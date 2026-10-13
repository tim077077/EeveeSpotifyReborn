"""The content pipeline: generate -> source images -> render -> (export video) -> publish -> track."""

from __future__ import annotations

import logging
import random
import shutil
import subprocess
import threading
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import db, generator, images, renderer, tiktok
from .config import settings
from .schemas import ImageSettings, PostSettings, Slide, SlideshowPlan, SlideshowSpec, TextItem

log = logging.getLogger("slidemill.pipeline")

executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="slidemill")
_generation_slots = threading.BoundedSemaphore(settings.max_concurrent_generations)


class PipelineError(RuntimeError):
    pass


class PublishLimitError(PipelineError):
    pass


# --- building the spec ------------------------------------------------------------


def spec_from_plan(plan: SlideshowPlan, image_settings: ImageSettings, aspect_ratio: str | None = None) -> SlideshowSpec:
    slides = []
    for i, ps in enumerate(plan.slides):
        items = [TextItem(**t.model_dump()) for t in ps.text_items]
        if image_settings.text_on_first_slide_only and i > 0:
            items = []
        slides.append(Slide(role=ps.role, text_items=items, image_query=ps.image_query))
    return SlideshowSpec(
        title=plan.title,
        aspect_ratio=aspect_ratio or image_settings.aspect_ratio,
        text_position=plan.text_position,
        overlay_body=image_settings.overlay_body,
        overlay_hook=image_settings.overlay_hook,
        overlay_cta=image_settings.overlay_cta,
        background_opacity=image_settings.background_opacity,
        keep_original_aspect_ratio=image_settings.keep_original_aspect_ratio,
        slides=slides[:20],
    )


def assign_images(session: Session, spec: SlideshowSpec, settings_: ImageSettings, explicit: list[str] | None = None) -> None:
    """Fill each slide's background from explicit refs, collections, or stock search."""
    explicit = explicit or []
    used_ids: set[str] = set()
    used_urls: set[str] = set()
    orientation = images.orientation_for(spec.aspect_ratio)

    has_cta = any(s.role == "cta" for s in spec.slides)
    if settings_.cta_slide and settings_.cta_collection_id and not has_cta and len(spec.slides) < 20:
        spec.slides.append(Slide(role="cta"))

    for i, slide in enumerate(spec.slides):
        if i < len(explicit) and explicit[i]:
            slide.image = explicit[i]
            continue
        if slide.image or slide.images:
            continue
        collection_id = {
            "hook": settings_.hook_collection_id,
            "cta": settings_.cta_collection_id,
        }.get(slide.role) or (settings_.collection_id if settings_.source == "collection" else None)
        picked = None
        if collection_id:
            picked = images.pick_from_collection(session, collection_id, used_ids)
        if picked is None and (settings_.source == "auto" or not collection_id):
            query = slide.image_query or spec.title
            picked = images.auto_image(session, query, orientation, used_urls)
        if picked is not None:
            used_ids.add(picked.id)
            slide.image = picked.path


def render(session: Session, show: db.Slideshow) -> None:
    spec = SlideshowSpec.model_validate(show.spec)
    out_dir = settings.media_dir / "slideshows" / show.id
    if out_dir.exists():
        shutil.rmtree(out_dir)
    show.rendered = renderer.render_slideshow(spec, out_dir)
    show.video_path = ""


def export_video(show: db.Slideshow, seconds_per_slide: float = 3.0) -> str:
    """Stitch rendered slides into an MP4 (for Reels/Shorts or video-mode TikTok)."""
    if not shutil.which("ffmpeg"):
        raise PipelineError("ffmpeg is not installed")
    if not show.rendered:
        raise PipelineError("slideshow has not been rendered")
    out_dir = settings.media_dir / "slideshows" / show.id
    listing = out_dir / "frames.txt"
    lines = []
    for rel in show.rendered:
        lines += [f"file '{(settings.media_dir / rel).resolve()}'", f"duration {seconds_per_slide}"]
    lines.append(f"file '{(settings.media_dir / show.rendered[-1]).resolve()}'")
    listing.write_text("\n".join(lines) + "\n")
    target = out_dir / "slideshow.mp4"
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(listing),
        "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p", "-r", "30", "-c:v", "libx264", "-movflags", "+faststart",
        str(target),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        raise PipelineError(f"ffmpeg failed: {result.stderr.strip()[:500]}")
    show.video_path = str(target.relative_to(settings.media_dir))
    return show.video_path


