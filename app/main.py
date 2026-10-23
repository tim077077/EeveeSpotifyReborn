"""FastAPI app: REST API under /api/v1, the dashboard at /, rendered media at /media."""

from __future__ import annotations

import io
import logging
import zipfile
import os
import secrets
from datetime import datetime, timedelta, timezone
from contextlib import asynccontextmanager
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, Depends, FastAPI, File, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import browser_queue, db, imagegen, images, legal, library, llm, pipeline, scheduler, store, tiktok, timing, virality
from .config import STATIC_DIR, settings
from .schemas import (
    ApplyTimingRequest,
    ExtensionResult,
    AutomationIn,
    AutomationPatch,
    CollectionIn,
    GenerateRequest,
    ImageSettings,
    ImportImagesRequest,
    LibraryDiscoverRequest,
    LibraryImportRequest,
    PostSettings,
    ProductIn,
    PublishRequest,
    RemixRequest,
    RunRequest,
    SettingsUpdate,
    SlideshowSpec,
    StudioRequest,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def lifespan(_: FastAPI):
    check_deployment_security()
    db.init_db()
    if settings.run_scheduler:
        scheduler.start()
    yield
    scheduler.shutdown()


def check_deployment_security() -> None:
    """Refuse to serve a public deployment without a strong dashboard/API password."""
    if not settings.is_public or os.environ.get("SLIDEMILL_ALLOW_NO_AUTH") == "1":
        return
    if len(settings.api_key) < 24:
        raise RuntimeError(
            f"PUBLIC_BASE_URL is {settings.public_base_url}, so this server is reachable from the internet, "
            "but SLIDEMILL_API_KEY is missing or shorter than 24 characters. Anyone could control your TikTok "
            "accounts and read your keys. Set a long random SLIDEMILL_API_KEY (deploy/install.sh does this)."
        )


app = FastAPI(title="Slidemill", version="0.1.0", lifespan=lifespan)


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "same-origin")
    if settings.public_base_url.startswith("https://"):
        response.headers.setdefault("Strict-Transport-Security", "max-age=31536000")
    return response


@app.get("/favicon.ico", include_in_schema=False)
def favicon():
    return FileResponse(STATIC_DIR / "favicon.svg", media_type="image/svg+xml")


@app.get("/about", response_class=HTMLResponse, include_in_schema=False)
def about_page() -> str:
    return legal.about_html()


@app.get("/privacy", response_class=HTMLResponse, include_in_schema=False)
def privacy_page() -> str:
    return legal.privacy_html()


@app.get("/terms", response_class=HTMLResponse, include_in_schema=False)
def terms_page() -> str:
    return legal.terms_html()


@app.get("/healthz", include_in_schema=False)
def healthz() -> dict[str, str]:
    return {"status": "ok"}
app.mount("/media", StaticFiles(directory=settings.media_dir), name="media")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


def get_session():
    with db.session_scope() as session:
        yield session


def require_key(request: Request) -> None:
    if not settings.api_key:
        return
    header = request.headers.get("authorization", "")
    token = header.removeprefix("Bearer ").strip()
    if not secrets.compare_digest(token, settings.api_key):
        raise HTTPException(401, {"error": "Missing or invalid API key", "code": "UNAUTHORIZED"})


api = APIRouter(prefix="/api/v1", dependencies=[Depends(require_key)])


def _404(what: str):
    return HTTPException(404, {"error": f"{what} not found", "code": "NOT_FOUND"})


# --- serializers ---------------------------------------------------------------------


def slideshow_out(s: db.Slideshow, full: bool = False) -> dict[str, Any]:
    out = {
        "id": s.id,
        "title": s.title,
        "prompt": s.prompt,
        "status": s.status,
        "automation_id": s.automation_id,
        "created_by_cron_id": s.created_by_cron_id,
        "caption": s.caption,
        "description": s.description,
        "slides": [pipeline.media_url(r) for r in s.rendered],
        "video_url": pipeline.media_url(s.video_path) if s.video_path else None,
        "error": s.error or None,
        "created_at": s.created_at.isoformat(),
        "posts": [post_out(p) for p in s.posts],
    }
    if full:
        out["prediction"] = s.prediction
        out["spec"] = s.spec
    else:
        p = s.prediction or None
        out["prediction"] = {k: p.get(k) for k in ("viral_potential", "hook_score", "hold_rate", "verdict_tag", "attention")} if p else None
    return out


def post_out(p: db.Post) -> dict[str, Any]:
    return {
        "id": p.id,
        "slideshow_id": p.slideshow_id,
        "tiktok_account_id": p.tiktok_account_id,
        "post_mode": p.post_mode,
        "status": p.status,
        "post_id": p.post_id or None,
        "error": p.error or None,
        "views": p.views,
        "likes": p.likes,
        "comments": p.comments,
        "shares": p.shares,
        "created_at": p.created_at.isoformat(),
    }


