"""Slideshow library: scrape public TikTok accounts and slideshow posts into a searchable database.

Three collection paths, from most to least reliable:
  1. Apify (APIFY_TOKEN) - managed TikTok scraper: profiles, hashtags and keyword search.
  2. Direct HTTP - single post URLs and profile metadata, parsed from the page's embedded JSON.
  3. Headless Chromium - lists a profile's posts by reading the page's own API calls. TikTok
     often serves datacenter IPs empty responses; it works best from a home connection.

Scraping TikTok is against its Terms of Service. Only public data is collected, but the risk
(IP blocks, account action) sits with whoever runs it.
"""

from __future__ import annotations

import base64
import glob
import json
import logging
import random
import re
import time
from datetime import datetime, timezone
from typing import Any

import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import db, images, llm, store
from .config import settings
from .schemas import ProfileClassification, SlideTextRead

log = logging.getLogger("slidemill.library")

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
REHYDRATION_RE = re.compile(r'<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>', re.S)
POST_URL_RE = re.compile(r"tiktok\.com/@([\w.\-]+)/(?:video|photo)/(\d+)")
PROFILE_URL_RE = re.compile(r"tiktok\.com/@([\w.\-]+)/?(?:\?|$)")
SHORT_URL_RE = re.compile(r"https?://(?:vm|vt)\.tiktok\.com/\w+")
APIFY_ACTOR = "clockworks~tiktok-scraper"


class LibraryError(RuntimeError):
    pass


# --- input parsing ----------------------------------------------------------------------


def parse_item(raw: str) -> tuple[str, str]:
    """('post', url) | ('profile', username)."""
    raw = raw.strip()
    if SHORT_URL_RE.match(raw):
        raw = str(httpx.head(raw, follow_redirects=True, headers={"User-Agent": UA}, timeout=20).url)
    if m := POST_URL_RE.search(raw):
        return "post", f"https://www.tiktok.com/@{m.group(1)}/video/{m.group(2)}"
    if m := PROFILE_URL_RE.search(raw):
        return "profile", m.group(1)
    name = raw.lstrip("@")
    if re.fullmatch(r"[\w.\-]{2,64}", name):
        return "profile", name
    raise LibraryError(f"Not a TikTok username or URL: {raw!r}")


# --- direct HTTP ------------------------------------------------------------------------------


