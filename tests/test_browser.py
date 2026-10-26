"""Browser-extension posting: queue, caps, gaps, drafts placeholder, retries, API endpoints."""

from __future__ import annotations

import io
import zipfile
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

import tests.test_flow  # noqa: F401 - sets up env before app import
from app import browser_queue, db, generator, imagegen, library, pipeline, store
from app.main import app
from tests.test_flow import AUTH, fake_image, fake_plan

T0 = datetime(2026, 10, 24, 12, 0, tzinfo=timezone.utc)


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setattr(generator, "generate_plan", fake_plan)
    monkeypatch.setattr(imagegen, "generate", fake_image)
    with TestClient(app) as c:
        yield c


@pytest.fixture(autouse=True)
def clean_queue():
    db.init_db()
    with db.session_scope() as s:
        s.query(db.Post).filter(db.Post.post_mode == "BROWSER").delete()
        s.query(db.Setting).filter(db.Setting.key.like("browser_%")).delete()
    browser_queue.last_seen.clear()
    yield


def make_show(client) -> str:
    r = client.post("/api/v1/slideshows/create", json={"slides": [{"text_items": [{"text": "a"}]}, {"text_items": [{"text": "b"}]}]}, headers=AUTH)
    sid = r.json()["id"]
    client.put(f"/api/v1/slideshows/{sid}", json={"caption": "5 habits that fixed my sleep", "description": "#sleep #habits"}, headers=AUTH)
    return sid


def queue(client, action="publish") -> str:
    sid = make_show(client)
    r = client.post(f"/api/v1/slideshows/{sid}/publish", json={"post_mode": "BROWSER", "browser_action": action, "sound": True}, headers=AUTH)
    assert r.status_code == 202, r.text
    assert r.json()["status"] == "queued" and r.json()["post_mode"] == "BROWSER"
    return r.json()["id"]


def test_publish_and_report(client):
    pid = queue(client)
    r = client.get("/api/v1/extension/next?version=2.0.0&username=tim&draft=0", headers=AUTH).json()
    job = r["job"]
    assert job["id"] == pid and job["action"] == "publish" and job["sound"] is True
    assert job["title"] == "5 habits that fixed my sleep" and job["caption"] == "#sleep #habits"
    assert len(job["images"]) == 2 and job["images"][0].endswith(".jpg")
    assert job["images"][0].startswith("http://testserver/media/")  # the address the client used
    # one at a time
    assert client.get("/api/v1/extension/next", headers=AUTH).json()["reason"] == "a post is already in progress"
    res = client.post(f"/api/v1/extension/jobs/{pid}/result", json={"ok": True, "item_id": "7400000000000000009", "username": "tim"}, headers=AUTH)
    assert res.json()["status"] == "published"
    # results can't be recorded twice
    assert client.post(f"/api/v1/extension/jobs/{pid}/result", json={"ok": True}, headers=AUTH).status_code == 404
    status = client.get("/api/v1/extension/status", headers=AUTH).json()
    assert status["done_last_24h"] == 1 and status["extension"]["username"] == "tim"


def test_gap_and_daily_cap(client):
    store.set_many({"browser_posts_per_day": "2", "browser_min_gap_minutes": "30"})
    ids = [queue(client) for _ in range(3)]
    j1 = browser_queue.claim_next(T0)["job"]
    browser_queue.record_result(j1["id"], True, "1")
    assert "waiting" in browser_queue.claim_next(T0 + timedelta(minutes=10))["reason"]
    j2 = browser_queue.claim_next(T0 + timedelta(minutes=31))["job"]
    browser_queue.record_result(j2["id"], True, "2")
    assert "daily limit" in browser_queue.claim_next(T0 + timedelta(hours=2))["reason"]
    j3 = browser_queue.claim_next(T0 + timedelta(hours=25))["job"]
    assert [j1["id"], j2["id"], j3["id"]] == ids  # oldest first