def automation_out(a: db.Automation) -> dict[str, Any]:
    return {
        "id": a.id,
        "title": a.title,
        "tiktok_account_id": a.tiktok_account_id,
        "product_id": a.product_id,
        "hooks": a.hooks,
        "style": a.style,
        "language": a.language,
        "post_settings": a.post_settings,
        "image_settings": a.image_settings,
        "timezone": a.timezone or settings.timezone,
        "target_country": a.target_country,
        "paused": a.paused,
        "favorite": a.favorite,
        "schedule": [{"job_id": j.id, "cron": j.cron} for j in a.schedules],
        "next_runs": scheduler.next_runs(a.id),
        "min_viral_score": a.min_viral_score or 0,
        "created_at": a.created_at.isoformat(),
    }


def image_out(i: db.Image) -> dict[str, Any]:
    return {"id": i.id, "path": i.path, "url": pipeline.media_url(i.path), "source": i.source, "use_count": i.use_count}


# --- status ------------------------------------------------------------------------------


@api.get("/status")
def status() -> dict[str, Any]:
    cfg = llm.resolve()
    return {
        "ai": bool(cfg),
        "ai_provider": cfg.provider if cfg else None,
        "model": cfg.model if cfg else None,
        "image_generation": imagegen.describe(),
        "image_providers": images.providers(),
        "apify": library.apify_available(),
        "tiktok": tiktok.configured(),
        "public_base_url": settings.public_base_url,
        "timezone": settings.timezone,
    }


# --- slideshows ----------------------------------------------------------------------------


@api.post("/slideshows/generate", status_code=201)
def generate(body: GenerateRequest, session: Session = Depends(get_session)) -> dict[str, Any]:
    kwargs: dict[str, Any] = {"explicit_images": body.images, "aspect_ratio": body.aspect_ratio}
    style = ""
    image_settings = ImageSettings()
    if body.automation_id:
        automation = session.get(db.Automation, body.automation_id)
        if automation is None:
            raise _404("automation")
        style = automation.style
        image_settings = ImageSettings.model_validate(automation.image_settings or {})
        kwargs["language"] = body.language or automation.language
    else:
        kwargs["language"] = body.language or "English"
    if body.image_source:
        image_settings.source = body.image_source
    kwargs["image_settings"] = image_settings
    try:
        show = pipeline.create_pending(session, body.prompt, body.automation_id)
    except pipeline.PipelineError as exc:
        raise HTTPException(429, {"error": str(exc), "code": "CONCURRENT_LIMIT"}) from exc
    session.commit()
    pipeline.executor.submit(pipeline.generate_into, show.id, style=style, **kwargs)
    return {"slideshow_id": show.id, "status": show.status}


@api.post("/slideshows/create", status_code=201)
def create_manual(spec: SlideshowSpec, session: Session = Depends(get_session)) -> dict[str, Any]:
    if not spec.slides:
        raise HTTPException(422, {"error": "at least one slide is required", "code": "VALIDATION_ERROR"})
    show = db.Slideshow(title=spec.title, prompt="manual", spec=spec.model_dump(), status="rendering")
    session.add(show)
    session.flush()
    try:
        pipeline.render(session, show)
        show.status = "completed"
    except Exception as exc:  # noqa: BLE001
        show.status, show.error = "failed", str(exc)
    return slideshow_out(show, full=True)


@api.get("/slideshows")
def list_slideshows(
    automation_id: str | None = None,
    status: str | None = None,
    limit: int = Query(20, le=100),
    offset: int = 0,
    session: Session = Depends(get_session),
) -> dict[str, Any]:
    q = select(db.Slideshow)
    if automation_id:
        q = q.where(db.Slideshow.automation_id == automation_id)
    if status:
        q = q.where(db.Slideshow.status == status)
    total = session.scalar(select(func.count()).select_from(q.subquery()))
    rows = session.scalars(q.order_by(db.Slideshow.created_at.desc()).limit(limit).offset(offset)).all()
    return {"slideshows": [slideshow_out(s) for s in rows], "total": total, "limit": limit, "offset": offset}


@api.get("/slideshows/{slideshow_id}")
def get_slideshow(slideshow_id: str, session: Session = Depends(get_session)) -> dict[str, Any]:
    show = session.get(db.Slideshow, slideshow_id)
    if show is None:
        raise _404("slideshow")
    return slideshow_out(show, full=True)


@api.get("/slideshows/{slideshow_id}/status")
def slideshow_status(slideshow_id: str, session: Session = Depends(get_session)) -> dict[str, Any]:
    show = session.get(db.Slideshow, slideshow_id)
    if show is None:
        raise _404("slideshow")
    return {"status": show.status, "error": show.error or None, "video_url": pipeline.media_url(show.video_path) if show.video_path else None}


@api.put("/slideshows/{slideshow_id}")
def update_slideshow(slideshow_id: str, body: dict[str, Any], session: Session = Depends(get_session)) -> dict[str, Any]:
    """Edit spec / caption / description and re-render."""
    show = session.get(db.Slideshow, slideshow_id)
    if show is None:
        raise _404("slideshow")
    if show.status in {"generating", "rendering"}:
        raise HTTPException(409, {"error": "slideshow is still generating", "code": "CONFLICT"})
    if "caption" in body:
        show.caption = str(body["caption"])[:89]
    if "description" in body:
        show.description = str(body["description"])[:4000]
    if "spec" in body:
        spec = SlideshowSpec.model_validate(body["spec"])
        show.spec = spec.model_dump()
        show.title = spec.title
        try:
            pipeline.render(session, show)
            show.status, show.error = "completed", ""
        except Exception as exc:  # noqa: BLE001
            show.status, show.error = "failed", str(exc)
    return slideshow_out(show, full=True)


