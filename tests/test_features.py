"""Multi-provider AI, Studio (brief -> finished slideshows), and the TikTok slideshow library."""

from __future__ import annotations

import io
import json
import time

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image

import tests.test_flow  # noqa: F401 - sets up env (data dir, API key) before app import
from app import db, generator, imagegen, library, llm, pipeline, store
from app.main import app
from app.schemas import CampaignPlan, SlideshowPlan
from tests.test_flow import AUTH, fake_image, fake_plan


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setattr(generator, "generate_plan", fake_plan)
    monkeypatch.setattr(imagegen, "generate", fake_image)
    with TestClient(app) as c:
        yield c


@pytest.fixture(autouse=True)
def clear_settings():
    db.init_db()
    with db.session_scope() as s:
        s.query(db.Setting).delete()
    yield


# --- any AI key -----------------------------------------------------------------------------


@pytest.mark.parametrize(
    "key,provider",
    [
        ("sk-ant-api03-abc", "anthropic"),
        ("sk-proj-abc", "openai"),
        ("AIzaSyAbc", "gemini"),
        ("sk-or-v1-abc", "openrouter"),
        ("gsk_abc", "groq"),
        ("xai-abc", "xai"),
        ("a" * 64, "together"),
        ("whatever", None),
    ],
)
def test_detect_provider(key, provider):
    assert llm.detect_provider(key) == provider


def test_settings_api_detects_provider_and_masks_key(client):
    r = client.put("/api/v1/settings", json={"values": {"llm_api_key": "AIzaSyTESTTESTTESTTEST", "llm_provider": ""}}, headers=AUTH)
    assert r.status_code == 200, r.text
    vals = r.json()["values"]
    assert vals["llm_provider"]["value"] == "gemini"
    assert "TESTTESTTEST" not in vals["llm_api_key"]["value"]
    cfg = llm.resolve()
    assert cfg.provider == "gemini" and cfg.model == llm.PRESETS["gemini"].default_model
    assert "generativelanguage" in cfg.base_url
    # Gemini key also drives image generation
    assert imagegen.resolve().provider == "gemini"


def test_openai_compatible_json_with_repair(monkeypatch):
    store.set_many({"llm_provider": "groq", "llm_api_key": "gsk_test"})
    plan = fake_plan("topic").model_dump()
    replies = iter(["not json at all", "```json\n" + json.dumps(plan) + "\n```"])
    seen = []

    def fake_post(url, json=None, headers=None, timeout=None):
        seen.append((url, json, headers))
        content = next(replies)
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}]}, request=httpx.Request("POST", url))

    monkeypatch.setattr(llm.httpx, "post", fake_post)
    result = llm.complete_json("sys", "user", SlideshowPlan)
    assert result.slides[0].text_items[0].text == "topic"
    url, body, headers = seen[0]
    assert url == "https://api.groq.com/openai/v1/chat/completions"
    assert headers["Authorization"] == "Bearer gsk_test"
    assert body["response_format"] == {"type": "json_object"}
    assert "JSON Schema" in body["messages"][0]["content"]
    assert len(seen) == 2  # second call carried the validation error back


def test_ollama_needs_no_key(monkeypatch):
    store.set_many({"llm_provider": "ollama", "llm_api_key": ""})
    cfg = llm.resolve()
    assert cfg and cfg.base_url == "http://localhost:11434/v1"


def test_image_fallback_is_free_pollinations():
    store.set_many({"llm_provider": "groq", "llm_api_key": "gsk_x"})
    assert imagegen.resolve().provider == "pollinations"
    store.set_many({"llm_provider": "openai", "llm_api_key": "sk-x"})
    assert imagegen.resolve().provider == "openai"


# --- studio ---------------------------------------------------------------------------------


