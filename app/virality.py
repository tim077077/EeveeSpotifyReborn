"""Virality prediction for a slideshow before it's posted (same model as android/core Virality.kt).

Higgsfield's Virality Predictor runs a brain-response model over a video and turns predicted cortex
activity into a hook score, a hold rate and a viral-potential number. A slideshow has no motion or
sound, so here a vision model reads the rendered slides like a scrolling viewer (calibrated with real
hooks from the library that over- and under-performed), and plain rules check the format. Every
prediction is stored, so analytics can later show whether it actually tracks your views.
"""

from __future__ import annotations

import base64
import json
import math
import re
import statistics
import zlib
from datetime import datetime, timezone

from sqlalchemy import select

from . import db, llm
from .config import settings
from .schemas import RankRead, SlideshowSpec, ViralityRead

SYSTEM_PROMPT = """You are a TikTok photo-carousel (slideshow) performance analyst. You predict how real viewers on the For You feed react to a slideshow before it is posted.

How viewers behave: they decide in under a second from slide 1 whether to stop scrolling (the hook). Each later slide either earns the next swipe (new, specific, a little surprising, easy to read in 2 seconds) or loses them (repetitive, vague, wall of text, unreadable over the image, the promised payoff never arrives). Saves and shares come from practical value, identity ("this is so me") and curiosity that pays off.

Score honestly against what actually performs on TikTok; most slideshows are average (50-65). Reserve 85+ for hooks that would stop almost anyone in the niche. Penalize: generic hooks, clickbait the slides don't deliver, numbered hooks whose count doesn't match the slides, text hard to read over the photo, more than ~25 words on a slide, slides that repeat each other.

For each slide in order, give swipe_on: the percent of viewers who reach this slide and swipe on to the next one (for the last slide: the percent who finish and engage). Tips must be concrete edits to this slideshow, not general advice."""

NUMBER_HOOK = re.compile(
    r"\b(\d{1,2})\s+(?:[a-z]+\s+){0,3}?(things|habits|ways|tips|signs|reasons|mistakes|rules|steps|lessons|foods|apps|books|places|ideas|secrets|truths|facts)\b",
    re.I,
)


class PredictError(RuntimeError):
    pass


def verdict(score: int) -> tuple[str, str]:
    if score >= 85:
        return "Scroll-stopper.", "Top-decile hook"
    if score >= 70:
        return "Strong.", "Above average"
    if score >= 55:
        return "Watchable.", "Average"
    if score >= 40:
        return "Needs work.", "Below average"
    return "Gets scrolled past.", "Weak hook"


def _clamp(v) -> int:
    try:
        return max(0, min(100, int(v)))
    except (TypeError, ValueError):
        return 50


def format_check(spec: SlideshowSpec) -> tuple[int, list[str]]:
    texts = [" ".join(t.text for t in s.text_items).strip() for s in spec.slides]
    score, problems = 100, []
    n = len(spec.slides)
    if n < 4:
        score -= 20
        problems.append(f"Only {n} slides: 5-8 usually hold attention better.")
    if n > 10:
        score -= 15
        problems.append(f"{n} slides is long: viewers drop off; cut to the strongest 6-8.")
    hook = texts[0] if texts else ""
    hook_words = len(hook.split())
    if hook_words == 0:
        score -= 35
        problems.append("Slide 1 has no text: the hook has to be readable in a second.")
    elif hook_words > 14:
        score -= 15
        problems.append(f"The hook is {hook_words} words: keep it under 12 so it reads at a glance.")
    for i, t in enumerate(texts):
        if i > 0 and len(t.split()) > 30:
            score -= 8
            problems.append(f"Slide {i + 1} has {len(t.split())} words: split it or cut it down.")
    m = NUMBER_HOOK.search(hook)
    if m:
        body = sum(1 for s in spec.slides[1:] if s.role != "cta")
        if body and int(m.group(1)) != body:
            score -= 20
            problems.append(f"The hook promises {m.group(1)} but there are {body} list slides.")
    if len(texts) > 2 and len(set(texts[1:])) < len(texts) - 1:
        score -= 10
        problems.append("Two slides say the same thing.")
    return max(0, min(100, score)), problems


