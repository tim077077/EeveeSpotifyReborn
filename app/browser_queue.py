"""Queue for the Slidemill browser extension, which posts from your logged-in tiktok.com tab.

The extension asks for work about once a minute (`claim_next`). Each claim hands out at most one
due post, respecting a daily cap and a minimum gap between posts. Claims that never report back
are released after CLAIM_TIMEOUT, so a closed tab or sleeping laptop doesn't lose posts.

This path uses TikTok's internal web endpoints instead of the official API (no developer app).
That is against TikTok's Terms of Service; the caps and gaps keep the account's posting pattern
human-paced.
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from sqlalchemy import select

from . import db, store
from .config import settings

log = logging.getLogger("slidemill.browser")

CLAIM_TIMEOUT = timedelta(minutes=20)
MAX_ATTEMPTS = 3
DEFAULT_POSTS_PER_DAY = 6
DEFAULT_MIN_GAP_MINUTES = 20

# Last check-in from the extension (in memory; shown in Settings).
last_seen: dict[str, str] = {}


def _int_setting(key: str, default: int) -> int:
    try:
        return max(0, int(store.get(key) or default))
    except ValueError:
        return default


def limits() -> tuple[int, int]:
    return (
        _int_setting("browser_posts_per_day", DEFAULT_POSTS_PER_DAY),
        _int_setting("browser_min_gap_minutes", DEFAULT_MIN_GAP_MINUTES),
    )


def _aware(dt: datetime | None) -> datetime | None:
    if dt is None:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def queue_post(session, slideshow: db.Slideshow, action: str = "publish", sound: bool = True) -> db.Post:
    post = db.Post(
        slideshow_id=slideshow.id,
        tiktok_account_id=None,
        post_mode="BROWSER",
        status="queued",
        options={"action": action, "sound": sound},
    )
    session.add(post)
    session.flush()
    return post


def _release_stale(session, now: datetime) -> None:
    for post in session.scalars(select(db.Post).where(db.Post.post_mode == "BROWSER", db.Post.status == "posting")).all():
        claimed = _aware(post.claimed_at)
        if claimed and now - claimed > CLAIM_TIMEOUT:
            if post.attempts >= MAX_ATTEMPTS:
                post.status, post.error = "failed", "The extension never reported back (tab closed or computer asleep)."
            else:
                post.status = "queued"
            post.claimed_at = None


def _done_in_last_24h(session, now: datetime) -> list[db.Post]:
    rows = session.scalars(
        select(db.Post).where(db.Post.post_mode == "BROWSER", db.Post.status.in_(["published", "sent_to_drafts", "posting"]))
    ).all()
    return [p for p in rows if (_aware(p.claimed_at) or _aware(p.created_at)) >= now - timedelta(hours=24)]


def status(now: datetime | None = None) -> dict:
    now = now or datetime.now(timezone.utc)
    per_day, gap = limits()
    with db.session_scope() as session:
        recent = _done_in_last_24h(session, now)
        queued = session.scalars(
            select(db.Post).where(db.Post.post_mode == "BROWSER", db.Post.status.in_(["queued", "posting"]))
        ).all()
        waiting_for_drafts = sum(1 for p in queued if (p.options or {}).get("action") == "draft")
        last = max((_aware(p.claimed_at) for p in recent if p.claimed_at), default=None)
    return {
        "queued": len(queued),
        "waiting_for_draft_support": waiting_for_drafts if not last_seen.get("draft") == "1" else 0,
        "done_last_24h": sum(1 for p in recent if p.status != "posting"),
        "posts_per_day": per_day,
        "min_gap_minutes": gap,
        "last_posted_at": last.isoformat() if last else None,
        "extension": dict(last_seen),
    }


def claim_next(now: datetime | None = None, client: dict | None = None) -> dict:
    """Hand the extension its next post, or explain why there's nothing to do yet.

    `client` is what the extension reports: version, the logged-in username, and whether it can
    save drafts yet ("draft": "1"). Draft posts stay queued until an extension that can do them asks.
    """
    now = now or datetime.now(timezone.utc)
    client = {k: str(v)[:128] for k, v in (client or {}).items() if v not in (None, "")}
    last_seen.clear()
    last_seen.update({"at": now.isoformat(), **client})
    can_draft = client.get("draft") == "1"
    per_day, gap = limits()

    with db.session_scope() as session:
        _release_stale(session, now)
        session.flush()
        recent = _done_in_last_24h(session, now)
        if any(p.status == "posting" for p in recent):
            return {"job": None, "reason": "a post is already in progress"}
        if per_day and sum(1 for p in recent if p.status != "posting") >= per_day:
            return {"job": None, "reason": f"daily limit reached ({per_day} in 24h)"}
        last = max((_aware(p.claimed_at) for p in recent if p.claimed_at), default=None)
        if last and gap and now - last < timedelta(minutes=gap):
            wait = timedelta(minutes=gap) - (now - last)
            return {"job": None, "reason": f"waiting {int(wait.total_seconds() // 60) + 1} min between posts"}

        candidates = session.scalars(
            select(db.Post).where(db.Post.post_mode == "BROWSER", db.Post.status == "queued").order_by(db.Post.created_at)
        ).all()
        post = next((p for p in candidates if can_draft or (p.options or {}).get("action", "publish") != "draft"), None)
        if post is None:
            if candidates:
                return {"job": None, "reason": f"{len(candidates)} draft(s) waiting: this extension can't save drafts yet"}
            return {"job": None, "reason": "queue empty"}

        show = session.get(db.Slideshow, post.slideshow_id)
        if show is None or not show.rendered:
            post.status, post.error = "failed", "slideshow has no rendered slides"
            return {"job": None, "reason": "skipped a post without slides"}
        post.status, post.claimed_at, post.attempts = "posting", now, post.attempts + 1
        options = post.options or {}
        return {
            "job": {
                "id": post.id,
                "slideshow_id": show.id,
                "action": options.get("action", "publish"),
                # TikTok's photo-post "title" is the line shown on the carousel; the caption carries hashtags.
                "title": (show.caption or show.title)[:90],
                "caption": show.description or "",
                "images": [f"{settings.public_base_url}/media/{rel}" for rel in show.rendered],
                "sound": bool(options.get("sound", True)),
            },
            "reason": "",
        }


def record_result(
    post_id: str, ok: bool, item_id: str = "", username: str = "", error: str = "", unsupported: bool = False
) -> db.Post | None:
    with db.session_scope() as session:
        post = session.get(db.Post, post_id)
        if post is None or post.post_mode != "BROWSER" or post.status != "posting":
            return None
        if ok:
            post.status = "sent_to_drafts" if (post.options or {}).get("action") == "draft" else "published"
            post.post_id, post.error = item_id, ""
            post.tiktok_username = username or store.get("browser_username")
        elif unsupported:
            # Not a failure of this post: hand the attempt back and wait for a capable extension.
            post.status, post.attempts, post.claimed_at = "queued", max(0, post.attempts - 1), None
            post.error = error[:2000]
        else:
            post.error = error[:2000]
            post.status = "failed" if post.attempts >= MAX_ATTEMPTS else "queued"
        if username:
            last_seen["username"] = username
        return post


def refresh_metrics() -> None:
    """Views and likes for published browser posts, read from each post's public page."""
    from . import library  # local import: avoids a cycle through pipeline

    with db.session_scope() as session:
        posts = session.scalars(
            select(db.Post).where(db.Post.post_mode == "BROWSER", db.Post.status == "published", db.Post.post_id != "")
        ).all()
        targets = [(p.id, p.post_id, p.tiktok_username or store.get("browser_username")) for p in posts]
    now = datetime.now(timezone.utc)
    for pid, item_id, username in targets:
        if not username:
            continue
        try:
            data = library.fetch_post(f"https://www.tiktok.com/@{username}/photo/{item_id}")
        except Exception as exc:  # noqa: BLE001 - one failed fetch shouldn't stop the rest
            log.info("metrics fetch failed for %s: %s", item_id, exc)
            continue
        with db.session_scope() as session:
            post = session.get(db.Post, pid)
            post.views, post.likes, post.comments, post.shares = data["views"], data["likes"], data["comments"], data["shares"]
            post.metrics_updated_at = now