# --- generation ---------------------------------------------------------------------


def recent_titles(session: Session, automation_id: str | None, limit: int = 30) -> list[str]:
    q = select(db.Slideshow.title).where(db.Slideshow.status == "completed").order_by(db.Slideshow.created_at.desc())
    if automation_id:
        q = q.where(db.Slideshow.automation_id == automation_id)
    return [t for t in session.scalars(q.limit(limit)).all() if t]


def create_pending(
    session: Session, prompt: str, automation_id: str | None = None, cron_id: str | None = None
) -> db.Slideshow:
    active = session.scalar(
        select(func.count()).select_from(db.Slideshow).where(db.Slideshow.status.in_(["generating", "rendering"]))
    )
    if active >= settings.max_concurrent_generations:
        raise PipelineError(f"{active} slideshows already generating (limit {settings.max_concurrent_generations}).")
    show = db.Slideshow(prompt=prompt, automation_id=automation_id, created_by_cron_id=cron_id, status="generating")
    session.add(show)
    session.flush()
    return show


def generate_into(
    slideshow_id: str,
    *,
    style: str = "",
    language: str = "English",
    image_settings: ImageSettings | None = None,
    product: dict | None = None,
    explicit_images: list[str] | None = None,
    aspect_ratio: str | None = None,
) -> None:
    """Fill a pending slideshow. Runs in a worker thread; records failures on the row."""
    image_settings = image_settings or ImageSettings()
    with _generation_slots:
        try:
            with db.session_scope() as session:
                show = session.get(db.Slideshow, slideshow_id)
                topic, automation_id = show.prompt, show.automation_id
                titles = recent_titles(session, automation_id)
            plan = generator.generate_plan(topic, style, language, product, titles)
            with db.session_scope() as session:
                show = session.get(db.Slideshow, slideshow_id)
                spec = spec_from_plan(plan, image_settings, aspect_ratio)
                assign_images(session, spec, image_settings, explicit_images)
                show.title = plan.title
                show.caption = plan.caption
                show.description = plan.description
                show.fingerprint = generator.fingerprint(" ".join(t.text for t in spec.slides[0].text_items))
                show.spec = spec.model_dump()
                show.status = "rendering"
            with db.session_scope() as session:
                show = session.get(db.Slideshow, slideshow_id)
                render(session, show)
                show.status = "completed"
        except Exception as exc:  # noqa: BLE001 - surface any failure on the row
            log.exception("generation failed for %s", slideshow_id)
            with db.session_scope() as session:
                show = session.get(db.Slideshow, slideshow_id)
                if show:
                    show.status = "failed"
                    show.error = str(exc)[:2000]


# --- automations --------------------------------------------------------------------


def pick_hook(session: Session, automation: db.Automation) -> str:
    hooks = [h for h in automation.hooks if h.strip()]
    if not hooks:
        return automation.title
    used = Counter(
        session.scalars(select(db.Slideshow.prompt).where(db.Slideshow.automation_id == automation.id)).all()
    )
    low = min(used.get(h, 0) for h in hooks)
    return random.choice([h for h in hooks if used.get(h, 0) == low])


def run_automation(automation_id: str, hook: str | None = None, mode: str = "export", cron_id: str | None = None) -> str | None:
    """Generate one slideshow for an automation and, if configured, publish it. Blocking."""
    with db.session_scope() as session:
        automation = session.get(db.Automation, automation_id)
        if automation is None:
            return None
        if cron_id and automation.paused:
            log.info("automation %s paused; skipping scheduled run", automation_id)
            return None
        show = create_pending(session, hook or pick_hook(session, automation), automation.id, cron_id)
        product = None
        if automation.product_id:
            p = session.get(db.Product, automation.product_id)
            if p:
                product = {"name": p.name, "description": p.description, "url": p.url}
        kwargs = dict(
            style=automation.style,
            language=automation.language,
            image_settings=ImageSettings.model_validate(automation.image_settings or {}),
            product=product,
        )
        post_settings = PostSettings.model_validate(automation.post_settings or {})
        account_id = automation.tiktok_account_id
        show_id = show.id

    generate_into(show_id, **kwargs)

    if mode == "export" and post_settings.auto_post and account_id:
        try:
            publish(show_id, account_id, post_settings)
        except PipelineError as exc:
            log.warning("auto-publish failed for %s: %s", show_id, exc)
    return show_id