def test_drafts_wait_for_a_capable_extension(client):
    pid = queue(client, action="draft")
    r = browser_queue.claim_next(T0, {"draft": "0"})
    assert r["job"] is None and "can't save drafts yet" in r["reason"]
    assert browser_queue.status()["waiting_for_draft_support"] == 1
    # an extension that claims support but then reports unsupported hands the post back
    job = browser_queue.claim_next(T0, {"draft": "1"})["job"]
    assert job["id"] == pid and job["action"] == "draft"
    post = browser_queue.record_result(pid, False, error="not wired up", unsupported=True)
    assert post.status == "queued" and post.attempts == 0
    # once drafts work, a success is recorded as a draft, not a public post
    browser_queue.claim_next(T0 + timedelta(hours=1), {"draft": "1"})
    assert browser_queue.record_result(pid, True, "77").status == "sent_to_drafts"


def test_failures_retry_then_give_up(client):
    store.set_many({"browser_min_gap_minutes": "0"})
    pid = queue(client)
    for attempt in range(1, browser_queue.MAX_ATTEMPTS + 1):
        job = browser_queue.claim_next(T0 + timedelta(hours=attempt))["job"]
        assert job["id"] == pid
        post = browser_queue.record_result(pid, False, error="captcha")
    assert post.status == "failed" and post.error == "captcha"


def test_stale_claim_is_released(client):
    pid = queue(client)
    assert browser_queue.claim_next(T0)["job"]["id"] == pid
    assert browser_queue.claim_next(T0 + timedelta(minutes=5))["reason"] == "a post is already in progress"
    store.set_many({"browser_min_gap_minutes": "0"})
    again = browser_queue.claim_next(T0 + timedelta(minutes=25))["job"]
    assert again["id"] == pid


def test_automation_in_browser_mode_needs_no_account(client):
    body = {"title": "browser auto", "hooks": ["sleep tips"],
            "post_settings": {"post_mode": "BROWSER", "browser_action": "publish", "auto_post": True}}
    aid = client.post("/api/v1/automations", json=body, headers=AUTH).json()["id"]
    sid = pipeline.run_automation(aid)
    with db.session_scope() as s:
        posts = s.get(db.Slideshow, sid).posts
        assert len(posts) == 1 and posts[0].post_mode == "BROWSER" and posts[0].status == "queued"


def test_best_times_with_browser_mode_skips_api_consent(client):
    aid = client.post("/api/v1/automations", json={"title": "x", "hooks": ["h"]}, headers=AUTH).json()["id"]
    r = client.post(f"/api/v1/automations/{aid}/apply-timing",
                    json={"country": "RO", "posts_per_day": 2, "via_browser": True, "browser_action": "draft"}, headers=AUTH)
    assert r.status_code == 200, r.text
    ps = r.json()["automation"]["post_settings"]
    assert ps["post_mode"] == "BROWSER" and ps["browser_action"] == "draft"
    assert any("tiktok.com tab" in w for w in r.json()["warnings"])


def test_view_counts_from_public_page(client, monkeypatch):
    pid = queue(client)
    browser_queue.claim_next(T0)
    browser_queue.record_result(pid, True, "7400000000000000009", username="tim")
    seen = []
    monkeypatch.setattr(library, "fetch_post", lambda url: seen.append(url) or {"views": 5000, "likes": 300, "comments": 4, "shares": 9})
    browser_queue.refresh_metrics()
    assert seen == ["https://www.tiktok.com/@tim/photo/7400000000000000009"]
    with db.session_scope() as s:
        assert s.get(db.Post, pid).views == 5000


def test_extension_download_zip(client):
    r = client.get("/api/v1/extension/download", headers=AUTH)
    assert r.status_code == 200
    names = zipfile.ZipFile(io.BytesIO(r.content)).namelist()
    for f in ["manifest.json", "bg.js", "bridge.js", "tiktok-inject.js", "panel.js", "popup.html", "popup.js"]:
        assert f"slidemill-tiktok-poster/{f}" in names
    assert client.get("/api/v1/extension/next").status_code == 401  # password required
