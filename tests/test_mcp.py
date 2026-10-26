"""Slidemill's MCP endpoint: Claude writes a slideshow, the app renders, scores and queues it."""

from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

import tests.test_flow  # noqa: F401 - sets up env before app import
from app import db, imagegen, llm, mcp_server, store
from app.config import settings
from app.main import app
from tests.test_flow import AUTH, fake_image
from tests.test_virality import _library, fake_complete

PLAN = {
    "title": "Sleep fixes nobody tells you", "caption": "3 sleep fixes that actually work",
    "description": "Small changes, deep sleep. #sleep #sleeptips #nightroutine", "text_position": "center",
    "slides": [
        {"role": "hook", "image_query": "dark cozy bedroom", "image_prompt": "a dark cozy bedroom at night",
         "text_items": [{"text": "3 sleep fixes nobody tells you", "font": "tiktok", "font_size": "large", "text_style": "outline", "text_color": "white"}]},
        {"role": "body", "image_query": "blackout curtains", "image_prompt": "blackout curtains in a calm bedroom",
         "text_items": [{"text": "1. make the room pitch dark", "font": "tiktok", "font_size": "medium", "text_style": "outline", "text_color": "white"}]},
        {"role": "body", "image_query": "phone on nightstand", "image_prompt": "a phone face down on a nightstand",
         "text_items": [{"text": "2. phone out of reach", "font": "tiktok", "font_size": "medium", "text_style": "outline", "text_color": "white"}]},
        {"role": "body", "image_query": "morning sunlight window", "image_prompt": "soft morning sunlight through a window",
         "text_items": [{"text": "3. same wake time, even sundays", "font": "tiktok", "font_size": "medium", "text_style": "outline", "text_color": "white"}]},
    ],
}


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setattr(imagegen, "generate", fake_image)
    monkeypatch.setattr(llm, "complete_json", fake_complete)
    store.set_many({"llm_provider": "openai", "llm_api_key": "sk-test"})
    with TestClient(app, base_url=settings.public_base_url) as c:
        yield c
    store.set_many({"llm_provider": "", "llm_api_key": ""})


def rpc(client, method, params=None, path=None):
    r = client.post(path or f"/mcp/{mcp_server.token()}", json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}},
                    headers={"Accept": "application/json, text/event-stream", "MCP-Protocol-Version": "2025-06-18"})
    return r


def call(client, tool, **arguments):
    r = rpc(client, "tools/call", {"name": tool, "arguments": arguments})
    assert r.status_code == 200, r.text
    result = r.json()["result"]
    assert not result.get("isError"), result
    return result["content"]


def test_connector_url_is_secret_and_wrong_token_is_rejected(client):
    url = client.get("/api/v1/mcp", headers=AUTH).json()["url"]
    assert url == f"{settings.public_base_url}/mcp/{mcp_server.token()}"
    assert settings.api_key not in url
    assert rpc(client, "tools/list", path="/mcp/not-the-token").status_code == 404
    assert client.get("/api/v1/mcp").status_code == 401  # the URL itself is only shown to the owner


def test_claude_sees_the_tools(client):
    names = {t["name"] for t in rpc(client, "tools/list").json()["result"]["tools"]}
    assert {"writing_guide", "research_library", "create_slideshow", "view_slideshow", "predict_virality",
            "queue_post", "analytics", "best_times"} <= names


def test_claude_writes_and_the_app_renders(client):
    content = call(client, "create_slideshow", plan=PLAN)
    info = json.loads(content[0]["text"])
    assert info["status"] == "completed" and info["caption"] == PLAN["caption"]
    images = [c for c in content if c["type"] == "image"]
    assert len(images) == 4 and images[0]["mimeType"] == "image/jpeg"  # Claude sees what it made
    with db.session_scope() as s:
        show = s.get(db.Slideshow, info["id"])
        assert show.prompt == "Written by Claude"
        assert show.spec["slides"][0]["text_items"][0]["text"] == "3 sleep fixes nobody tells you"
    score = json.loads(call(client, "predict_virality", slideshow_id=info["id"])[0]["text"])
    assert 0 <= score["viral_potential"] <= 100
    post = json.loads(call(client, "queue_post", slideshow_id=info["id"])[0]["text"])
    assert post["status"] == "queued" and post["post_mode"] == "BROWSER"


def test_research_is_best_first_and_errors_come_back_readable(client):
    _library(6)
    rows = json.loads(call(client, "research_library", limit=3)[0]["text"])
    assert [r["slides"][0] for r in rows] == ["post5 hook", "post4 hook", "post3 hook"]
    r = rpc(client, "tools/call", {"name": "view_slideshow", "arguments": {"slideshow_id": "nope"}}).json()["result"]
    assert r["isError"] and "not found" in r["content"][0]["text"]


def test_create_can_queue_the_post(client):
    content = call(client, "create_slideshow", plan=PLAN, post="draft")
    assert content[1]["text"] == "Queued for TikTok as a draft."
    info = json.loads(content[0]["text"])
    with db.session_scope() as s:
        assert s.query(db.Post).filter_by(slideshow_id=info["id"]).count() == 1