def wait_job(client, job_id, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = client.get(f"/api/v1/jobs/{job_id}", headers=AUTH).json()
        pending = any(s["status"] not in {"completed", "failed"} for s in job.get("slideshows", []))
        if job["status"] in {"completed", "failed"} and not pending:
            return job
        time.sleep(0.2)
    raise AssertionError(f"job timed out: {job}")


def test_studio_makes_complete_slideshows_with_ai_images(client, monkeypatch):
    store.set_many({"llm_provider": "openai", "llm_api_key": "sk-test"})
    monkeypatch.setattr(
        generator,
        "plan_campaign",
        lambda brief, n: CampaignPlan(
            name="Glow up", format_summary="6-slide self-care listicles", style="6 slides, casual",
            visual_style="soft pink film photos", aspect_ratio="4:5", hooks=[f"hook {i}" for i in range(n)], language="English",
        ),
    )
    r = client.post(
        "/api/v1/studio",
        json={"brief": "glow up tips for girls", "count": 3, "image_source": "ai", "save_as_automation": True, "schedule": ["0 9 * * *"]},
        headers=AUTH,
    )
    assert r.status_code == 202, r.text
    job = wait_job(client, r.json()["job_id"])
    assert job["status"] == "completed", job
    assert len(job["slideshows"]) == 3
    for s in job["slideshows"]:
        assert s["status"] == "completed" and len(s["slides"]) == 4
        full = client.get(f"/api/v1/slideshows/{s['id']}", headers=AUTH).json()
        assert all(sl["image"].startswith("library/ai/") for sl in full["spec"]["slides"])
    automation = client.get(f"/api/v1/automations/{job['result']['automation_id']}", headers=AUTH).json()
    assert len(automation["hooks"]) == 13 and automation["image_settings"]["visual_style"] == "soft pink film photos"
    assert automation["schedule"][0]["cron"] == "0 9 * * *"


def test_studio_requires_ai_key(client):
    assert client.post("/api/v1/studio", json={"brief": "x"}, headers=AUTH).status_code == 422


# --- library --------------------------------------------------------------------------------

WEB_ITEM = {
    "id": "7400000000000000001",
    "desc": "things that changed my skin #skincare",
    "createTime": 1760000000,
    "author": {"uniqueId": "glowdaily", "nickname": "Glow Daily", "signature": "skincare that works", "verified": False},
    "authorStats": {"followerCount": 120000, "heartCount": 3400000, "videoCount": 210},
    "stats": {"playCount": 850000, "diggCount": 61000, "commentCount": 300, "shareCount": 4000, "collectCount": 22000},
    "imagePost": {"images": [{"imageURL": {"urlList": [f"https://cdn.example/slide{i}.jpeg"]}} for i in range(3)]},
    "music": {"title": "original sound", "authorName": "glowdaily"},
}


def test_normalize_web_item():
    post = library.normalize_web_item(WEB_ITEM)
    assert post["is_slideshow"] and len(post["slide_urls"]) == 3
    assert post["views"] == 850000 and post["saves"] == 22000 and post["username"] == "glowdaily"
    assert post["author"]["followers"] == 120000


def test_parse_item():
    assert library.parse_item("@glowdaily") == ("profile", "glowdaily")
    assert library.parse_item("https://www.tiktok.com/@glowdaily") == ("profile", "glowdaily")
    assert library.parse_item("https://www.tiktok.com/@glowdaily/photo/7400000000000000001?lang=en") == (
        "post", "https://www.tiktok.com/@glowdaily/video/7400000000000000001",
    )


def _fake_download(candidates, folder):
    buf = io.BytesIO()
    Image.new("RGB", (300, 400), (240, 200, 210)).save(buf, "JPEG")
    return library.images.store_bytes(buf.getvalue(), folder)[0]


def test_library_import_search_analyze_remix(client, monkeypatch):
    monkeypatch.setattr(library, "fetch_post", lambda url: library.normalize_web_item(WEB_ITEM))
    monkeypatch.setattr(library, "_download", _fake_download)
    monkeypatch.setattr(library.time, "sleep", lambda s: None)
    store.set_many({"llm_provider": "openai", "llm_api_key": "sk-test"})

    def fake_complete(system, user, model, images=None, cfg=None):
        if model.__name__ == "ProfileClassification":
            return model(niche="skincare", product_medium="creator")
        if model.__name__ == "SlideTextRead":
            assert images and images[0].startswith("data:image/jpeg;base64,")
            return model(slides=["things that changed my skin", "1. double cleanse", "2. spf daily"])
        raise AssertionError(model)

    monkeypatch.setattr(library.llm, "complete_json", fake_complete)

    r = client.post("/api/v1/library/import", json={"items": ["https://www.tiktok.com/@glowdaily/photo/7400000000000000001"]}, headers=AUTH)
    assert r.status_code == 202
    job = wait_job(client, r.json()["job_id"])
    assert job["status"] == "completed", job

    niches = client.get("/api/v1/library/niches", headers=AUTH).json()["niches"]
    assert niches == [{"niche": "skincare", "profile_count": 1}]
    found = client.get("/api/v1/library?q=changed my skin", headers=AUTH).json()
    assert found["total"] == 1
    prof = found["profiles"][0]
    assert prof["username"] == "glowdaily" and prof["followers"] == 120000 and prof["slideshow_count"] == 1

    detail = client.get(f"/api/v1/library/profiles/{prof['id']}", headers=AUTH).json()
    post = detail["slideshows"][0]
    assert len(post["images"]) == 3

    texts = client.post(f"/api/v1/library/posts/{post['id']}/analyze", headers=AUTH).json()["slide_texts"]
    assert texts[1] == "1. double cleanse"

    captured = {}
    orig = pipeline.generate_into

    def spy(sid, **kw):
        captured.update(kw)
        return orig(sid, **kw)

    monkeypatch.setattr(pipeline, "generate_into", spy)
    r = client.post(f"/api/v1/library/posts/{post['id']}/remix", json={"count": 1, "topic": "acne myths"}, headers=AUTH)
    assert r.status_code == 202, r.text
    sid = r.json()["slideshow_ids"][0]
    deadline = time.time() + 30
    while client.get(f"/api/v1/slideshows/{sid}/status", headers=AUTH).json()["status"] not in {"completed", "failed"}:
        assert time.time() < deadline
        time.sleep(0.2)
    assert "1. double cleanse" in captured["reference"]
    assert client.get(f"/api/v1/slideshows/{sid}", headers=AUTH).json()["prompt"] == "acne myths"
