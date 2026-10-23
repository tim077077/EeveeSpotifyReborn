"""The content pipeline: generate -> source images -> render -> (export video) -> publish -> track."""

from __future__ import annotations

import logging
import random
import shutil
import subprocess
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import browser_queue, db, generator, imagegen, images, renderer, tiktok, timing
from .config import settings
from .schemas import CampaignPlan, ImageSettings, PostSettings, Slide, SlideshowPlan, SlideshowSpec, TextItem

log = logging.getLogger("slidemill.pipeline")

executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="slidemill")
# Long-running orchestration (studio batches, library scrapes) waits on `executor` work, so it
# runs on its own pool to avoid starving it.
job_executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix="slidemill-job")
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
        slides.append(Slide(role=ps.role, text_items=items, image_query=ps.image_query, image_prompt=ps.image_prompt))
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


def _source_for(settings_: ImageSettings) -> str:
    if settings_.source == "auto":
        return "stock" if images.providers() else "ai"
    return settings_.source


def assign_images(spec: SlideshowSpec, settings_: ImageSettings, explicit: list[str] | None = None) -> None:
    """Fill each slide's background from explicit refs, collections, stock search or AI generation.

    Uses short DB sessions: AI generation can take a minute and must not hold the SQLite write lock.
    """
    explicit = explicit or []
    used_ids: set[str] = set()
    used_urls: set[str] = set()
    orientation = images.orientation_for(spec.aspect_ratio)
    source = _source_for(settings_)

    has_cta = any(s.role == "cta" for s in spec.slides)
    if settings_.cta_slide and settings_.cta_collection_id and not has_cta and len(spec.slides) < 20:
        spec.slides.append(Slide(role="cta"))

    needs_ai: list[Slide] = []
    for i, slide in enumerate(spec.slides):
        if i < len(explicit) and explicit[i]:
            slide.image = explicit[i]
            continue
        if slide.image or slide.images:
            continue
        collection_id = {
            "hook": settings_.hook_collection_id,
            "cta": settings_.cta_collection_id,
        }.get(slide.role) or (settings_.collection_id if source == "collection" else None)
        picked_path = None
        if collection_id:
            with db.session_scope() as session:
                picked = images.pick_from_collection(session, collection_id, used_ids)
                if picked:
                    used_ids.add(picked.id)
                    picked_path = picked.path
        if picked_path is None and source == "stock":
            with db.session_scope() as session:
                picked = images.auto_image(session, slide.image_query or spec.title, orientation, used_urls)
                picked_path = picked.path if picked else None
        if picked_path is None and source in {"ai", "stock"}:
            # AI is also the fallback when stock search finds nothing.
            needs_ai.append(slide)
            continue
        slide.image = picked_path

    if needs_ai:
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = pool.map(lambda sl: _ai_background(sl, spec, settings_.visual_style), needs_ai)
            for slide, path in zip(needs_ai, results):
                slide.image = path


def _ai_background(slide: Slide, spec: SlideshowSpec, visual_style: str) -> str | None:
    prompt = slide.image_prompt or slide.image_query or spec.title
    try:
        data = imagegen.generate(prompt, spec.aspect_ratio, visual_style)
    except imagegen.ImageGenError as exc:
        log.warning("AI background failed (%s); slide keeps the plain background", exc)
        return None
    rel, w, h = images.store_bytes(data, "library/ai")
    with db.session_scope() as session:
        session.add(db.Image(collection_id=None, path=rel, source="ai", query=prompt[:500], width=w, height=h, use_count=1))
    return rel


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
    session: Session,
    prompt: str,
    automation_id: str | None = None,
    cron_id: str | None = None,
    queue: bool = False,
) -> db.Slideshow:
    """Insert a slideshow row to be filled by generate_into.

    Single requests are refused when the generation slots are full; batch requests (queue=True)
    wait their turn in "queued" status instead.
    """
    if not queue:
        active = session.scalar(
            select(func.count()).select_from(db.Slideshow).where(db.Slideshow.status.in_(["generating", "rendering"]))
        )
        if active >= settings.max_concurrent_generations:
            raise PipelineError(f"{active} slideshows already generating (limit {settings.max_concurrent_generations}).")
    show = db.Slideshow(
        prompt=prompt, automation_id=automation_id, created_by_cron_id=cron_id, status="queued" if queue else "generating"
    )
    session.add(show)
    session.flush()
    return show


def _set_status(slideshow_id: str, status: str, error: str = "") -> None:
    with db.session_scope() as session:
        show = session.get(db.Slideshow, slideshow_id)
        if show:
            show.status = status
            if error:
                show.error = error[:2000]