def spearman(xs: list[float], ys: list[float]) -> float | None:
    if len(xs) < 3 or len(xs) != len(ys):
        return None

    def ranks(v):
        order = sorted(range(len(v)), key=lambda i: v[i])
        r = [0.0] * len(v)
        i = 0
        while i < len(order):
            j = i
            while j + 1 < len(order) and v[order[j + 1]] == v[order[i]]:
                j += 1
            for k in range(i, j + 1):
                r[order[k]] = (i + j) / 2 + 1
            i = j + 1
        return r

    rx, ry = ranks(xs), ranks(ys)
    mx, my = sum(rx) / len(rx), sum(ry) / len(ry)
    num = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    dx = sum((a - mx) ** 2 for a in rx)
    dy = sum((b - my) ** 2 for b in ry)
    if not dx or not dy:
        return None
    return num / math.sqrt(dx * dy)


def calibration_examples(limit: int = 6, exclude: str = "") -> tuple[list[str], list[str]]:
    """Real library hooks that beat or missed their account's usual views (minus the `exclude` post)."""
    scored: list[tuple[float, str]] = []
    with db.session_scope() as session:
        for p in session.scalars(select(db.LibraryProfile)).all():
            posts = [x for x in p.posts if x.is_slideshow and x.views]
            if len(posts) < 3:
                continue
            median = statistics.median(x.views for x in posts) or 1
            for x in posts:
                if x.id == exclude:
                    continue
                hook = (x.slide_texts[0] if x.slide_texts and x.slide_texts[0] else re.sub(r"#\S+", "", x.description).strip())
                if hook:
                    scored.append((math.log(x.views / median), f'"{hook[:120]}" ({x.views / median:.1f}x the account\'s usual views)'))
    scored.sort(key=lambda t: -t[0])
    half = limit // 2
    return [s for v, s in scored[:half] if v > 0], [s for v, s in scored[-half:] if v < 0] if scored else []


def score(read: ViralityRead, spec: SlideshowSpec, vision_used: bool, calibrated: int) -> dict:
    n = len(spec.slides)
    per = [s.model_dump() for s in read.slides[:n]]
    while len(per) < n:
        per.append({"swipe_on": 60, "visual_pull": 50, "clarity": 50, "note": ""})
    hook = _clamp(read.hook_score)
    curve = [1.0]
    for i in range(n - 1):
        curve.append(curve[-1] * min(0.99, max(0.3, _clamp(per[i]["swipe_on"]) / 100)))
    hold = curve[-1]
    fmt, problems = format_check(spec)
    dims = {
        "visual_pull": int(statistics.mean(_clamp(s["visual_pull"]) for s in per)) if per else 50,
        "curiosity": _clamp(read.curiosity),
        "clarity": int((statistics.mean(_clamp(s["clarity"]) for s in per) if per else 50) * 0.7 + fmt * 0.3),
        "relatability": _clamp(read.relatability),
        "shareability": _clamp(read.shareability),
    }
    payoff = _clamp(read.payoff)
    engagement = statistics.mean([_clamp(read.curiosity), _clamp(read.relatability), _clamp(read.shareability), payoff])
    viral = max(0, min(100, int(0.40 * hook + 0.25 * hold * 100 + 0.20 * engagement + 0.15 * fmt)))
    v, tag = verdict(viral)
    # Only when one slide clearly loses more viewers than the rest.
    swipes = [_clamp(per[i]["swipe_on"]) for i in range(n - 1)]
    low = min(range(len(swipes)), key=lambda i: swipes[i]) if swipes else None
    drop = low + 1 if low is not None and swipes[low] <= statistics.mean(swipes) - 8 else None
    tips = list(dict.fromkeys(problems + list(read.tips)))[:6]
    basis = "AI read of the rendered slides" if vision_used else "AI read of the slide text (this AI key can't see images)"
    basis += " + format rules" + (f", calibrated with {calibrated} real library hooks" if calibrated else "")
    return {
        "viral_potential": viral,
        "hook_score": hook,
        "hold_rate": round(hold * 100),
        "payoff": payoff,
        "format_score": fmt,
        "verdict": v,
        "verdict_tag": tag,
        "attention": [round(c * 100) for c in curve],
        "slides": [
            {"swipe_on": _clamp(s["swipe_on"]), "visual_pull": _clamp(s["visual_pull"]), "clarity": _clamp(s["clarity"]),
             "note": s.get("note", ""), "attention": round(curve[i] * 100)}
            for i, s in enumerate(per)
        ],
        "biggest_drop": drop,
        "dimensions": dims,
        "tips": tips,
        "better_hook": read.better_hook,
        "basis": basis,
        "predicted_at": datetime.now(timezone.utc).isoformat(),
    }


