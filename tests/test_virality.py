"""Virality prediction: scoring math, format rules, stored predictions, calibration, pre-test."""

from __future__ import annotations

import random
import re

import pytest
from fastapi.testclient import TestClient

import tests.test_flow  # noqa: F401 - sets up env before app import
from app import db, generator, imagegen, llm, pipeline, store, virality
from app.main import app
from app.schemas import SlideshowSpec, ViralityRead
from tests.test_flow import AUTH, fake_image, fake_plan

SCORES: list[int] = []
SEEN: dict = {}


def rank_by(true_value):
    """A fake blind ranker: orders the comparison's slideshows by true_value(their slide text)."""
    def rank(user):
        items = re.findall(r"^Slideshow ([A-G])(?: \(this one\))?: (.*)$", user, re.M)
        return [x for x, _ in sorted(items, key=lambda t: -true_value(t[1]))]
    return rank


RANKER = {"fn": None, "transcribe": None}
CALLS: list[str] = []


def fake_complete(system, user, model, images=None, cfg=None):
    assert issubclass(model, ViralityRead)
    CALLS.append(user)
    n = int(user.split("There are ")[1].split(" slides")[0])
    SEEN["images"] = len(images or [])
    hook = SCORES.pop(0) if SCORES else 64
    extra = {}
    if "rank_order" in model.model_fields:
        letters = re.findall(r"^Slideshow ([A-G])", user, re.M)
        extra["rank_order"] = RANKER["fn"](user) if RANKER["fn"] else letters
    if "slide_texts" in model.model_fields:
        extra["slide_texts"] = RANKER["transcribe"](user) if RANKER["transcribe"] else [f"read {i}" for i in range(n)]
    return model(
        hook_score=hook, curiosity=70, relatability=60, payoff=55, shareability=50,
        slides=[{"swipe_on": 80, "visual_pull": 60, "clarity": 70, "note": "ok"} for _ in range(n)],
        tips=["Make slide 2 more specific."], better_hook="the sleep habit nobody tells you", **extra,
    )


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setattr(generator, "generate_plan", fake_plan)
    monkeypatch.setattr(imagegen, "generate", fake_image)
    monkeypatch.setattr(llm, "complete_json", fake_complete)
    store.set_many({"llm_provider": "openai", "llm_api_key": "sk-test"})
    SCORES.clear()
    CALLS.clear()
    RANKER["fn"] = None
    RANKER["transcribe"] = None
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


def _library(n: int, texts=None) -> None:
    """One library account with n slideshows; post i has (i+1)k views and hook "post{i} hook"."""
    from app.config import settings

    (settings.media_dir / "library").mkdir(parents=True, exist_ok=True)
    (settings.media_dir / "library" / "bt.jpg").write_bytes(b"\xff\xd8\xff\xd9")
    with db.session_scope() as s:
        s.query(db.LibraryPost).delete()
        s.query(db.LibraryProfile).delete()
        prof = db.LibraryProfile(username="backtester")
        s.add(prof)
        s.flush()
        for i in range(n):
            s.add(db.LibraryPost(id=f"bt{i}", profile_id=prof.id, is_slideshow=True, views=1000 * (i + 1),
                                 images=["library/bt.jpg"] * 4,
                                 slide_texts=texts(i) if texts else [f"post{i} hook", "b", "c", "d"]))


def _n(text: str) -> int:
    return int(re.search(r"post(\d+)", text).group(1))


def _backtest_fake(order):
    def fake(system, user, model, images=None, cfg=None):
        own = user.split("1. [hook] ")[1].split("\n")[0]
        assert f'"{own}" (' not in user, "a post must not see its own result as a calibration example"
        comp = [line for line in user.splitlines() if line.startswith("Slideshow ")]
        assert len({_n(x) for x in comp}) == len(comp), "a post must not be ranked against itself"
        r = fake_complete(system, user, model, images, cfg)
        r.hook_score = order(_n(own))
        return r
    return fake