def generate_into(
    slideshow_id: str,
    *,
    style: str = "",
    language: str = "English",
    image_settings: ImageSettings | None = None,
    product: dict | None = None,
    explicit_images: list[str] | None = None,
    aspect_ratio: str | None = None,
    reference: str = "",
) -> None:
    """Fill a pending slideshow: write text, source images, render. Runs in a worker thread."""
    image_settings = image_settings or ImageSettings()
    with _generation_slots:
        try:
            _set_status(slideshow_id, "generating")
            with db.session_scope() as session:
                show = session.get(db.Slideshow, slideshow_id)
                topic, automation_id = show.prompt, show.automation_id
                titles = recent_titles(session, automation_id)
            plan = generator.generate_plan(
                topic, style, language, product, titles, visual_style=image_settings.visual_style, reference=reference
            )
            spec = spec_from_plan(plan, image_settings, aspect_ratio)
            assign_images(spec, image_settings, explicit_images)
            with db.session_scope() as session:
                show = session.get(db.Slideshow, slideshow_id)
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
            _set_status(slideshow_id, "failed", str(exc))


# --- studio: brief -> format -> finished slideshows -----------------------------------


def run_studio(job_id: str, brief: str, count: int, image_source: str, aspect_ratio: str | None, language: str | None,
               product_id: str | None, save_as_automation: bool, tiktok_account_id: str | None, schedule: list[str],
               target_country: str | None = None, posts_per_day: int = 2, auto_publish: bool = False,
               privacy_level: str | None = None, browser_action: str | None = None) -> None:
    """Plan a content format from a brief, then generate `count` complete slideshows from it."""
    _job(job_id, status="running", message="Planning the format…")
    try:
        # Extra hooks give a saved automation material for future runs.
        campaign: CampaignPlan = generator.plan_campaign(brief, count + (10 if save_as_automation else 0))
        image_settings = ImageSettings(
            source=image_source, visual_style=campaign.visual_style, aspect_ratio=aspect_ratio or campaign.aspect_ratio
        )
        product = None
        automation_id = None
        with db.session_scope() as session:
            if product_id and (p := session.get(db.Product, product_id)):
                product = {"name": p.name, "description": p.description}
            if save_as_automation:
                automation = db.Automation(
                    title=campaign.name,
                    tiktok_account_id=tiktok_account_id,
                    product_id=product_id,
                    hooks=campaign.hooks,
                    style=campaign.style,
                    language=language or campaign.language,
                    post_settings=PostSettings(auto_post=bool(tiktok_account_id)).model_dump(),
                    image_settings=image_settings.model_dump(),
                )
                if target_country:
                    plan = timing.best_times(target_country, posts_per_day, tiktok_account_id)
                    automation.timezone, automation.target_country = plan["timezone"], plan["country"]
                    schedule = plan["crons"]
                    automation.post_settings = PostSettings(
                        auto_post=bool(tiktok_account_id),
                        post_mode="DIRECT_POST" if auto_publish else "MEDIA_UPLOAD",
                        visibility=privacy_level or "SELF_ONLY",
                        consented=auto_publish,  # the API checked consent before starting
                        allow_comments=False,
                    ).model_dump()
                if browser_action:
                    automation.post_settings = PostSettings(
                        auto_post=True, post_mode="BROWSER", browser_action=browser_action
                    ).model_dump()
                automation.schedules = [db.ScheduleJob(cron=c) for c in schedule]
                session.add(automation)
                session.flush()
                automation_id = automation.id
            ids = [create_pending(session, hook, automation_id, queue=True).id for hook in campaign.hooks[:count]]
        if save_as_automation:
            from . import scheduler  # local import: scheduler imports pipeline

            scheduler.sync()
        _job(
            job_id,
            message=f"Writing {len(ids)} slideshows…",
            result={"campaign": campaign.model_dump(), "slideshow_ids": ids, "automation_id": automation_id},
        )
        futures = [
            executor.submit(
                generate_into, sid, style=campaign.style, language=language or campaign.language,
                image_settings=image_settings, product=product, aspect_ratio=image_settings.aspect_ratio,
            )
            for sid in ids
        ]
        for f in futures:
            f.result()
        # Score every Studio slideshow so you can pick the best one to post first.
        from . import virality

        for sid in ids:
            try:
                virality.predict(sid)
            except Exception:  # noqa: BLE001 - a missing score never fails the batch
                log.info("prediction skipped for %s", sid)
        _job(job_id, status="completed", message="Done")
    except Exception as exc:  # noqa: BLE001
        log.exception("studio job %s failed", job_id)
        _job(job_id, status="failed", message=str(exc)[:2000])


def _job(job_id: str, status: str | None = None, message: str | None = None, result: dict | None = None) -> None:
    with db.session_scope() as session:
        job = session.get(db.Job, job_id)
        if job is None:
            return
        if status:
            job.status = status
        if message is not None:
            job.message = message
        if result is not None:
            job.result = {**(job.result or {}), **result}


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