@api.post("/slideshows/{slideshow_id}/video")
def make_video(slideshow_id: str, seconds_per_slide: float = 3.0, session: Session = Depends(get_session)) -> dict[str, Any]:
    show = session.get(db.Slideshow, slideshow_id)
    if show is None:
        raise _404("slideshow")
    try:
        pipeline.export_video(show, seconds_per_slide)
    except pipeline.PipelineError as exc:
        raise HTTPException(422, {"error": str(exc), "code": "VALIDATION_ERROR"}) from exc
    return {"video_url": pipeline.media_url(show.video_path)}


@api.post("/slideshows/{slideshow_id}/predict")
def predict_slideshow(slideshow_id: str) -> dict[str, Any]:
    """Predict this slideshow's hook strength, swipe-through and viral potential before posting."""
    try:
        return virality.predict(slideshow_id)
    except virality.PredictError as exc:
        if str(exc) == "slideshow not found":
            raise _404("slideshow") from exc
        raise HTTPException(422, {"error": str(exc), "code": "VALIDATION_ERROR"}) from exc


@api.get("/virality/calibration")
def virality_calibration() -> dict[str, Any]:
    return virality.calibration()


@api.delete("/slideshows/{slideshow_id}")
def delete_slideshow(slideshow_id: str, session: Session = Depends(get_session)) -> dict[str, Any]:
    show = session.get(db.Slideshow, slideshow_id)
    if show is None:
        raise _404("slideshow")
    for rel in show.rendered + ([show.video_path] if show.video_path else []):
        images.delete_file(rel)
    session.delete(show)
    return {"deleted": True}


@api.post("/slideshows/{slideshow_id}/publish", status_code=202)
def publish_slideshow(slideshow_id: str, body: PublishRequest, session: Session = Depends(get_session)) -> dict[str, Any]:
    show = session.get(db.Slideshow, slideshow_id)
    if show is None:
        raise _404("slideshow")
    post_settings = None
    account_id = body.tiktok_account_id
    if show.automation_id:
        automation = session.get(db.Automation, show.automation_id)
        if automation:
            post_settings = PostSettings.model_validate(automation.post_settings or {})
            account_id = account_id or automation.tiktok_account_id
    mode = body.post_mode or (post_settings.post_mode if post_settings else "MEDIA_UPLOAD")
    if not account_id and mode != "BROWSER":
        raise HTTPException(422, {"error": "tiktok_account_id is required", "code": "VALIDATION_ERROR"})
    if mode == "DIRECT_POST":
        if not body.privacy_level:
            raise HTTPException(422, {"error": "Choose who can see this post.", "code": "VALIDATION_ERROR"})
        if not body.consent:
            raise HTTPException(422, {"error": "Confirm TikTok's Music Usage Confirmation to post.", "code": "VALIDATION_ERROR"})
        if body.branded_content and body.privacy_level == "SELF_ONLY":
            raise HTTPException(422, {"error": "Branded content can't be private.", "code": "VALIDATION_ERROR"})
    session.commit()
    try:
        post_id = pipeline.publish(
            slideshow_id, account_id, post_settings, post_mode=body.post_mode, caption=body.caption,
            description=body.description, privacy_level=body.privacy_level, allow_comments=body.allow_comments,
            brand_organic=body.brand_organic, branded_content=body.branded_content, user_confirmed=mode == "DIRECT_POST",
            browser_action=body.browser_action, sound=body.sound,
        )
    except pipeline.PublishLimitError as exc:
        raise HTTPException(429, {"error": str(exc), "code": "RATE_LIMITED"}) from exc
    except pipeline.PipelineError as exc:
        raise HTTPException(422, {"error": str(exc), "code": "VALIDATION_ERROR"}) from exc
    with db.session_scope() as s2:
        return post_out(s2.get(db.Post, post_id))


# --- automations ---------------------------------------------------------------------------


def _check_timezone(tz: str) -> None:
    if not tz:
        return
    try:
        ZoneInfo(tz)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise HTTPException(422, {"error": f"unknown timezone {tz!r}", "code": "VALIDATION_ERROR"}) from exc


def _set_schedule(session: Session, automation: db.Automation, crons: list[str]) -> None:
    try:
        crons = [scheduler.validate_cron(c, automation.timezone or None) for c in crons]
    except ValueError as exc:
        raise HTTPException(422, {"error": f"invalid cron: {exc}", "code": "VALIDATION_ERROR"}) from exc
    automation.schedules.clear()
    session.flush()
    for c in crons:
        automation.schedules.append(db.ScheduleJob(cron=c))


@api.post("/automations", status_code=201)
def create_automation(body: AutomationIn, session: Session = Depends(get_session)) -> dict[str, Any]:
    automation = db.Automation(
        title=body.title,
        tiktok_account_id=body.tiktok_account_id,
        product_id=body.product_id,
        hooks=body.hooks,
        style=body.style,
        language=body.language,
        post_settings=body.post_settings.model_dump(),
        image_settings=body.image_settings.model_dump(),
        timezone=body.timezone,
        target_country=body.target_country.upper(),
        min_viral_score=body.min_viral_score,
    )
    _check_timezone(body.timezone)
    session.add(automation)
    session.flush()
    _set_schedule(session, automation, body.schedule)
    session.commit()
    scheduler.sync()
    return automation_out(automation)