def _read(spec: SlideshowSpec, caption: str, description: str, rendered: list[str], exclude: str = "") -> dict:
    """Ask the AI to read one slideshow (`rendered` = media-relative images) and score it."""
    cfg = llm.resolve()
    if cfg is None:
        raise PredictError("Add an AI key in Settings first.")
    good, bad = calibration_examples(exclude=exclude)
    lines = [f"Caption: {caption}", f"Description: {description}", "", "Slides (text as written on each image):"]
    lines += [f"{i + 1}. [{s.role}] " + " / ".join(t.text for t in s.text_items) for i, s in enumerate(spec.slides)]
    if good or bad:
        lines += ["", "Calibration from real slideshows in this creator's research library:"]
        lines += [f"- over-performed: {g}" for g in good] + [f"- under-performed: {b}" for b in bad]
    tail = f"\nThere are {len(spec.slides)} slides; return exactly {len(spec.slides)} entries in `slides`."
    if cfg.vision:
        tail += " The rendered slides are attached in order: judge the actual images and how readable the text is on them."
    images = (
        ["data:image/jpeg;base64," + base64.b64encode((settings.media_dir / r).read_bytes()).decode() for r in rendered[:10]]
        if cfg.vision else None
    )
    try:
        read = llm.complete_json(SYSTEM_PROMPT, "\n".join(lines) + tail, ViralityRead, images=images, cfg=cfg)
    except llm.LLMError as exc:
        raise PredictError(str(exc)) from exc
    return score(read, spec, cfg.vision, len(good) + len(bad))


# --- the library: real slideshows whose views are known --------------------------------------


def library_rows() -> list[dict]:
    """Library slideshows from accounts with 3+ of them, each with its log views vs the account's median."""
    rows: list[dict] = []
    with db.session_scope() as session:
        for p in session.scalars(select(db.LibraryProfile)).all():
            posts = [x for x in p.posts if x.is_slideshow and x.views]
            if len(posts) < 3:
                continue
            median = statistics.median(x.views for x in posts) or 1
            for x in posts:
                texts = [str(t) for t in (x.slide_texts or []) if t]
                hook = texts[0] if texts else re.sub(r"#\S+", "", x.description).strip()
                rows.append({
                    "id": x.id, "niche": p.niche, "username": p.username, "url": x.url, "hook": hook,
                    "texts": texts or ([hook] if hook else []), "images": list(x.images), "views": x.views,
                    "lift": math.log(x.views / median),
                })
    return rows


def _pct(value: float, population: list[float]) -> float:
    """Percentile (0-100) of `value` within `population`."""
    if not population:
        return 50.0
    below = sum(1 for v in population if v < value)
    equal = sum(1 for v in population if v == value)
    return 100 * (below + 0.5 * equal) / len(population)