def run_automation(
    automation_id: str,
    hook: str | None = None,
    mode: str = "export",
    cron_id: str | None = None,
    publish_at: datetime | None = None,
) -> str | None:
    """Generate one slideshow for an automation and, if configured, publish it. Blocking.

    With `publish_at` (scheduled runs start a little early), the finished slideshow waits until
    then - plus a few random minutes so posts don't land on the exact same second every day.
    """
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
        min_score = automation.min_viral_score or 0

    generate_into(show_id, **kwargs)
    show_id = pretest(show_id, min_score, kwargs)

    if mode == "export" and post_settings.auto_post and (account_id or post_settings.post_mode == "BROWSER"):
        if publish_at is not None:
            wait = (publish_at - datetime.now(timezone.utc)).total_seconds() + random.uniform(0, 240)
            if 0 < wait < 3600:
                time.sleep(wait)
        try:
            publish(show_id, account_id, post_settings)
        except PipelineError as exc:
            log.warning("auto-publish failed for %s: %s", show_id, exc)
    return show_id


def pretest(show_id: str, min_score: int, kwargs: dict) -> str:
    """Predict the new slideshow's virality; below `min_score`, write one more version using the
    predictor's tips and keep whichever scores higher (the other is deleted). 0 = off."""
    from . import virality  # local import: virality imports db/llm only, but keep pipeline import-light

    if min_score <= 0:
        return show_id
    try:
        first = virality.predict(show_id)
    except virality.PredictError as exc:
        log.info("pre-test skipped: %s", exc)
        return show_id
    if first["viral_potential"] >= min_score:
        return show_id
    with db.session_scope() as session:
        show = session.get(db.Slideshow, show_id)
        retry = create_pending(session, show.prompt, show.automation_id, show.created_by_cron_id, queue=True)
        retry_id = retry.id
    advice = "\n- ".join(
        [f"A first draft scored {first['viral_potential']}/100. Fix this:", *first["tips"]]
        + ([f"Consider a hook like: {first['better_hook']}"] if first.get("better_hook") else [])
    )
    generate_into(retry_id, **{**kwargs, "style": (kwargs.get("style") or "") + "\n\n" + advice})
    try:
        second = virality.predict(retry_id)
    except virality.PredictError:
        second = None
    keep = retry_id if second and second["viral_potential"] > first["viral_potential"] else show_id
    drop = show_id if keep == retry_id else retry_id
    with db.session_scope() as session:
        dead = session.get(db.Slideshow, drop)
        if dead:
            for rel in dead.rendered + ([dead.video_path] if dead.video_path else []):
                images.delete_file(rel)
            session.delete(dead)
    log.info("pre-test kept %s (%s -> %s)", keep, first["viral_potential"], second and second["viral_potential"])
    return keep


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
    account_id: str | None,
    post_settings: PostSettings | None = None,
    *,
    post_mode: str | None = None,
    caption: str | None = None,
    description: str | None = None,
    browser_action: str | None = None,
    sound: bool | None = None,
    privacy_level: str | None = None,
    allow_comments: bool | None = None,
    brand_organic: bool | None = None,
    branded_content: bool | None = None,
    user_confirmed: bool = False,
) -> str:
    """Send a rendered slideshow to TikTok.

    Direct posts need the account owner's explicit consent: either `user_confirmed` (they just
    clicked Post in the dashboard) or `post_settings.consented` (given when setting up the
    automation). Without it the post goes to drafts instead.

    BROWSER mode queues the post for the Slidemill browser extension instead (no account needed).
    """
    post_settings = post_settings or PostSettings()
    with db.session_scope() as session:
        show = session.get(db.Slideshow, slideshow_id)
        if show is None or show.status != "completed" or not show.rendered:
            raise PipelineError("slideshow is not rendered yet")
        mode = post_mode or post_settings.post_mode
        if mode == "BROWSER":
            if caption is not None:
                show.caption = caption[:89]
            if description is not None:
                show.description = description[:4000]
            post = browser_queue.queue_post(
                session,
                show,
                action=browser_action or post_settings.browser_action,
                sound=post_settings.auto_music if sound is None else sound,
            )
            return post.id
        account = session.get(db.TikTokAccount, account_id) if account_id else None
        if account is None:
            raise PipelineError("TikTok account not found")

        if mode == "DIRECT_POST" and not (user_confirmed or post_settings.consented):
            log.info("no posting consent recorded for %s; sending to drafts instead", slideshow_id)
            mode = "MEDIA_UPLOAD"
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
                privacy_level=privacy_level or post_settings.visibility,
                auto_add_music=post_settings.auto_music,
                allow_comments=post_settings.allow_comments if allow_comments is None else allow_comments,
                is_aigc=post_settings.disclose_ai_content,
                brand_organic=post_settings.brand_organic if brand_organic is None else brand_organic,
                branded_content=post_settings.branded_content if branded_content is None else branded_content,
            )
            post.status = "processing"
        except Exception as exc:  # noqa: BLE001 - record any failure on the post
            post.status = "failed"
            post.error = str(exc)[:2000]
        return post.id


def refresh_posts() -> None:
    """Poll publish status for in-flight posts, then pull metrics for published ones."""
    try:
        browser_queue.refresh_metrics()
    except Exception:  # noqa: BLE001 - API-post metrics below should still update
        log.exception("browser post metrics refresh failed")
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