def _page_scope(url: str) -> dict[str, Any]:
    resp = httpx.get(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"}, timeout=30, follow_redirects=True)
    if resp.status_code != 200:
        raise LibraryError(f"TikTok returned {resp.status_code} for {url}")
    m = REHYDRATION_RE.search(resp.text)
    if not m:
        raise LibraryError("TikTok page had no embedded data (blocked or changed layout).")
    return json.loads(m.group(1)).get("__DEFAULT_SCOPE__", {})


def fetch_profile(username: str) -> dict[str, Any]:
    detail = _page_scope(f"https://www.tiktok.com/@{username}").get("webapp.user-detail", {})
    info = detail.get("userInfo")
    if not info or not info.get("user"):
        raise LibraryError(f"@{username} not found or private (status {detail.get('statusCode')}).")
    user, stats = info["user"], info.get("stats", {})
    return {
        "tiktok_user_id": str(user.get("id", "")),
        "username": user.get("uniqueId", username),
        "nickname": user.get("nickname", ""),
        "avatar": user.get("avatarLarger") or user.get("avatarMedium", ""),
        "bio": user.get("signature", ""),
        "bio_link": (user.get("bioLink") or {}).get("link", ""),
        "verified": bool(user.get("verified")),
        "region": user.get("region", ""),
        "followers": stats.get("followerCount", 0),
        "following": stats.get("followingCount", 0),
        "total_likes": stats.get("heartCount") or stats.get("heart", 0),
        "video_count": stats.get("videoCount", 0),
    }


def fetch_post(url: str) -> dict[str, Any]:
    detail = _page_scope(url).get("webapp.video-detail", {})
    item = detail.get("itemInfo", {}).get("itemStruct")
    if not item:
        raise LibraryError(f"Post unavailable (status {detail.get('statusCode')}).")
    return normalize_web_item(item)


def _first(urls: Any) -> list[str]:
    return [u for u in (urls or []) if isinstance(u, str) and u.startswith("http")]


def normalize_web_item(item: dict[str, Any]) -> dict[str, Any]:
    """TikTok web itemStruct -> library post dict."""
    stats = item.get("statsV2") or item.get("stats") or {}
    image_post = item.get("imagePost") or {}
    slides = [_first((img.get("imageURL") or {}).get("urlList")) for img in image_post.get("images", [])]
    author = item.get("author") or {}
    music = item.get("music") or {}
    video = item.get("video") or {}
    return {
        "id": str(item["id"]),
        "username": author.get("uniqueId", ""),
        "author": {
            "nickname": author.get("nickname", ""),
            "avatar": author.get("avatarLarger", ""),
            "bio": author.get("signature", ""),
            "verified": bool(author.get("verified")),
            **{k: v for k, v in {
                "followers": (item.get("authorStats") or {}).get("followerCount"),
                "total_likes": (item.get("authorStats") or {}).get("heartCount"),
                "video_count": (item.get("authorStats") or {}).get("videoCount"),
            }.items() if v is not None},
        },
        "description": item.get("desc", ""),
        "is_slideshow": bool(slides),
        "slide_urls": slides,
        "cover_urls": _first([video.get("cover"), video.get("originCover")]) or (slides[0] if slides else []),
        "music": " - ".join(x for x in (music.get("title"), music.get("authorName")) if x),
        "views": int(stats.get("playCount", 0) or 0),
        "likes": int(stats.get("diggCount", 0) or 0),
        "comments": int(stats.get("commentCount", 0) or 0),
        "shares": int(stats.get("shareCount", 0) or 0),
        "saves": int(stats.get("collectCount", 0) or 0),
        "posted_at": int(item.get("createTime", 0) or 0),
    }


# --- headless browser -----------------------------------------------------------------------


def browser_profile_posts(username: str, max_posts: int) -> list[dict[str, Any]]:
    """List a profile's posts by scrolling the page and capturing its item_list API responses."""
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return []
    exe = (glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome") or [None])[0]
    items: dict[str, dict] = {}

    def on_response(resp):
        if "/api/post/item_list" in resp.url:
            try:
                for it in resp.json().get("itemList", []) or []:
                    items[str(it["id"])] = it
            except Exception:  # noqa: BLE001 - empty/blocked responses are expected
                pass

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(
                executable_path=exe, headless=True, args=["--no-sandbox", "--disable-blink-features=AutomationControlled"]
            )
            page = browser.new_page(user_agent=UA, locale="en-US", viewport={"width": 1280, "height": 900})
            page.on("response", on_response)
            page.goto(f"https://www.tiktok.com/@{username}", wait_until="domcontentloaded", timeout=60000)
            stale = 0
            while len(items) < max_posts and stale < 4:
                before = len(items)
                page.wait_for_timeout(2000 + random.randint(0, 1500))
                page.mouse.wheel(0, 5000)
                stale = stale + 1 if len(items) == before else 0
            browser.close()
    except Exception as exc:  # noqa: BLE001
        log.warning("browser scrape of @%s failed: %s", username, exc)
    return [normalize_web_item(it) for it in list(items.values())[:max_posts]]


# --- Apify -----------------------------------------------------------------------------------


def apify_available() -> bool:
    return bool(store.get("apify_token"))


def apify_scrape(*, profiles: list[str] | None = None, hashtags: list[str] | None = None,
                 queries: list[str] | None = None, max_posts: int = 30) -> list[dict[str, Any]]:
    token = store.get("apify_token")
    if not token:
        raise LibraryError("Discovery by search/hashtag needs an Apify token (Settings -> APIFY_TOKEN).")
    body: dict[str, Any] = {"resultsPerPage": max_posts, "shouldDownloadVideos": False, "shouldDownloadCovers": False}
    if profiles:
        body["profiles"] = profiles
    if hashtags:
        body["hashtags"] = hashtags
    if queries:
        body["searchQueries"] = queries
    resp = httpx.post(
        f"https://api.apify.com/v2/acts/{APIFY_ACTOR}/run-sync-get-dataset-items",
        params={"token": token},
        json=body,
        timeout=330,
    )
    if resp.status_code >= 400:
        raise LibraryError(f"Apify error {resp.status_code}: {resp.text[:300]}")
    return [normalize_apify_item(it) for it in resp.json() if it.get("id")]


def normalize_apify_item(it: dict[str, Any]) -> dict[str, Any]:
    author = it.get("authorMeta") or {}
    slides = []
    for s in it.get("slideshowImageLinks") or []:
        slides.append(_first([s.get("downloadLink"), s.get("tiktokLink")]))
    music = it.get("musicMeta") or {}
    posted = it.get("createTime") or 0
    if not posted and it.get("createTimeISO"):
        posted = int(datetime.fromisoformat(it["createTimeISO"].replace("Z", "+00:00")).timestamp())
    return {
        "id": str(it["id"]),
        "username": author.get("name", ""),
        "author": {
            "nickname": author.get("nickName", ""),
            "avatar": author.get("avatar", ""),
            "bio": author.get("signature", ""),
            "bio_link": author.get("bioLink") or "",
            "verified": bool(author.get("verified")),
            "region": author.get("region") or "",
            "followers": author.get("fans", 0),
            "following": author.get("following", 0),
            "total_likes": author.get("heart", 0),
            "video_count": author.get("video", 0),
        },
        "description": it.get("text", ""),
        "is_slideshow": bool(it.get("isSlideshow") or slides),
        "slide_urls": slides,
        "cover_urls": _first([(it.get("videoMeta") or {}).get("coverUrl")]),
        "music": " - ".join(x for x in (music.get("musicName"), music.get("musicAuthor")) if x),
        "views": it.get("playCount", 0) or 0,
        "likes": it.get("diggCount", 0) or 0,
        "comments": it.get("commentCount", 0) or 0,
        "shares": it.get("shareCount", 0) or 0,
        "saves": it.get("collectCount", 0) or 0,
        "posted_at": int(posted or 0),
    }


# --- persistence ------------------------------------------------------------------------------


def _download(candidates: list[str], folder: str) -> str:
    """Store the first candidate URL that decodes as an image; '' if none do."""
    for url in candidates:
        try:
            resp = httpx.get(url, headers={"User-Agent": UA, "Referer": "https://www.tiktok.com/"}, timeout=30, follow_redirects=True)
            if resp.status_code == 200:
                return images.store_bytes(resp.content, folder)[0]
        except Exception:  # noqa: BLE001 - try the next mirror
            continue
    return ""


def upsert_profile(session: Session, data: dict[str, Any]) -> db.LibraryProfile:
    username = data["username"]
    profile = session.scalars(select(db.LibraryProfile).where(db.LibraryProfile.username == username)).first()
    if profile is None:
        profile = db.LibraryProfile(username=username)
        session.add(profile)
    for field in ("tiktok_user_id", "nickname", "bio", "bio_link", "region", "followers", "following", "total_likes", "video_count"):
        if data.get(field) not in (None, ""):
            setattr(profile, field, data[field])
    if "verified" in data:
        profile.verified = bool(data["verified"])
    if data.get("avatar") and not profile.avatar_path:
        profile.avatar_path = _download([data["avatar"]], f"tiktok/{username}")
    profile.scraped_at = datetime.now(timezone.utc)
    session.flush()
    return profile


def upsert_post(session: Session, profile: db.LibraryProfile, post: dict[str, Any]) -> db.LibraryPost:
    row = session.get(db.LibraryPost, post["id"])
    is_new = row is None
    if is_new:
        row = db.LibraryPost(id=post["id"], profile_id=profile.id)
        session.add(row)
    row.url = f"https://www.tiktok.com/@{profile.username}/{'photo' if post['is_slideshow'] else 'video'}/{post['id']}"
    row.description = post["description"]
    row.is_slideshow = post["is_slideshow"]
    row.music = post["music"]
    for field in ("views", "likes", "comments", "shares", "saves"):
        setattr(row, field, int(post.get(field) or 0))
    if post.get("posted_at"):
        row.posted_at = datetime.fromtimestamp(post["posted_at"], timezone.utc)
    row.scraped_at = datetime.now(timezone.utc)
    folder = f"tiktok/{profile.username}"
    if post["is_slideshow"] and not row.images:
        row.images = [p for p in (_download(c, folder) for c in post["slide_urls"]) if p]
    if not row.cover_path:
        row.cover_path = row.images[0] if row.images else _download(post.get("cover_urls") or [], folder)
    session.flush()
    return row


def save_posts(posts: list[dict[str, Any]], slideshows_only: bool = False) -> list[str]:
    """Persist scraped posts (and their authors). Returns profile ids touched."""
    touched: set[str] = set()
    for post in posts:
        if slideshows_only and not post["is_slideshow"]:
            continue
        if not post.get("username"):
            continue
        with db.session_scope() as session:
            profile = upsert_profile(session, {"username": post["username"], **post.get("author", {})})
            upsert_post(session, profile, post)
            touched.add(profile.id)
    return sorted(touched)


# --- AI enrichment ----------------------------------------------------------------------------


CLASSIFY_PROMPT = "You categorize TikTok accounts for a creator-inspiration database."


def classify_profile(profile_id: str) -> None:
    if not llm.configured():
        return
    with db.session_scope() as session:
        p = session.get(db.LibraryProfile, profile_id)
        if p is None:
            return
        captions = [x.description for x in sorted(p.posts, key=lambda x: -x.views)[:12] if x.description]
        text = f"@{p.username} ({p.nickname})\nbio: {p.bio}\nlink: {p.bio_link}\ntop captions:\n" + "\n".join(f"- {c}" for c in captions)
    try:
        result = llm.complete_json(CLASSIFY_PROMPT, text, ProfileClassification)
    except llm.LLMError as exc:
        log.info("classification skipped for %s: %s", profile_id, exc)
        return
    with db.session_scope() as session:
        p = session.get(db.LibraryProfile, profile_id)
        p.niche = result.niche.strip().lower()[:64]
        p.product_medium = result.product_medium


READ_PROMPT = (
    "These images are the slides of one TikTok photo carousel, in order. Transcribe the overlay text on each slide "
    "exactly as written (keep line breaks as spaces). Use an empty string for slides without overlay text."
)


def analyze_post(post_id: str) -> list[str]:
    """Read slide text off a library slideshow with a vision model."""
    with db.session_scope() as session:
        post = session.get(db.LibraryPost, post_id)
        if post is None:
            raise LibraryError("post not found")
        paths = list(post.images)
    if not paths:
        raise LibraryError("This post has no slide images to read.")
    cfg = llm.resolve()
    if cfg is None or not cfg.vision:
        raise LibraryError("Reading slide text needs a vision-capable AI key (Anthropic, OpenAI, Gemini, xAI, Mistral or OpenRouter).")
    data_urls = [
        "data:image/jpeg;base64," + base64.b64encode((settings.media_dir / p).read_bytes()).decode() for p in paths[:20]
    ]
    try:
        result = llm.complete_json(READ_PROMPT, f"{len(data_urls)} slides.", SlideTextRead, images=data_urls)
    except llm.LLMError as exc:
        raise LibraryError(str(exc)) from exc
    with db.session_scope() as session:
        session.get(db.LibraryPost, post_id).slide_texts = result.slides
    return result.slides


def reference_text(post_id: str) -> tuple[str, str]:
    """(reference block for the writer, default topic) for remixing a library post."""
    with db.session_scope() as session:
        post = session.get(db.LibraryPost, post_id)
        if post is None:
            raise LibraryError("post not found")
        slides = post.slide_texts or []
        lines = [f"caption: {post.description}", f"views: {post.views:,}  likes: {post.likes:,}  slides: {len(post.images)}"]
        lines += [f"slide {i + 1}: {t}" for i, t in enumerate(slides) if t]
        topic = slides[0] if slides and slides[0] else re.sub(r"#\S+", "", post.description).strip() or "the same topic"
        return "\n".join(lines), topic


# --- jobs --------------------------------------------------------------------------------------


def _job(job_id: str, **fields: Any) -> None:
    with db.session_scope() as session:
        job = session.get(db.Job, job_id)
        if job:
            for k, v in fields.items():
                setattr(job, k, v)


def import_job(job_id: str, raw_items: list[str], max_posts: int) -> None:
    _job(job_id, status="running", message="Starting…")
    done, errors, profile_ids = 0, [], set()
    try:
        for raw in raw_items:
            try:
                kind, value = parse_item(raw)
                if kind == "post":
                    _job(job_id, message=f"Fetching {value}")
                    profile_ids.update(save_posts([fetch_post(value)]))
                else:
                    _job(job_id, message=f"Fetching @{value}")
                    profile_ids.update(_import_profile(value, max_posts))
                done += 1
            except Exception as exc:  # noqa: BLE001 - keep going through the list
                errors.append(f"{raw}: {exc}")
            time.sleep(random.uniform(1.0, 2.5))  # be gentle with TikTok
        for pid in profile_ids:
            classify_profile(pid)
        _job(
            job_id,
            status="completed" if done else "failed",
            message=f"Imported {done}/{len(raw_items)}" + (f". Errors: {'; '.join(errors)[:1500]}" if errors else ""),
            result={"profile_ids": sorted(profile_ids)},
        )
    except Exception as exc:  # noqa: BLE001
        _job(job_id, status="failed", message=str(exc)[:2000])


def _import_profile(username: str, max_posts: int) -> set[str]:
    if apify_available():
        posts = apify_scrape(profiles=[username], max_posts=max_posts)
        ids = set(save_posts(posts))
        if ids:
            return ids
    info = fetch_profile(username)
    with db.session_scope() as session:
        profile = upsert_profile(session, info)
        pid = profile.id
    posts = browser_profile_posts(info["username"], max_posts)
    save_posts(posts)
    return {pid}


def discover_job(job_id: str, query: str, max_posts: int) -> None:
    _job(job_id, status="running", message=f"Searching TikTok for {query!r}…")
    try:
        q = query.strip()
        posts = apify_scrape(hashtags=[q.lstrip("#")], max_posts=max_posts) if q.startswith("#") else apify_scrape(queries=[q], max_posts=max_posts)
        _job(job_id, message=f"Saving {len(posts)} posts…")
        profile_ids = save_posts(posts, slideshows_only=True)
        for pid in profile_ids:
            classify_profile(pid)
        _job(job_id, status="completed", message=f"Found {len(profile_ids)} slideshow accounts", result={"profile_ids": profile_ids})
    except Exception as exc:  # noqa: BLE001
        _job(job_id, status="failed", message=str(exc)[:2000])


def refresh_all(max_posts: int = 20) -> None:
    """Daily refresh of tracked profiles (stats + new posts)."""
    with db.session_scope() as session:
        usernames = session.scalars(select(db.LibraryProfile.username)).all()
    for name in usernames:
        try:
            _import_profile(name, max_posts)
        except Exception as exc:  # noqa: BLE001
            log.info("refresh of @%s failed: %s", name, exc)
        time.sleep(random.uniform(2, 5))