def _spread(rows: list[dict], k: int) -> list[dict]:
    """k rows evenly spaced from the lowest lift to the highest."""
    rows = sorted(rows, key=lambda r: r["lift"])
    if len(rows) <= k:
        return rows
    return [rows[round(i * (len(rows) - 1) / (k - 1))] for i in range(k)]


# --- method B: posts like this (no AI call) ------------------------------------------------------

STOP = set("the a an and or of to in on for is are was it its this that my your you i me we with at be by as from "
           "how what why when not no do so if just all".split())


def _tokens(text: str) -> list[str]:
    return [w for w in re.findall(r"[^\W\d_]{2,}|\d+", text.lower()) if w not in STOP]


def similar_posts(text: str, rows: list[dict], k: int = 5) -> list[tuple[float, dict]]:
    """The k library posts whose slide text is closest to `text` (TF-IDF cosine), best first."""
    query = _tokens(text)
    docs = [_tokens(" ".join(r["texts"])) for r in rows]
    if not query or not rows:
        return []
    df: dict[str, int] = {}
    for d in docs:
        for w in set(d):
            df[w] = df.get(w, 0) + 1

    def vec(toks: list[str]) -> dict[str, float]:
        v: dict[str, float] = {}
        for w in toks:
            v[w] = v.get(w, 0.0) + math.log((len(docs) + 1) / (df.get(w, 0) + 1)) + 1
        norm = math.sqrt(sum(x * x for x in v.values())) or 1.0
        return {w: x / norm for w, x in v.items()}

    q = vec(query)
    scored = []
    for r, d in zip(rows, docs):
        if d:
            dv = vec(d)
            sim = sum(x * dv.get(w, 0.0) for w, x in q.items())
            if sim > 0.05:
                scored.append((sim, r))
    scored.sort(key=lambda t: -t[0])
    return scored[:k]


def similar_evidence(text: str, rows: list[dict]) -> dict | None:
    """What library posts like this one actually got (needs 3 matches)."""
    top = similar_posts(text, rows)
    if len(top) < 3:
        return None
    lift = sum(s * r["lift"] for s, r in top) / sum(s for s, _ in top)
    return {
        "score": round(_pct(lift, [r["lift"] for r in rows])),
        "lift": round(math.exp(lift), 2),
        "posts": [{"hook": r["hook"][:120], "url": r["url"], "username": r["username"], "lift": round(math.exp(r["lift"]), 2),
                   "similarity": round(sim, 2)} for sim, r in top],
    }


# --- method A: rank blind against real posts -------------------------------------------------------

RANK_PROMPT = """You compare TikTok photo-carousel (slideshow) posts. Rank every slideshow you are given from the one that will perform best to the one that will perform worst, where performing means beating the views its account usually gets: a hook that stops the scroll, slides that earn each swipe, a payoff, and something people relate to, save or share. Judge only what is in front of you; you are not told how any of them did."""
LETTERS = "ABCDEFG"