@api.get("/automations")
def list_automations(session: Session = Depends(get_session)) -> dict[str, Any]:
    rows = session.scalars(select(db.Automation).order_by(db.Automation.favorite.desc(), db.Automation.created_at.desc())).all()
    return {"automations": [automation_out(a) for a in rows]}


@api.get("/automations/{automation_id}")
def get_automation(automation_id: str, session: Session = Depends(get_session)) -> dict[str, Any]:
    automation = session.get(db.Automation, automation_id)
    if automation is None:
        raise _404("automation")
    return automation_out(automation)


@api.patch("/automations/{automation_id}")
def patch_automation(automation_id: str, body: AutomationPatch, session: Session = Depends(get_session)) -> dict[str, Any]:
    automation = session.get(db.Automation, automation_id)
    if automation is None:
        raise _404("automation")
    data = body.model_dump(exclude_unset=True)
    schedule = data.pop("schedule", None)
    _check_timezone(data.get("timezone") or "")
    for key, value in data.items():
        setattr(automation, key, value)
    if schedule is not None:
        _set_schedule(session, automation, schedule)
    session.commit()
    scheduler.sync()
    return automation_out(automation)


@api.delete("/automations/{automation_id}")
def delete_automation(automation_id: str, session: Session = Depends(get_session)) -> dict[str, Any]:
    automation = session.get(db.Automation, automation_id)
    if automation is None:
        raise _404("automation")
    session.delete(automation)
    session.commit()
    scheduler.sync()
    return {"deleted": True}


@api.post("/automations/{automation_id}/run", status_code=202)
def run_automation(automation_id: str, body: RunRequest, session: Session = Depends(get_session)) -> dict[str, Any]:
    if session.get(db.Automation, automation_id) is None:
        raise _404("automation")
    pipeline.executor.submit(pipeline.run_automation, automation_id, body.hook, body.mode)
    return {"queued": True}


# --- collections & images ------------------------------------------------------------------


@api.get("/collections")
def list_collections(session: Session = Depends(get_session)) -> dict[str, Any]:
    rows = session.execute(
        select(db.Collection, func.count(db.Image.id))
        .outerjoin(db.Image, db.Image.collection_id == db.Collection.id)
        .group_by(db.Collection.id)
        .order_by(db.Collection.created_at.desc())
    ).all()
    return {"collections": [{"id": c.id, "name": c.name, "image_count": n} for c, n in rows]}


@api.post("/collections", status_code=201)
def create_collection(body: CollectionIn, session: Session = Depends(get_session)) -> dict[str, Any]:
    c = db.Collection(name=body.name)
    session.add(c)
    session.flush()
    return {"id": c.id, "name": c.name, "image_count": 0}


@api.delete("/collections/{collection_id}")
def delete_collection(collection_id: str, session: Session = Depends(get_session)) -> dict[str, Any]:
    c = session.get(db.Collection, collection_id)
    if c is None:
        raise _404("collection")
    for img in c.images:
        images.delete_file(img.path)
    session.delete(c)
    return {"deleted": True}


@api.get("/collections/{collection_id}/images")
def collection_images(collection_id: str, limit: int = Query(100, le=500), offset: int = 0, session: Session = Depends(get_session)):
    rows = session.scalars(
        select(db.Image).where(db.Image.collection_id == collection_id).order_by(db.Image.created_at.desc()).limit(limit).offset(offset)
    ).all()
    return {"images": [image_out(i) for i in rows]}


@api.post("/collections/{collection_id}/upload", status_code=201)
async def upload_images(collection_id: str, files: list[UploadFile] = File(...), session: Session = Depends(get_session)):
    if session.get(db.Collection, collection_id) is None:
        raise _404("collection")
    out = []
    for f in files:
        data = await f.read()
        try:
            rel, w, h = images.store_bytes(data, f"library/{collection_id}")
        except Exception as exc:  # noqa: BLE001 - not an image
            raise HTTPException(422, {"error": f"{f.filename}: {exc}", "code": "VALIDATION_ERROR"}) from exc
        img = db.Image(collection_id=collection_id, path=rel, source="upload", width=w, height=h)
        session.add(img)
        session.flush()
        out.append(image_out(img))
    return {"images": out}


@api.post("/collections/{collection_id}/import", status_code=201)
def import_images(collection_id: str, body: ImportImagesRequest, session: Session = Depends(get_session)):
    if session.get(db.Collection, collection_id) is None:
        raise _404("collection")
    try:
        found = images.search(body.query, body.count)
    except images.ImageSearchError as exc:
        raise HTTPException(422, {"error": str(exc), "code": "VALIDATION_ERROR"}) from exc
    out = [image_out(images.download_into(session, p, collection_id, body.query)) for p in found[: body.count]]
    return {"images": out}


@api.delete("/images/{image_id}")
def delete_image(image_id: str, session: Session = Depends(get_session)):
    img = session.get(db.Image, image_id)
    if img is None:
        raise _404("image")
    images.delete_file(img.path)
    session.delete(img)
    return {"deleted": True}