# --- publishing ---------------------------------------------------------------------


def media_url(rel: str) -> str:
    return f"{settings.public_base_url}/media/{rel}"


def direct_posts_last_24h(session: Session, account_id: str) -> int:
    since = datetime.now(timezone.utc) - timedelta(hours=24)
    return session.scalar(
        select(func.count())
        .select_from(db.Post)
        .where(db.Post.tiktok_account_id == account_id, db.Post.post_mode == "DIRECT_POST", db.Post.created_at >= since)
        .where(db.Post.status != "failed")
    )


def publish(
    slideshow_id: str,
    account_id: str,
    post_settings: PostSettings | None = None,
    *,
    post_mode: str | None = None,
    caption: str | None = None,
    description: str | None = None,
) -> str:
    post_settings = post_settings or PostSettings()
    with db.session_scope() as session:
        show = session.get(db.Slideshow, slideshow_id)
        account = session.get(db.TikTokAccount, account_id)
        if show is None or show.status != "completed" or not show.rendered:
            raise PipelineError("slideshow is not rendered yet")
        if account is None:
            raise PipelineError("TikTok account not found")

        mode = post_mode or post_settings.post_mode
        if mode == "DIRECT_POST" and direct_posts_last_24h(session, account_id) >= settings.tiktok_direct_posts_per_day:
            if not settings.tiktok_fallback_to_draft:
                raise PublishLimitError("Direct-post limit reached for this account in the last 24h.")
            mode = "MEDIA_UPLOAD"

        title = caption if caption is not None else (
            post_settings.caption_static if post_settings.caption_mode == "static" else show.caption
        )
        desc = description if description is not None else (
            post_settings.description_static if post_settings.description_mode == "static" else show.description
        )
        post = db.Post(slideshow_id=show.id, tiktok_account_id=account.id, post_mode=mode, status="pending")
        session.add(post)
        session.flush()
        try:
            post.publish_id = tiktok.publish_photos(
                account,
                [media_url(r) for r in show.rendered],
                title=title or show.title,
                description=desc or "",
                post_mode=mode,
                privacy_level=post_settings.visibility,
                auto_add_music=post_settings.auto_music,
                allow_comments=post_settings.allow_comments,
                is_aigc=post_settings.disclose_ai_content,
            )
            post.status = "processing"
        except Exception as exc:  # noqa: BLE001 - record any failure on the post
            post.status = "failed"
            post.error = str(exc)[:2000]
        return post.id


def refresh_posts() -> None:
    """Poll publish status for in-flight posts, then pull metrics for published ones."""
    with db.session_scope() as session:
        pending = session.scalars(select(db.Post).where(db.Post.status == "processing")).all()
        for post in pending:
            account = session.get(db.TikTokAccount, post.tiktok_account_id) if post.tiktok_account_id else None
            if account is None or not post.publish_id:
                continue
            try:
                data = tiktok.publish_status(account, post.publish_id)
            except tiktok.TikTokError as exc:
                log.warning("status check failed for %s: %s", post.id, exc)
                continue
            status = data.get("status", "")
            if status == "PUBLISH_COMPLETE":
                post.status = "published"
                ids = data.get("publicaly_available_post_id") or data.get("publicly_available_post_id") or []
                post.post_id = str(ids[0]) if ids else ""
            elif status == "SEND_TO_USER_INBOX":
                post.status = "sent_to_drafts"
            elif status == "FAILED":
                post.status = "failed"
                post.error = data.get("fail_reason", "publish failed")

        by_account: dict[str, list[db.Post]] = {}
        for post in session.scalars(select(db.Post).where(db.Post.post_id != "")).all():
            if post.tiktok_account_id:
                by_account.setdefault(post.tiktok_account_id, []).append(post)
        now = datetime.now(timezone.utc)
        for account_id, posts in by_account.items():
            account = session.get(db.TikTokAccount, account_id)
            if account is None:
                continue
            try:
                videos = {v["id"]: v for v in tiktok.video_metrics(account, [p.post_id for p in posts])}
            except tiktok.TikTokError as exc:
                log.warning("metrics fetch failed for %s: %s", account_id, exc)
                continue
            for post in posts:
                v = videos.get(post.post_id)
                if v:
                    post.views = v.get("view_count", 0)
                    post.likes = v.get("like_count", 0)
                    post.comments = v.get("comment_count", 0)
                    post.shares = v.get("share_count", 0)
                    post.metrics_updated_at = now