def rank_score(texts: list[str], cover: str | None, rows: list[dict], seed: str, exclude: str = "") -> dict | None:
    """Rank a slideshow blind among up to 6 real library posts spread from flops to hits; its place
    among them (whose real results are known) is the score."""
    refs = _spread([r for r in rows if r["id"] != exclude and r["texts"]], 6)
    if len(refs) < 4:
        return None
    cfg = llm.resolve()
    if cfg is None:
        raise PredictError("Add an AI key in Settings first.")
    pos = zlib.crc32(seed.encode()) % (len(refs) + 1)  # where the new one sits, so position bias can't favour it
    items: list[dict | None] = refs[:pos] + [None] + refs[pos:]
    lines, covers = [], []
    for letter, it in zip(LETTERS, items):
        slides = texts if it is None else it["texts"]
        lines.append(f"Slideshow {letter}: " + " / ".join(t[:160] for t in slides[:10]))
        covers.append(cover if it is None else (it["images"][0] if it["images"] else None))
    images = None
    if cfg.vision and all(covers):
        images = ["data:image/jpeg;base64," + base64.b64encode((settings.media_dir / c).read_bytes()).decode() for c in covers]
    user = "\n".join(lines) + f"\n\nRank all {len(items)} ({', '.join(LETTERS[:len(items)])}), best first."
    if images:
        user += " Their cover slides (slide 1) are attached in the same order."
    try:
        read = llm.complete_json(RANK_PROMPT, user, RankRead, images=images, cfg=cfg)
    except llm.LLMError as exc:
        raise PredictError(str(exc)) from exc
    order = [str(x).strip().upper()[:1] for x in read.order]
    me = LETTERS[pos]
    if me not in order:
        return None
    above = {x for x in order[: order.index(me)]}
    beats = sum(1 for i, _ in enumerate(refs) if LETTERS[i if i < pos else i + 1] not in above)
    lifts = sorted(r["lift"] for r in refs)
    at = beats / len(refs) * (len(lifts) - 1)
    lo = int(at)
    lift = lifts[lo] + (lifts[min(lo + 1, len(lifts) - 1)] - lifts[lo]) * (at - lo)
    return {"score": round(100 * (beats + 0.5) / (len(refs) + 1)), "lift": round(math.exp(lift), 2),
            "beats": beats, "references": len(refs), "reason": read.reason}


# --- combining methods ------------------------------------------------------------------------------

METHODS = {"current": "AI score", "rank": "Rank vs library", "similar": "Posts like this", "blend": "Blend of all three",
           "learned": "Learned from your library"}
FEATURES = ["hook", "hold", "engagement", "format", "rank", "similar"]
SWITCH_MARGIN = 0.05  # a method has to beat the AI score by this much ρ before the app switches to it
USEFUL = 0.15  # ...and track views at least weakly itself; beating a useless score by luck doesn't count
LEARN_MIN = 30


def features(result: dict, rank: dict | None, similar: dict | None) -> dict:
    d = result["dimensions"]
    return {
        "current": result["viral_potential"], "hook": result["hook_score"], "hold": result["hold_rate"],
        "engagement": round(statistics.mean([d["curiosity"], d["relatability"], d["shareability"], result["payoff"]])),
        "format": result["format_score"], "rank": rank["score"] if rank else None, "similar": similar["score"] if similar else None,
    }


def _vector(f: dict) -> list[float]:
    return [float(f[k]) if f.get(k) is not None else 50.0 for k in FEATURES]


def _solve(a: list[list[float]], b: list[float]) -> list[float]:
    n = len(b)
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for c in range(n):
        piv = max(range(c, n), key=lambda r: abs(m[r][c]))
        m[c], m[piv] = m[piv], m[c]
        if abs(m[c][c]) < 1e-12:
            continue
        for r in range(n):
            if r != c:
                f = m[r][c] / m[c][c]
                m[r] = [x - f * y for x, y in zip(m[r], m[c])]
    return [m[i][n] / m[i][i] if abs(m[i][i]) > 1e-12 else 0.0 for i in range(n)]


def fit(xs: list[list[float]], ys: list[float], lam: float = 1.0) -> dict:
    """Ridge regression of log lift on standardized features."""
    n, k = len(xs), len(xs[0])
    mu = [sum(r[j] for r in xs) / n for j in range(k)]
    sd = [math.sqrt(sum((r[j] - mu[j]) ** 2 for r in xs) / n) or 1.0 for j in range(k)]
    z = [[(r[j] - mu[j]) / sd[j] for j in range(k)] for r in xs]
    ym = sum(ys) / n
    a = [[sum(row[i] * row[j] for row in z) + (lam if i == j else 0.0) for j in range(k)] for i in range(k)]
    b = [sum(row[i] * (y - ym) for row, y in zip(z, ys)) for i in range(k)]
    return {"mu": mu, "sd": sd, "w": _solve(a, b), "b": ym}


