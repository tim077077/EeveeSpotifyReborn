"""End-to-end flow with Claude and TikTok stubbed: generate -> render -> publish -> track."""

from __future__ import annotations

import io
import os
import tempfile
import time

os.environ["SLIDEMILL_DATA_DIR"] = tempfile.mkdtemp(prefix="slidemill-test-")
os.environ["SLIDEMILL_SCHEDULER"] = "0"
os.environ["SLIDEMILL_API_KEY"] = "test-key"

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app import db, generator, imagegen, pipeline, tiktok
from app.config import settings
from app.main import app
from app.schemas import PlanSlide, PlanTextItem, SlideshowPlan

AUTH = {"Authorization": "Bearer test-key"}


def fake_plan(topic, style="", language="English", product=None, recent_titles=None, slide_count_hint=None, **_):
    def item(text, size="medium"):
        return PlanTextItem(text=text, font="tiktok", font_size=size, text_style="outline", text_color="white")

    return SlideshowPlan(
        title=f"{topic} #{len(recent_titles or [])}",
        caption="5 habits that fixed my sleep",
        description="what actually worked for me #sleep #habits",
        text_position="center",
        slides=[
            PlanSlide(role="hook", text_items=[item(topic, "large")], image_query="cozy bedroom night", image_prompt="cozy bedroom at night"),
            *[PlanSlide(role="body", text_items=[item(f"{i}. tip number {i}")], image_query="calm morning", image_prompt="calm morning light") for i in range(1, 4)],
        ],
    )


def fake_image(prompt, aspect_ratio="4:5", visual_style="", cfg=None):
    buf = io.BytesIO()
    Image.new("RGB", (400, 500), (90, 60, 120)).save(buf, "PNG")
    return buf.getvalue()


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setattr(generator, "generate_plan", fake_plan)
    monkeypatch.setattr(imagegen, "generate", fake_image)
    calls = {}

    def fake_publish(account, urls, **kw):
        calls["publish"] = (urls, kw)
        return "pub_123"

    monkeypatch.setattr(tiktok, "publish_photos", fake_publish)
    monkeypatch.setattr(
        tiktok, "publish_status", lambda a, pid: {"status": "PUBLISH_COMPLETE", "publicaly_available_post_id": [777]}
    )
    monkeypatch.setattr(
        tiktok, "video_metrics", lambda a, ids: [{"id": "777", "view_count": 1200, "like_count": 90, "comment_count": 4, "share_count": 7}]
    )
    with TestClient(app) as c:
        c.calls = calls
        yield c


