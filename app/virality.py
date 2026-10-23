"""Virality prediction for a slideshow before it's posted (same model as android/core Virality.kt).

Higgsfield's Virality Predictor runs a brain-response model over a video and turns predicted cortex
activity into a hook score, a hold rate and a viral-potential number. A slideshow has no motion or
sound, so here a vision model reads the rendered slides like a scrolling viewer (calibrated with real
hooks from the library that over- and under-performed), and plain rules check the format. Every
prediction is stored, so analytics can later show whether it actually tracks your views.
"""

from __future__ import annotations

import base64
import math
import re
import statistics
from datetime import datetime, timezone

from sqlalchemy import select

from . import db, llm
from .config import settings
from .schemas import SlideshowSpec, ViralityRead

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


def calibration_examples(limit: int = 6) -> tuple[list[str], list[str]]:
    """Real library hooks that beat or missed their account's usual views."""
    scored: list[tuple[float, str]] = []
    with db.session_scope() as session:
        for p in session.scalars(select(db.LibraryProfile)).all():
            posts = [x for x in p.posts if x.is_slideshow and x.views]
            if len(posts) < 3:
                continue
            median = statistics.median(x.views for x in posts) or 1
            for x in posts:
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


def predict(slideshow_id: str) -> dict:
    with db.session_scope() as session:
        show = session.get(db.Slideshow, slideshow_id)
        if show is None:
            raise PredictError("slideshow not found")
        if show.status != "completed" or not show.rendered:
            raise PredictError("Render the slideshow first.")
        spec = SlideshowSpec.model_validate(show.spec)
        caption, description, rendered = show.caption, show.description, list(show.rendered)
    cfg = llm.resolve()
    if cfg is None:
        raise PredictError("Add an AI key in Settings first.")
    good, bad = calibration_examples()
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
    result = score(read, spec, cfg.vision, len(good) + len(bad))
    with db.session_scope() as session:
        session.get(db.Slideshow, slideshow_id).prediction = result
    return result


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
    elif rho is None:
        summary = "Not enough variation in views yet."
    elif rho >= 0.4:
        summary = f"The score tracks your views well (ρ = {rho:.2f}): trust it to pick between drafts."
    elif rho >= 0.15:
        summary = f"The score weakly tracks your views (ρ = {rho:.2f}): use it as a tiebreaker."
    else:
        summary = f"The score isn't tracking your views (ρ = {rho:.2f}): don't rely on it for this account yet."
    return {"posts": len(pairs), "spearman": round(rho, 2) if rho is not None else None, "summary": summary}
