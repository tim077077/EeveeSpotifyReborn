"""FastAPI app: REST API under /api/v1, the dashboard at /, rendered media at /media."""

from __future__ import annotations

import logging
import os
import secrets
from datetime import datetime, timedelta, timezone
from contextlib import asynccontextmanager
from typing import Any

from fastapi import APIRouter, Depends, FastAPI, File, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import db, images, pipeline, scheduler, tiktok
from .config import STATIC_DIR, settings
from .schemas import (
    AutomationIn,
    AutomationPatch,
    CollectionIn,
    GenerateRequest,
    ImageSettings,
    ImportImagesRequest,
    PostSettings,
    ProductIn,
    PublishRequest,
    RunRequest,
    SlideshowSpec,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init_db()
    if settings.run_scheduler:
        scheduler.start()
    yield
    scheduler.shutdown()


app = FastAPI(title="Slidemill", version="0.1.0", lifespan=lifespan)
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
        out["spec"] = s.spec
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
        "paused": a.paused,
        "favorite": a.favorite,
        "schedule": [{"job_id": j.id, "cron": j.cron} for j in a.schedules],
        "next_runs": scheduler.next_runs(a.id),
        "created_at": a.created_at.isoformat(),
    }


def image_out(i: db.Image) -> dict[str, Any]:
    return {"id": i.id, "path": i.path, "url": pipeline.media_url(i.path), "source": i.source, "use_count": i.use_count}


# --- status ------------------------------------------------------------------------------


@api.get("/status")
def status() -> dict[str, Any]:
    return {
        "anthropic": bool(os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN")),
        "model": settings.anthropic_model,
        "image_providers": images.providers(),
        "tiktok": tiktok.configured(),
        "public_base_url": settings.public_base_url,
        "timezone": settings.timezone,
    }


# --- slideshows ----------------------------------------------------------------------------


@api.post("/slideshows/generate", status_code=201)
def generate(body: GenerateRequest, session: Session = Depends(get_session)) -> dict[str, Any]:
    kwargs: dict[str, Any] = {"explicit_images": body.images, "aspect_ratio": body.aspect_ratio}
    style = ""
    if body.automation_id:
        automation = session.get(db.Automation, body.automation_id)
        if automation is None:
            raise _404("automation")
        style = automation.style
        kwargs["image_settings"] = ImageSettings.model_validate(automation.image_settings or {})
        kwargs["language"] = body.language or automation.language
    else:
        kwargs["language"] = body.language or "English"
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
    if not account_id:
        raise HTTPException(422, {"error": "tiktok_account_id is required", "code": "VALIDATION_ERROR"})
    session.commit()
    try:
        post_id = pipeline.publish(
            slideshow_id, account_id, post_settings, post_mode=body.post_mode, caption=body.caption, description=body.description
        )
    except pipeline.PublishLimitError as exc:
        raise HTTPException(429, {"error": str(exc), "code": "RATE_LIMITED"}) from exc
    except pipeline.PipelineError as exc:
        raise HTTPException(422, {"error": str(exc), "code": "VALIDATION_ERROR"}) from exc
    with db.session_scope() as s2:
        return post_out(s2.get(db.Post, post_id))


# --- automations ---------------------------------------------------------------------------


def _set_schedule(session: Session, automation: db.Automation, crons: list[str]) -> None:
    try:
        crons = [scheduler.validate_cron(c) for c in crons]
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
    )
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


app.include_router(api)


@app.get("/", response_class=HTMLResponse)
def index():
    return FileResponse(STATIC_DIR / "index.html")