def test_backtest_ranks_like_views(client, monkeypatch):
    _library(10)
    monkeypatch.setattr(llm, "complete_json", _backtest_fake(lambda i: 20 + i * 7))
    RANKER["fn"] = rank_by(_n)
    out = virality.backtest()
    assert out["posts"] == 10 and out["spearman"] == 1.0 and "real views in your library" in out["summary"]
    assert out["rows"][0]["post_id"] == "bt9" and out["rows"][0]["lift"] > 1
    rho = {m["method"]: m["spearman"] for m in out["methods"]}
    assert rho["rank"] >= 0.9
    assert out["method"] == "current"  # nothing beat a perfect AI score by the margin
    assert out["noise"] == 0
    assert client.get("/api/v1/virality/calibration", headers=AUTH).json()["backtest"]["spearman"] == 1.0


def test_backtest_switches_to_ranking_when_it_tracks_better(client, monkeypatch):
    _library(10)
    monkeypatch.setattr(llm, "complete_json", _backtest_fake(lambda i: [60, 20, 80, 40, 30, 90, 10, 70, 50, 35][i]))
    RANKER["fn"] = rank_by(_n)
    out = virality.backtest()
    rho = {m["method"]: m["spearman"] for m in out["methods"]}
    assert out["spearman"] < 0.15 and "isn't tracking" in out["summary"]
    assert rho["rank"] >= 0.9 and out["method"] in ("rank", "blend") and "now scores with" in out["summary"]
    # New predictions use the winner: a slideshow written like the best library post ranks at the top.
    monkeypatch.setattr(llm, "complete_json", fake_complete)
    RANKER["fn"] = rank_by(lambda t: _n(t) if "post" in t else 99)
    sid = make(client, ["the sleep habit nobody tells you", "a", "b", "c", "d"])
    p = client.post(f"/api/v1/slideshows/{sid}/predict", headers=AUTH).json()
    assert p["method"] == out["method"] and p["rank"]["beats"] == p["rank"]["references"]
    assert p["ai_score"] != p["viral_potential"] or out["method"] == "current"


def test_similar_posts_finds_the_near_duplicate():
    rows = [{"id": str(i), "texts": [t], "lift": 0.0, "hook": t, "url": "", "username": "u"} for i, t in enumerate([
        "5 morning habits that fixed my skin", "what I eat in a day as a nurse", "my night routine for deep sleep",
        "things nobody tells you about sleep", "how I save money on groceries"])]
    top = virality.similar_posts("the night routine that gave me deep sleep", rows)
    assert top[0][1]["id"] == "2"
    assert virality.similar_evidence("the night routine that gave me deep sleep", rows) is None  # needs 3 matches


def test_learned_weights_only_win_out_of_sample():
    rnd = random.Random(7)

    def rows(signal: bool):
        out = []
        for i in range(40):
            hook, fmt = rnd.randint(20, 90), rnd.randint(40, 100)
            y = (0.05 * hook + 0.03 * fmt + rnd.gauss(0, 0.3)) if signal else rnd.gauss(0, 1)
            f = {"current": rnd.randint(30, 90), "hook": hook, "hold": rnd.randint(20, 80), "engagement": 60,
                 "format": fmt, "rank": None, "similar": None}
            out.append({"f": f, "lift_log": y})
        return out

    methods, winner, model, _ = virality.compare_methods(rows(True))
    assert winner == "learned" and model is not None
    assert next(m for m in methods if m["method"] == "learned")["spearman"] > 0.5
    _, winner, model, _ = virality.compare_methods(rows(False))  # pure noise: fitting can't help out of sample
    assert winner != "learned" and model is None


def test_switch_needs_a_real_margin():
    rnd = random.Random(3)
    rows = []
    for i in range(20):
        y = i / 10
        rows.append({"lift_log": y, "f": {"current": i * 4 + rnd.choice([0, 1]), "hook": 50, "hold": 50, "engagement": 50,
                                          "format": 50, "rank": i * 4, "similar": None}})
    _, winner, _, _ = virality.compare_methods(rows)
    assert winner == "current"


