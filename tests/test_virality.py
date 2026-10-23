"""Virality prediction: scoring math, format rules, stored predictions, calibration, pre-test."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

import tests.test_flow  # noqa: F401 - sets up env before app import
from app import db, generator, imagegen, llm, pipeline, store, virality
from app.main import app
from app.schemas import SlideshowSpec, ViralityRead
from tests.test_flow import AUTH, fake_image, fake_plan

SCORES: list[int] = []
SEEN: dict = {}


def fake_complete(system, user, model, images=None, cfg=None):
    assert model is ViralityRead
    n = int(user.split("There are ")[1].split(" slides")[0])
    SEEN["images"] = len(images or [])
    hook = SCORES.pop(0) if SCORES else 64
    return ViralityRead(
        hook_score=hook, curiosity=70, relatability=60, payoff=55, shareability=50,
        slides=[{"swipe_on": 80, "visual_pull": 60, "clarity": 70, "note": "ok"} for _ in range(n)],
        tips=["Make slide 2 more specific."], better_hook="the sleep habit nobody tells you",
    )


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setattr(generator, "generate_plan", fake_plan)
    monkeypatch.setattr(imagegen, "generate", fake_image)
    monkeypatch.setattr(llm, "complete_json", fake_complete)
    store.set_many({"llm_provider": "openai", "llm_api_key": "sk-test"})
    SCORES.clear()
    with TestClient(app) as c:
        yield c
    store.set_many({"llm_provider": "", "llm_api_key": ""})


def make(client, texts):
    r = client.post("/api/v1/slideshows/create", json={"slides": [{"text_items": [{"text": t}]} for t in texts]}, headers=AUTH)
    assert r.status_code == 201, r.text
    return r.json()["id"]


def test_format_rules_and_spearman():
    spec = SlideshowSpec.model_validate({"slides": [{"role": "hook", "text_items": [{"text": "5 habits that fixed my sleep"}]}]
                                         + [{"text_items": [{"text": f"{i}. x"}]} for i in range(1, 4)]})
    score, problems = virality.format_check(spec)
    assert score < 100 and any("promises 5 but there are 3" in p for p in problems)
    assert virality.spearman([1, 2, 3, 4], [10, 20, 30, 40]) == pytest.approx(1.0)
    assert virality.spearman([1, 2], [1, 2]) is None


def test_predict_endpoint(client):
    sid = make(client, ["5 habits that fixed my sleep", "1. dark room", "2. no phone", "3. same wake time", "4. cold room", "5. no caffeine"])
    SCORES.append(80)
    r = client.post(f"/api/v1/slideshows/{sid}/predict", headers=AUTH)
    assert r.status_code == 200, r.text
    p = r.json()
    assert p["hook_score"] == 80 and p["attention"][0] == 100 and len(p["attention"]) == 6
    assert p["hold_rate"] == 33  # 0.8 ** 5
    assert p["viral_potential"] == int(0.40 * 80 + 0.25 * 32.768 + 0.20 * 58.75 + 0.15 * 100)
    assert SEEN["images"] == 6  # the vision model saw the rendered slides
    assert client.get(f"/api/v1/slideshows/{sid}", headers=AUTH).json()["prediction"]["better_hook"] == "the sleep habit nobody tells you"
    listed = {s["id"]: s for s in client.get("/api/v1/slideshows?limit=100", headers=AUTH).json()["slideshows"]}
    assert listed[sid]["prediction"]["viral_potential"] == p["viral_potential"]
    assert client.post("/api/v1/slideshows/nope/predict", headers=AUTH).status_code == 404


def test_calibration(client):
    with db.session_scope() as s:
        s.query(db.Slideshow).update({db.Slideshow.prediction: None})
    for i in range(8):
        sid = make(client, [f"hook {i}", "a", "b", "c", "d"])
        SCORES.append(30 + i * 8)
        client.post(f"/api/v1/slideshows/{sid}/predict", headers=AUTH)
        with db.session_scope() as s:
            s.add(db.Post(slideshow_id=sid, post_mode="BROWSER", status="published", views=1000 * (i + 1)))
    cal = client.get("/api/v1/virality/calibration", headers=AUTH).json()
    assert cal["posts"] == 8 and cal["spearman"] == 1.0 and "tracks your views well" in cal["summary"]


def test_pretest_keeps_better_version(client):
    body = {"title": "pretest", "hooks": ["sleep tips"], "min_viral_score": 60,
            "post_settings": {"post_mode": "BROWSER", "auto_post": True}}
    aid = client.post("/api/v1/automations", json=body, headers=AUTH).json()["id"]
    assert client.get(f"/api/v1/automations/{aid}", headers=AUTH).json()["min_viral_score"] == 60
    SCORES.extend([10, 95])
    kept = pipeline.run_automation(aid)
    with db.session_scope() as s:
        shows = s.query(db.Slideshow).filter(db.Slideshow.automation_id == aid).all()
        assert [x.id for x in shows] == [kept]
        assert shows[0].prediction["hook_score"] == 95
        assert len(shows[0].posts) == 1