@api.get("/images/search")
def search_images(q: str, page: int = 1):
    try:
        found = images.search(q, 24, page=page)
    except images.ImageSearchError as exc:
        raise HTTPException(422, {"error": str(exc), "code": "VALIDATION_ERROR"}) from exc
    return {"images": [p.__dict__ for p in found]}


# --- products --------------------------------------------------------------------------------


@api.get("/products")
def list_products(session: Session = Depends(get_session)):
    rows = session.scalars(select(db.Product).order_by(db.Product.created_at.desc())).all()
    return {"products": [{"id": p.id, "name": p.name, "description": p.description, "url": p.url} for p in rows]}


@api.post("/products", status_code=201)
def create_product(body: ProductIn, session: Session = Depends(get_session)):
    p = db.Product(**body.model_dump())
    session.add(p)
    session.flush()
    return {"id": p.id, **body.model_dump()}


@api.delete("/products/{product_id}")
def delete_product(product_id: str, session: Session = Depends(get_session)):
    p = session.get(db.Product, product_id)
    if p is None:
        raise _404("product")
    session.delete(p)
    return {"deleted": True}


# --- TikTok ----------------------------------------------------------------------------------

_oauth_states: set[str] = set()


@api.get("/tiktok/accounts")
def tiktok_accounts(session: Session = Depends(get_session)):
    rows = session.scalars(select(db.TikTokAccount).order_by(db.TikTokAccount.created_at)).all()
    return {
        "accounts": [
            {"id": a.id, "username": a.username, "display_name": a.display_name, "avatar_url": a.avatar_url} for a in rows
        ]
    }


@api.get("/tiktok/connect")
def tiktok_connect():
    if not tiktok.configured():
        raise HTTPException(422, {"error": "Set TIKTOK_CLIENT_KEY and TIKTOK_CLIENT_SECRET first.", "code": "VALIDATION_ERROR"})
    url, state = tiktok.authorize_url()
    _oauth_states.add(state)
    return {"url": url}


@api.get("/tiktok/accounts/{account_id}/creator-info")
def tiktok_creator_info(account_id: str, session: Session = Depends(get_session)):
    """What TikTok says this creator can post right now (shown before every direct post)."""
    account = session.get(db.TikTokAccount, account_id)
    if account is None:
        raise _404("account")
    try:
        info = tiktok.creator_info(account)
    except tiktok.TikTokError as exc:
        raise HTTPException(502, {"error": str(exc), "code": "TIKTOK_ERROR"}) from exc
    return {
        "nickname": info.get("creator_nickname") or account.display_name,
        "username": info.get("creator_username") or account.username,
        "avatar_url": info.get("creator_avatar_url") or account.avatar_url,
        "privacy_level_options": info.get("privacy_level_options") or [],
        "comment_disabled": bool(info.get("comment_disabled")),
    }


@api.delete("/tiktok/accounts/{account_id}")
def tiktok_disconnect(account_id: str, session: Session = Depends(get_session)):
    a = session.get(db.TikTokAccount, account_id)
    if a is None:
        raise _404("account")
    session.delete(a)
    return {"deleted": True}


@app.get("/auth/tiktok/callback")
def tiktok_callback(code: str = "", state: str = "", error: str = "", session: Session = Depends(get_session)):
    if error:
        return RedirectResponse(f"/#accounts?error={error}")
    if state not in _oauth_states:
        raise HTTPException(400, "Unknown OAuth state; start the connection again from the dashboard.")
    _oauth_states.discard(state)
    try:
        tiktok.exchange_code(session, code)
    except tiktok.TikTokError as exc:
        return RedirectResponse(f"/#accounts?error={exc}")
    return RedirectResponse("/#accounts")


# --- posts & analytics -----------------------------------------------------------------------


@api.get("/posts")
def list_posts(
    tiktok_account_id: str | None = None,
    sort: str = "recent",
    days: int | None = 30,
    limit: int = Query(50, le=200),
    session: Session = Depends(get_session),
):
    q = select(db.Post)
    if tiktok_account_id:
        q = q.where(db.Post.tiktok_account_id == tiktok_account_id)
    if days:
        q = q.where(db.Post.created_at >= datetime.now(timezone.utc) - timedelta(days=days))
    order = {"views": db.Post.views, "likes": db.Post.likes, "shares": db.Post.shares, "comments": db.Post.comments}.get(sort)
    rows = session.scalars(q.order_by((order or db.Post.created_at).desc())).all()
    stats = {
        "total_posts": len(rows),
        "views": sum(p.views for p in rows),
        "likes": sum(p.likes for p in rows),
        "comments": sum(p.comments for p in rows),
        "shares": sum(p.shares for p in rows),
    }
    out = []
    for p in rows[:limit]:
        item = post_out(p)
        item["title"] = p.slideshow.title
        item["thumb"] = pipeline.media_url(p.slideshow.rendered[0]) if p.slideshow.rendered else None
        out.append(item)
    return {"posts": out, "statistics": stats}


@api.post("/posts/refresh", status_code=202)
def refresh_posts():
    pipeline.executor.submit(pipeline.refresh_posts)
    return {"queued": True}