def apply_fit(model: dict, x: list[float]) -> float:
    return model["b"] + sum(w * (v - m) / s for w, v, m, s in zip(model["w"], x, model["mu"], model["sd"]))


def method_value(method: str, f: dict, bt: dict) -> int | None:
    """A 0-100 score for one slideshow's features under `method`, using the library test's populations."""
    pop = bt.get("populations", {})
    if method == "current":
        return f["current"]
    if method in ("rank", "similar"):
        return f[method]
    if method == "blend":
        parts = [_pct(f[m], pop[m]) for m in ("current", "rank", "similar") if f.get(m) is not None and pop.get(m)]
        return round(statistics.mean(parts)) if parts else None
    if method == "learned" and bt.get("model"):
        return round(_pct(apply_fit(bt["model"], _vector(f)), pop.get("learned", [])))
    return None


def compare_methods(rows: list[dict]) -> tuple[list[dict], str, dict | None, dict]:
    """ρ of every method on the library rows, the winner, the learned model (if it won) and populations."""
    ys = [r["lift_log"] for r in rows]
    pops = {m: [r["f"][m] for r in rows if r["f"].get(m) is not None] for m in ("current", "rank", "similar")}
    bt = {"populations": pops}
    results: list[dict] = []

    def add(method: str, scores: list[float | None]) -> None:
        pairs = [(sc, y) for sc, y in zip(scores, ys) if sc is not None]
        rho = spearman([a for a, _ in pairs], [b for _, b in pairs]) if len(pairs) >= BACKTEST_MIN else None
        results.append({"method": method, "label": METHODS[method], "posts": len(pairs),
                        "spearman": round(rho, 2) if rho is not None else None})

    for m in ("current", "rank", "similar", "blend"):
        add(m, [method_value(m, r["f"], bt) for r in rows])
    model = None
    if len(rows) >= LEARN_MIN:
        xs = [_vector(r["f"]) for r in rows]
        # Leave-one-out: each post is predicted by weights fitted without it.
        loo = [apply_fit(fit(xs[:i] + xs[i + 1:], ys[:i] + ys[i + 1:]), xs[i]) for i in range(len(rows))]
        add("learned", loo)
        model = fit(xs, ys)
        pops["learned"] = [apply_fit(model, x) for x in xs]
    rho = {r["method"]: r["spearman"] for r in results}
    singles = [m for m in ("current", "rank", "similar", "blend") if rho[m] is not None]
    best = max(singles, key=lambda m: rho[m]) if singles else "current"
    if rho.get("learned") is not None and rho["learned"] >= (rho.get(best) or -1) + SWITCH_MARGIN:
        best = "learned"
    base = rho.get("current")
    good = rho.get(best) is not None and rho[best] >= USEFUL
    winner = best if good and (base is None or rho[best] >= base + SWITCH_MARGIN) else "current"
    if winner != "learned":
        model = None
        pops.pop("learned", None)
    return results, winner, model, pops


# --- predicting one slideshow ---------------------------------------------------------------------


def _slide_texts(spec: SlideshowSpec) -> list[str]:
    return [" ".join(t.text for t in s.text_items).strip() for s in spec.slides]