def test_backtest_job_and_small_library(client, monkeypatch):
    import time

    _library(4)
    job = client.post("/api/v1/virality/backtest", headers=AUTH).json()["job_id"]
    for _ in range(100):
        j = client.get(f"/api/v1/jobs/{job}", headers=AUTH).json()
        if j["status"] in ("completed", "failed"):
            break
        time.sleep(0.05)
    assert j["status"] == "failed" and "needs 8" in j["message"]
    _library(12)
    monkeypatch.setattr(llm, "complete_json", _backtest_fake(lambda i: 20 + i * 5))
    job = client.post("/api/v1/virality/backtest", headers=AUTH).json()["job_id"]
    for _ in range(200):
        j = client.get(f"/api/v1/jobs/{job}", headers=AUTH).json()
        if j["status"] in ("completed", "failed"):
            break
        time.sleep(0.05)
    assert j["status"] == "completed" and j["result"]["backtest"]["posts"] == 12


def test_one_call_per_prediction_and_none_when_unchanged(client):
    _library(10)
    sid = make(client, ["the sleep habit nobody tells you", "a", "b", "c", "d"])
    CALLS.clear()
    p = client.post(f"/api/v1/slideshows/{sid}/predict", headers=AUTH).json()
    assert len(CALLS) == 1 and p["rank"]["references"] == 6  # the ranking came in the same call
    again = client.post(f"/api/v1/slideshows/{sid}/predict", headers=AUTH).json()
    assert len(CALLS) == 1 and again["cached"] and again["viral_potential"] == p["viral_potential"]
    with db.session_scope() as s:
        show = s.get(db.Slideshow, sid)
        spec = dict(show.spec)
        spec["slides"] = [dict(x) for x in spec["slides"]]
        spec["slides"][1]["text_items"] = [{"text": "a sharper second slide"}]
        show.spec = spec
    fresh = client.post(f"/api/v1/slideshows/{sid}/predict", headers=AUTH).json()
    assert len(CALLS) == 2 and not fresh.get("cached")


def test_backtest_is_one_call_per_post_and_reads_unread_posts_in_it(client):
    _library(10, texts=lambda i: [f"post{i} hook", "b", "c", "d"] if i % 2 else [])
    out = virality.backtest()
    assert out["posts"] == 10 and out["version"] == virality.BACKTEST_VERSION
    assert len(CALLS) <= 10 + virality.NOISE_POSTS
    with db.session_scope() as s:
        assert s.get(db.LibraryPost, "bt0").slide_texts == ["read 0", "read 1", "read 2", "read 3"]  # read for free
    transcribing = [c for c in CALLS if "(unknown: read it off" in c]
    assert len(transcribing) == 5


def test_an_old_library_test_does_not_pick_the_method(client):
    import json

    _library(10)
    with db.session_scope() as s:
        s.merge(db.Setting(key=virality.BACKTEST_KEY, value=json.dumps({"method": "rank", "methods": [], "populations": {}})))
    sid = make(client, ["the sleep habit nobody tells you", "a", "b", "c", "d"])
    p = client.post(f"/api/v1/slideshows/{sid}/predict", headers=AUTH).json()
    assert p["method"] == "current" and p["viral_potential"] == p["ai_score"]
    with db.session_scope() as s:
        s.query(db.Setting).filter(db.Setting.key == virality.BACKTEST_KEY).delete()


def test_reference_covers_only_when_the_call_stays_small(client):
    _library(10)
    sid = make(client, ["the sleep habit nobody tells you", "a", "b", "c", "d"])
    client.post(f"/api/v1/slideshows/{sid}/predict", headers=AUTH)
    assert SEEN["images"] == 5 + 6  # 5 slides + 6 reference covers
    long_sid = make(client, ["the sleep habit nobody tells you"] + [f"tip {i}" for i in range(9)])
    client.post(f"/api/v1/slideshows/{long_sid}/predict", headers=AUTH)
    assert SEEN["images"] == 10  # 10 slides; covers would pass the cap, so the ranking is by text