# --- posting times ---------------------------------------------------------------------------


@api.get("/timing/countries")
def timing_countries() -> dict[str, Any]:
    return {"countries": timing.country_list()}


@api.get("/timing/best")
def timing_best(country: str, posts_per_day: int = Query(3, ge=1, le=6), tiktok_account_id: str | None = None):
    try:
        return timing.best_times(country, posts_per_day, tiktok_account_id)
    except KeyError as exc:
        raise HTTPException(404, {"error": f"unsupported country {country!r}", "code": "NOT_FOUND"}) from exc


def _require_publish_consent(auto_publish: bool, privacy_level: str | None, consent: bool, branded: bool) -> None:
    if not auto_publish:
        return
    if not privacy_level:
        raise HTTPException(422, {"error": "Choose who can see the automatic posts.", "code": "VALIDATION_ERROR"})
    if not consent:
        raise HTTPException(422, {"error": "Agree to TikTok's Music Usage Confirmation to post automatically.", "code": "VALIDATION_ERROR"})
    if branded and privacy_level == "SELF_ONLY":
        raise HTTPException(422, {"error": "Branded content can't be private.", "code": "VALIDATION_ERROR"})


def apply_timing(session: Session, automation: db.Automation, body: ApplyTimingRequest) -> dict:
    plan = timing.best_times(body.country, body.posts_per_day, automation.tiktok_account_id)
    automation.timezone = plan["timezone"]
    automation.target_country = plan["country"]
    post = PostSettings.model_validate(automation.post_settings or {})
    post.auto_post = True
    if body.via_browser:
        post.post_mode, post.browser_action = "BROWSER", body.browser_action
    else:
        post.post_mode = "DIRECT_POST" if body.auto_publish else "MEDIA_UPLOAD"
    if body.auto_publish and not body.via_browser:
        post.visibility = body.privacy_level
        post.consented = True
        post.allow_comments = body.allow_comments
        post.brand_organic = body.brand_organic
        post.branded_content = body.branded_content
    automation.post_settings = post.model_dump()
    automation.paused = False
    _set_schedule(session, automation, plan["crons"])
    return plan


@api.post("/automations/{automation_id}/apply-timing")
def automation_apply_timing(automation_id: str, body: ApplyTimingRequest, session: Session = Depends(get_session)):
    """Schedule an automation at the best times for a country and turn on auto-posting."""
    automation = session.get(db.Automation, automation_id)
    if automation is None:
        raise _404("automation")
    if body.country.upper() not in timing.COUNTRIES:
        raise HTTPException(404, {"error": f"unsupported country {body.country!r}", "code": "NOT_FOUND"})
    if not body.via_browser:
        _require_publish_consent(body.auto_publish, body.privacy_level, body.consent, body.branded_content)
    plan = apply_timing(session, automation, body)
    session.commit()
    scheduler.sync()
    warnings = []
    if body.via_browser:
        per_day, gap = browser_queue.limits()
        if body.posts_per_day > per_day:
            warnings.append(f"The browser poster is capped at {per_day} posts a day (Settings); extra ones wait in the queue.")
        warnings.append("Keep a logged-in tiktok.com tab open with the Slidemill extension, or posts wait in the queue.")
    elif not automation.tiktok_account_id:
        warnings.append("No TikTok account on this automation: slideshows will be made but not posted.")
    if body.auto_publish and not body.via_browser and body.posts_per_day > settings.tiktok_direct_posts_per_day:
        warnings.append(f"TikTok allows {settings.tiktok_direct_posts_per_day} direct posts a day; extra ones go to drafts.")
    return {"automation": automation_out(automation), "plan": plan, "warnings": warnings}


# --- browser extension -------------------------------------------------------------------------

EXTENSION_DIR = STATIC_DIR.parent / "extension"


@api.get("/extension/next")
def extension_next(request: Request, version: str = "", username: str = "", draft: str = "0") -> dict[str, Any]:
    """Called by the browser extension / Android app about once a minute: the next post to make, if any."""
    return browser_queue.claim_next(
        client={"version": version, "username": username, "draft": draft}, base_url=str(request.base_url)
    )


@api.post("/extension/jobs/{post_id}/result")
def extension_result(post_id: str, body: ExtensionResult) -> dict[str, Any]:
    post = browser_queue.record_result(post_id, body.ok, body.item_id, body.username, body.error, body.unsupported)
    if post is None:
        raise HTTPException(404, {"error": "no post in progress with that id", "code": "NOT_FOUND"})
    return {"status": post.status}


@api.get("/extension/status")
def extension_status() -> dict[str, Any]:
    return browser_queue.status()