def wait_done(client, sid, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        st = client.get(f"/api/v1/slideshows/{sid}/status", headers=AUTH).json()
        if st["status"] in {"completed", "failed"}:
            return st
        time.sleep(0.2)
    raise AssertionError("timed out")


def add_account():
    with db.session_scope() as s:
        a = db.TikTokAccount(open_id="oid", username="tester", access_token="tok")
        s.add(a)
        s.flush()
        return a.id


def test_requires_api_key(client):
    assert client.get("/api/v1/status").status_code == 401


def test_generate_render_publish_track(client):
    r = client.post("/api/v1/slideshows/generate", json={"prompt": "5 habits that fixed my sleep"}, headers=AUTH)
    assert r.status_code == 201, r.text
    sid = r.json()["slideshow_id"]
    assert wait_done(client, sid)["status"] == "completed"

    show = client.get(f"/api/v1/slideshows/{sid}", headers=AUTH).json()
    assert len(show["slides"]) == 4
    for url in show["slides"]:
        rel = url.split("/media/", 1)[1]
        img = Image.open(settings.media_dir / rel)
        assert img.size == (1080, 1350)

    # edit + re-render
    spec = show["spec"]
    spec["slides"][0]["text_items"][0]["text_style"] = "background"
    spec["aspect_ratio"] = "9:16"
    r = client.put(f"/api/v1/slideshows/{sid}", json={"spec": spec, "caption": "new caption"}, headers=AUTH)
    assert r.status_code == 200 and r.json()["caption"] == "new caption"
    assert Image.open(settings.media_dir / r.json()["slides"][0].split("/media/", 1)[1]).size == (1080, 1920)

    account_id = add_account()
    r = client.post(f"/api/v1/slideshows/{sid}/publish", json={"tiktok_account_id": account_id}, headers=AUTH)
    assert r.status_code == 202, r.text
    assert r.json()["status"] == "processing" and r.json()["post_mode"] == "MEDIA_UPLOAD"
    urls, kw = client.calls["publish"]
    assert len(urls) == 4 and urls[0].startswith(settings.public_base_url + "/media/")
    assert kw["title"] == "new caption"

    pipeline.refresh_posts()
    posts = client.get("/api/v1/posts", headers=AUTH).json()
    mine = [p for p in posts["posts"] if p["slideshow_id"] == sid]
    assert mine[0]["status"] == "published" and mine[0]["views"] == 1200
    assert posts["statistics"]["views"] == sum(p["views"] for p in posts["posts"])


def test_direct_post_limit_falls_back_to_draft(client):
    account_id = add_account() if not _account() else _account()
    sid = client.post("/api/v1/slideshows/create", json={"slides": [{"text_items": [{"text": "hi"}]}]}, headers=AUTH).json()["id"]
    modes = []
    for _ in range(settings.tiktok_direct_posts_per_day + 1):
        r = client.post(f"/api/v1/slideshows/{sid}/publish", json={"tiktok_account_id": account_id, "post_mode": "DIRECT_POST", "privacy_level": "SELF_ONLY", "consent": True}, headers=AUTH)
        modes.append(r.json()["post_mode"])
    assert modes[-1] == "MEDIA_UPLOAD"


def _account():
    with db.session_scope() as s:
        a = s.query(db.TikTokAccount).first()
        return a.id if a else None


def test_automation_schedule_and_run(client):
    account_id = _account() or add_account()
    # collection with uploaded images
    cid = client.post("/api/v1/collections", json={"name": "bg"}, headers=AUTH).json()["id"]
    buf = io.BytesIO()
    Image.new("RGB", (800, 1000), (200, 120, 90)).save(buf, "JPEG")
    files = [("files", (f"{i}.jpg", buf.getvalue(), "image/jpeg")) for i in range(3)]
    assert client.post(f"/api/v1/collections/{cid}/upload", files=files, headers=AUTH).status_code == 201

    body = {
        "title": "Sleep tips",
        "tiktok_account_id": account_id,
        "hooks": ["5 habits that fixed my sleep", "things I stopped doing before bed"],
        "schedule": ["0 9 * * *", "30 18 * * 1-5"],
        "image_settings": {"source": "collection", "collection_id": cid, "overlay_body": True},
    }
    r = client.post("/api/v1/automations", json=body, headers=AUTH)
    assert r.status_code == 201, r.text
    aid = r.json()["id"]
    assert len(r.json()["schedule"]) == 2

    bad = client.patch(f"/api/v1/automations/{aid}", json={"schedule": ["not a cron"]}, headers=AUTH)
    assert bad.status_code == 422

    sid = pipeline.run_automation(aid)
    show = client.get(f"/api/v1/slideshows/{sid}", headers=AUTH).json()
    assert show["status"] == "completed"
    assert all(s["image"] and s["image"].startswith(f"library/{cid}/") for s in show["spec"]["slides"])
    assert show["posts"] and show["posts"][0]["status"] == "processing"

    # second run rotates to the unused hook
    sid2 = pipeline.run_automation(aid)
    assert client.get(f"/api/v1/slideshows/{sid2}", headers=AUTH).json()["prompt"] != show["prompt"]

    # paused automations skip scheduled runs
    client.patch(f"/api/v1/automations/{aid}", json={"paused": True}, headers=AUTH)
    assert pipeline.run_automation(aid, cron_id="x") is None


def test_video_export(client):
    sid = client.post(
        "/api/v1/slideshows/create", json={"slides": [{"text_items": [{"text": "one"}]}, {"text_items": [{"text": "two"}]}]}, headers=AUTH
    ).json()["id"]
    r = client.post(f"/api/v1/slideshows/{sid}/video?seconds_per_slide=1", headers=AUTH)
    assert r.status_code == 200, r.text
    assert (settings.media_dir / r.json()["video_url"].split("/media/", 1)[1]).stat().st_size > 0