def predict(slideshow_id: str) -> dict:
    with db.session_scope() as session:
        show = session.get(db.Slideshow, slideshow_id)
        if show is None:
            raise PredictError("slideshow not found")
        if show.status != "completed" or not show.rendered:
            raise PredictError("Render the slideshow first.")
        spec = SlideshowSpec.model_validate(show.spec)
        caption, description, rendered = show.caption, show.description, list(show.rendered)
    result = _read(spec, caption, description, rendered)
    rows = library_rows()
    texts = [t for t in _slide_texts(spec) if t]
    similar = similar_evidence(" ".join(texts), rows)
    if similar:
        result["similar"] = similar
    bt = last_backtest() or {}
    method = bt.get("method", "current")
    rank = None
    if method in ("rank", "blend", "learned"):
        try:
            rank = rank_score(texts, rendered[0], rows, slideshow_id)
        except PredictError:
            rank = None  # fall back to the AI score below rather than failing the prediction
        if rank:
            result["rank"] = rank
    value = method_value(method, features(result, rank, similar), bt) if method != "current" else None
    result["ai_score"] = result["viral_potential"]
    if value is not None:
        result["viral_potential"] = value
        result["verdict"], result["verdict_tag"] = verdict(value)
        rho = next((m["spearman"] for m in bt.get("methods", []) if m["method"] == method), None)
        result["basis"] += f"; final score from {METHODS[method].lower()} (the library test's pick, ρ = {rho})"
        result["method"] = method
    else:
        result["method"] = "current"
    with db.session_scope() as session:
        session.get(db.Slideshow, slideshow_id).prediction = result
    return result


def _summary(rho: float | None, what: str) -> str:
    if rho is None:
        return "Not enough variation in views yet."
    if rho >= 0.4:
        return f"The score tracks {what} well (ρ = {rho:.2f}): trust it to pick between drafts."
    if rho >= 0.15:
        return f"The score weakly tracks {what} (ρ = {rho:.2f}): use it as a tiebreaker."
    return f"The score isn't tracking {what} (ρ = {rho:.2f}): don't rely on it yet."


def calibration() -> dict:
    pairs: list[tuple[float, float]] = []
    with db.session_scope() as session:
        for s in session.scalars(select(db.Slideshow).where(db.Slideshow.prediction.is_not(None))).all():
            if not s.prediction:
                continue
            views = max((p.views for p in s.posts if p.status == "published"), default=0)
            if views > 0:
                pairs.append((float(s.prediction["viral_potential"]), math.log(views)))
    rho = spearman([a for a, _ in pairs], [b for _, b in pairs])
    if len(pairs) < 8:
        summary = f"Post at least 8 predicted slideshows to see whether the score tracks your views ({len(pairs)} so far)."
    else:
        summary = _summary(rho, "your views")
    return {
        "posts": len(pairs), "spearman": round(rho, 2) if rho is not None else None, "summary": summary,
        "backtest": last_backtest(),
    }


# --- backtest: race the methods on real library posts whose views are already known -------------

BACKTEST_KEY = "virality_backtest"
BACKTEST_MIN = 8
NOISE_POSTS = 3


def _library_spec(post: db.LibraryPost) -> SlideshowSpec:
    texts = list(post.slide_texts or [])
    slides = []
    for i, path in enumerate(post.images[:10]):
        text = str(texts[i]) if i < len(texts) and texts[i] else ""
        slides.append({"role": "hook" if i == 0 else "body", "image": path, "text_items": [{"text": text}] if text else []})
    return SlideshowSpec.model_validate({"title": post.description[:80] or "Library post", "slides": slides})