@api.get("/extension/download")
def extension_download():
    """The extension as a zip, ready for chrome://extensions -> Load unpacked."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(EXTENSION_DIR.rglob("*")):
            if path.is_file():
                zf.write(path, f"slidemill-tiktok-poster/{path.relative_to(EXTENSION_DIR)}")
    return Response(
        buf.getvalue(),
        media_type="application/zip",
        headers={"Content-Disposition": 'attachment; filename="slidemill-tiktok-poster.zip"'},
    )


# --- settings ----------------------------------------------------------------------------------


@api.get("/settings")
def get_settings() -> dict[str, Any]:
    return {
        "values": store.public_view(),
        "llm_providers": {k: {"label": v.label, "default_model": v.default_model, "vision": v.vision} for k, v in llm.PRESETS.items()},
        "image_providers": imagegen.IMAGE_PROVIDERS,
    }


@api.put("/settings")
def put_settings(body: SettingsUpdate) -> dict[str, Any]:
    values = dict(body.values)
    # A pasted LLM key with no explicit provider picks its provider from the key's prefix.
    if values.get("llm_api_key") and not values.get("llm_provider"):
        detected = llm.detect_provider(values["llm_api_key"])
        if detected:
            values["llm_provider"] = detected
    try:
        store.set_many(values)
    except KeyError as exc:
        raise HTTPException(422, {"error": f"unknown setting {exc}", "code": "VALIDATION_ERROR"}) from exc
    return get_settings()


@api.post("/settings/test")
def test_settings() -> dict[str, Any]:
    out: dict[str, Any] = {}
    try:
        out["ai"] = {"ok": True, "detail": llm.test()}
    except llm.LLMError as exc:
        out["ai"] = {"ok": False, "detail": str(exc)}
    out["images"] = {"ok": not imagegen.describe().startswith("unavailable"), "detail": imagegen.describe()}
    return out


# --- jobs -----------------------------------------------------------------------------------


def _new_job(session: Session, kind: str) -> db.Job:
    job = db.Job(kind=kind)
    session.add(job)
    session.flush()
    session.commit()
    return job


@api.get("/jobs/{job_id}")
def get_job(job_id: str, session: Session = Depends(get_session)) -> dict[str, Any]:
    job = session.get(db.Job, job_id)
    if job is None:
        raise _404("job")
    out = {"id": job.id, "kind": job.kind, "status": job.status, "message": job.message, "result": job.result}
    ids = (job.result or {}).get("slideshow_ids") or []
    if ids:
        rows = {s.id: s for s in session.scalars(select(db.Slideshow).where(db.Slideshow.id.in_(ids))).all()}
        out["slideshows"] = [slideshow_out(rows[i]) for i in ids if i in rows]
    return out


# --- studio ------------------------------------------------------------------------------------


@api.post("/studio", status_code=202)
def studio(body: StudioRequest, session: Session = Depends(get_session)) -> dict[str, Any]:
    """Describe what you want; get finished slideshows (text, images, rendering) back."""
    if not llm.configured():
        raise HTTPException(422, {"error": "Add an AI key in Settings first.", "code": "VALIDATION_ERROR"})
    try:
        schedule = [scheduler.validate_cron(c) for c in body.schedule]
    except ValueError as exc:
        raise HTTPException(422, {"error": f"invalid cron: {exc}", "code": "VALIDATION_ERROR"}) from exc
    if not body.via_browser:
        _require_publish_consent(body.auto_publish, body.privacy_level, body.consent, False)
    if body.target_country and body.target_country.upper() not in timing.COUNTRIES:
        raise HTTPException(404, {"error": f"unsupported country {body.target_country!r}", "code": "NOT_FOUND"})
    job = _new_job(session, "studio")
    pipeline.job_executor.submit(
        pipeline.run_studio, job.id, body.brief, body.count, body.image_source, body.aspect_ratio, body.language,
        body.product_id, body.save_as_automation, body.tiktok_account_id, schedule,
        body.target_country, body.posts_per_day, body.auto_publish, body.privacy_level,
        body.browser_action if body.via_browser else None,
    )
    return {"job_id": job.id}


# --- library ------------------------------------------------------------------------------------


def profile_out(p: db.LibraryProfile, posts: list[db.LibraryPost] | None = None) -> dict[str, Any]:
    out = {
        "id": p.id,
        "username": p.username,
        "nickname": p.nickname,
        "avatar": pipeline.media_url(p.avatar_path) if p.avatar_path else None,
        "bio": p.bio,
        "link_in_bio": p.bio_link,
        "followers": p.followers,
        "following_count": p.following,
        "total_likes": p.total_likes,
        "video_count": p.video_count,
        "region": p.region,
        "verified": p.verified,
        "niche": p.niche,
        "product_medium": p.product_medium,
        "url": f"https://www.tiktok.com/@{p.username}",
        "scraped_at": p.scraped_at.isoformat() if p.scraped_at else None,
    }
    if posts is not None:
        out["slideshows"] = [library_post_out(x) for x in posts]
    return out


def library_post_out(x: db.LibraryPost) -> dict[str, Any]:
    return {
        "id": x.id,
        "url": x.url,
        "description": x.description,
        "is_slideshow": x.is_slideshow,
        "images": [pipeline.media_url(i) for i in x.images],
        "cover": pipeline.media_url(x.cover_path) if x.cover_path else None,
        "slide_texts": x.slide_texts,
        "music": x.music,
        "views": x.views,
        "likes": x.likes,
        "comments": x.comments,
        "shares": x.shares,
        "saves": x.saves,
        "posted_at": x.posted_at.isoformat() if x.posted_at else None,
    }


@api.get("/library/niches")
def library_niches(session: Session = Depends(get_session)) -> dict[str, Any]:
    rows = session.execute(
        select(db.LibraryProfile.niche, func.count()).where(db.LibraryProfile.niche != "").group_by(db.LibraryProfile.niche)
        .order_by(func.count().desc())
    ).all()
    return {"niches": [{"niche": n, "profile_count": c} for n, c in rows]}


@api.get("/library")
def library_search(
    q: str = "",
    niche: str = "",
    product_medium: str = "",
    region: str = "",
    sort: str = "followers",
    limit: int = Query(24, le=100),
    offset: int = 0,
    session: Session = Depends(get_session),
) -> dict[str, Any]:
    query = select(db.LibraryProfile)
    if q:
        like = f"%{q.lower()}%"
        query = query.where(
            func.lower(db.LibraryProfile.username).like(like)
            | func.lower(db.LibraryProfile.nickname).like(like)
            | func.lower(db.LibraryProfile.bio).like(like)
            | db.LibraryProfile.id.in_(select(db.LibraryPost.profile_id).where(func.lower(db.LibraryPost.description).like(like)))
        )
    if niche:
        query = query.where(db.LibraryProfile.niche == niche)
    if product_medium:
        query = query.where(db.LibraryProfile.product_medium == product_medium)
    if region:
        query = query.where(db.LibraryProfile.region == region.upper())
    order = {"recent": db.LibraryProfile.scraped_at, "likes": db.LibraryProfile.total_likes}.get(sort, db.LibraryProfile.followers)
    total = session.scalar(select(func.count()).select_from(query.subquery()))
    rows = session.scalars(query.order_by(order.desc()).limit(limit).offset(offset)).all()
    out = []
    for p in rows:
        top = sorted([x for x in p.posts if x.is_slideshow], key=lambda x: -x.views)[:4]
        item = profile_out(p)
        item["slideshow_count"] = sum(1 for x in p.posts if x.is_slideshow)
        item["top_slideshows"] = [library_post_out(x) for x in top]
        out.append(item)
    return {"profiles": out, "total": total}


@api.get("/library/profiles/{profile_id}")
def library_profile(profile_id: str, session: Session = Depends(get_session)) -> dict[str, Any]:
    p = session.get(db.LibraryProfile, profile_id)
    if p is None:
        raise _404("profile")
    posts = sorted(p.posts, key=lambda x: (not x.is_slideshow, -x.views))
    return profile_out(p, posts)


@api.delete("/library/profiles/{profile_id}")
def library_delete(profile_id: str, session: Session = Depends(get_session)) -> dict[str, Any]:
    p = session.get(db.LibraryProfile, profile_id)
    if p is None:
        raise _404("profile")
    for x in p.posts:
        for rel in x.images + ([x.cover_path] if x.cover_path else []):
            images.delete_file(rel)
    session.delete(p)
    return {"deleted": True}


@api.post("/library/import", status_code=202)
def library_import(body: LibraryImportRequest, session: Session = Depends(get_session)) -> dict[str, Any]:
    items = [i for i in body.items if i.strip()]
    if not items:
        raise HTTPException(422, {"error": "Paste at least one TikTok username or URL.", "code": "VALIDATION_ERROR"})
    job = _new_job(session, "library_import")
    pipeline.job_executor.submit(library.import_job, job.id, items, body.max_posts)
    return {"job_id": job.id}


@api.post("/library/discover", status_code=202)
def library_discover(body: LibraryDiscoverRequest, session: Session = Depends(get_session)) -> dict[str, Any]:
    if not library.apify_available():
        raise HTTPException(422, {"error": "Search/hashtag discovery needs an Apify token in Settings.", "code": "VALIDATION_ERROR"})
    job = _new_job(session, "library_discover")
    pipeline.job_executor.submit(library.discover_job, job.id, body.query, body.max_posts)
    return {"job_id": job.id}


@api.post("/library/posts/{post_id}/analyze")
def library_analyze(post_id: str) -> dict[str, Any]:
    try:
        return {"slide_texts": library.analyze_post(post_id)}
    except library.LibraryError as exc:
        raise HTTPException(422, {"error": str(exc), "code": "VALIDATION_ERROR"}) from exc


@api.post("/library/posts/{post_id}/remix", status_code=202)
def library_remix(post_id: str, body: RemixRequest, session: Session = Depends(get_session)) -> dict[str, Any]:
    """Make new slideshows in the format of a library post."""
    post = session.get(db.LibraryPost, post_id)
    if post is None:
        raise _404("post")
    if not post.slide_texts and post.images:
        try:
            library.analyze_post(post_id)
        except library.LibraryError:
            pass  # caption-only reference still works
    reference, default_topic = library.reference_text(post_id)
    topic = body.topic or default_topic
    image_settings = ImageSettings(source=body.image_source)
    ids = []
    for _ in range(body.count):
        show = pipeline.create_pending(session, topic, queue=True)
        ids.append(show.id)
    session.commit()
    for sid in ids:
        pipeline.executor.submit(
            pipeline.generate_into, sid, image_settings=image_settings, aspect_ratio=body.aspect_ratio, reference=reference
        )
    return {"slideshow_ids": ids}


app.include_router(api)


@app.get("/", response_class=HTMLResponse)
def index():
    return FileResponse(STATIC_DIR / "index.html")