def backtest(limit: int = 20, progress=lambda done, total: None) -> dict:
    """Score real library slideshows blind with every method (each post is left out of its own
    calibration examples, references and look-alikes), measure how well each ranks them like their
    real views did, and pick the method the app should use."""
    from . import library  # library imports llm; keep this module's imports light

    sample = _spread([r for r in library_rows() if r["images"]], limit)
    if len(sample) < BACKTEST_MIN:
        raise PredictError(
            f"The library has {len(sample)} usable slideshows; it needs {BACKTEST_MIN}. "
            "Add accounts in your niche (3+ slideshow posts each) to the Slideshow library first."
        )
    cfg = llm.resolve()
    if cfg is None:
        raise PredictError("Add an AI key in Settings first.")
    rows: list[dict] = []
    for n, row in enumerate(sample):
        progress(n, len(sample))
        with db.session_scope() as session:
            analyzed = bool(session.get(db.LibraryPost, row["id"]).slide_texts)
        if not analyzed:
            if not cfg.vision:
                continue  # neither the slide text nor a way to read it
            try:
                library.analyze_post(row["id"])
            except library.LibraryError:
                continue
        with db.session_scope() as session:
            post = session.get(db.LibraryPost, row["id"])
            spec, caption = _library_spec(post), post.description
        texts = [t for t in _slide_texts(spec) if t]
        others = [r for r in library_rows() if r["id"] != row["id"]]
        try:
            result = _read(spec, caption, "", row["images"], exclude=row["id"])
            rank = rank_score(texts, row["images"][0], others, row["id"])
        except PredictError:
            continue
        similar = similar_evidence(" ".join(texts), others)
        rows.append({
            "post_id": row["id"], "url": row["url"], "username": row["username"],
            "hook": texts[0] if texts else caption[:80], "views": row["views"], "lift": round(math.exp(row["lift"]), 2),
            "lift_log": row["lift"], "score": result["viral_potential"], "f": features(result, rank, similar),
        })
    # Noise: the same slideshow read twice should get about the same score.
    drifts = []
    for r in rows[:NOISE_POSTS]:
        with db.session_scope() as session:
            post = session.get(db.LibraryPost, r["post_id"])
            spec, caption, images = _library_spec(post), post.description, list(post.images)
        try:
            drifts.append(abs(_read(spec, caption, "", images, exclude=r["post_id"])["viral_potential"] - r["score"]))
        except PredictError:
            pass
    progress(len(sample), len(sample))
    if len(rows) < BACKTEST_MIN:
        methods, winner, model, pops = [], "current", None, {}
        rho, summary = None, f"Only {len(rows)} library slideshows could be scored; the test needs {BACKTEST_MIN}."
    else:
        methods, winner, model, pops = compare_methods(rows)
        rho = next(m["spearman"] for m in methods if m["method"] == "current")
        summary = _summary(rho, "real views in your library")
        if winner != "current":
            won = next(m for m in methods if m["method"] == winner)
            summary += f" {won['label']} did better (ρ = {won['spearman']}), so the app now scores with it."
        elif (rho is None or rho < USEFUL) and all((m["spearman"] or 0) < USEFUL for m in methods):
            summary += " No other method tracked views either, so treat every score as a guess for now."
    out = {
        "posts": len(rows), "spearman": rho, "summary": summary, "method": winner, "methods": methods,
        "model": model, "populations": pops,
        "noise": round(statistics.mean(drifts), 1) if drifts else None,
        "rows": sorted(
            [{**{k: v for k, v in r.items() if k not in ("f", "lift_log")}, "rank": r["f"]["rank"], "similar": r["f"]["similar"]}
             for r in rows],
            key=lambda r: -r["score"],
        ),
        "ran_at": datetime.now(timezone.utc).isoformat(),
    }
    with db.session_scope() as session:
        row = session.get(db.Setting, BACKTEST_KEY)
        if row is None:
            session.add(db.Setting(key=BACKTEST_KEY, value=json.dumps(out)))
        else:
            row.value = json.dumps(out)
    return out


def last_backtest() -> dict | None:
    with db.session_scope() as session:
        row = session.get(db.Setting, BACKTEST_KEY)
        raw = row.value if row is not None else ""
    try:
        return json.loads(raw) if raw else None
    except ValueError:
        return None


def backtest_job(job_id: str, limit: int) -> None:
    from .pipeline import _job

    _job(job_id, status="running", message="Scoring library slideshows…")
    try:
        out = backtest(limit, lambda done, total: _job(job_id, message=f"Scored {done} of {total} library slideshows…"))
    except PredictError as exc:
        _job(job_id, status="failed", message=str(exc))
        return
    except Exception as exc:  # noqa: BLE001 - surface anything to the dashboard instead of a stuck job
        _job(job_id, status="failed", message=f"Backtest failed: {exc}")
        return
    _job(job_id, status="completed", message=out["summary"], result={"backtest": out})
