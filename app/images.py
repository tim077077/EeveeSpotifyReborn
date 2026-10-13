"""Background image sourcing: stock-photo search, downloads into collections, and picking."""

from __future__ import annotations

import io
import random
from dataclasses import dataclass

import httpx
from PIL import Image as PILImage
from PIL import ImageOps
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import db, imagegen, store
from .config import settings


@dataclass
class StockPhoto:
    url: str  # full-resolution download URL
    thumb: str
    width: int
    height: int
    source: str
    credit: str = ""


class ImageSearchError(RuntimeError):
    pass


def providers() -> list[str]:
    out = []
    if store.get("pexels_api_key"):
        out.append("pexels")
    if store.get("unsplash_access_key"):
        out.append("unsplash")
    return out


def search(query: str, count: int = 12, orientation: str = "portrait", page: int = 1) -> list[StockPhoto]:
    """Search the first configured stock provider."""
    available = providers()
    if not available:
        raise ImageSearchError("No image provider configured. Set PEXELS_API_KEY or UNSPLASH_ACCESS_KEY.")
    if available[0] == "pexels":
        return _pexels(query, count, orientation, page)
    return _unsplash(query, count, orientation, page)


def _pexels(query: str, count: int, orientation: str, page: int) -> list[StockPhoto]:
    resp = httpx.get(
        "https://api.pexels.com/v1/search",
        params={"query": query, "per_page": min(count, 80), "orientation": orientation, "page": page},
        headers={"Authorization": store.get("pexels_api_key")},
        timeout=20,
    )
    resp.raise_for_status()
    return [
        StockPhoto(
            url=p["src"].get("large2x") or p["src"]["original"],
            thumb=p["src"]["medium"],
            width=p["width"],
            height=p["height"],
            source="pexels",
            credit=p.get("photographer", ""),
        )
        for p in resp.json().get("photos", [])
    ]


def _unsplash(query: str, count: int, orientation: str, page: int) -> list[StockPhoto]:
    resp = httpx.get(
        "https://api.unsplash.com/search/photos",
        params={"query": query, "per_page": min(count, 30), "orientation": orientation, "page": page},
        headers={"Authorization": f"Client-ID {store.get('unsplash_access_key')}"},
        timeout=20,
    )
    resp.raise_for_status()
    return [
        StockPhoto(
            url=r["urls"]["regular"],
            thumb=r["urls"]["small"],
            width=r["width"],
            height=r["height"],
            source="unsplash",
            credit=r.get("user", {}).get("name", ""),
        )
        for r in resp.json().get("results", [])
    ]


def store_bytes(data: bytes, folder: str) -> tuple[str, int, int]:
    """Normalize to JPEG under media/<folder>; returns (relative path, width, height)."""
    img = ImageOps.exif_transpose(PILImage.open(io.BytesIO(data))).convert("RGB")
    img.thumbnail((2160, 2700))
    name = f"{db.new_id()}.jpg"
    target = settings.media_dir / folder / name
    target.parent.mkdir(parents=True, exist_ok=True)
    img.save(target, "JPEG", quality=90)
    return f"{folder}/{name}", img.width, img.height


def download_into(session: Session, photo: StockPhoto, collection_id: str | None, query: str = "") -> db.Image:
    resp = httpx.get(photo.url, timeout=30, follow_redirects=True)
    resp.raise_for_status()
    rel, w, h = store_bytes(resp.content, f"library/{collection_id or 'auto'}")
    image = db.Image(
        collection_id=collection_id, path=rel, source=photo.source, source_url=photo.url, query=query, width=w, height=h
    )
    session.add(image)
    session.flush()
    return image


def pick_from_collection(session: Session, collection_id: str, exclude: set[str]) -> db.Image | None:
    """Least-used image first so a collection is exhausted before anything repeats."""
    rows = session.scalars(select(db.Image).where(db.Image.collection_id == collection_id)).all()
    candidates = [r for r in rows if r.id not in exclude] or list(rows)
    if not candidates:
        return None
    low = min(r.use_count for r in candidates)
    choice = random.choice([r for r in candidates if r.use_count == low])
    choice.use_count += 1
    return choice


def auto_image(session: Session, query: str, orientation: str, used_urls: set[str]) -> db.Image | None:
    """Search stock photos for a slide and cache the pick in the 'auto' pool."""
    try:
        results = search(query, count=15, orientation=orientation)
    except (ImageSearchError, httpx.HTTPError):
        return None
    fresh = [p for p in results if p.url not in used_urls]
    # Reuse beyond the first few results keeps slideshows from looking identical.
    pool = fresh[:8] or results[:8]
    if not pool:
        return None
    photo = random.choice(pool)
    used_urls.add(photo.url)
    existing = session.scalars(select(db.Image).where(db.Image.source_url == photo.url)).first()
    if existing:
        existing.use_count += 1
        return existing
    image = download_into(session, photo, None, query)
    image.use_count = 1
    return image


def ai_image(session: Session, prompt: str, aspect_ratio: str, visual_style: str = "") -> db.Image:
    """Generate a background with the configured image model and keep it in the 'ai' pool."""
    data = imagegen.generate(prompt, aspect_ratio, visual_style)
    rel, w, h = store_bytes(data, "library/ai")
    image = db.Image(collection_id=None, path=rel, source="ai", query=prompt[:500], width=w, height=h, use_count=1)
    session.add(image)
    session.flush()
    return image


def delete_file(rel: str) -> None:
    path = (settings.media_dir / rel).resolve()
    if settings.media_dir.resolve() in path.parents and path.is_file():
        path.unlink()


def orientation_for(aspect_ratio: str) -> str:
    return {"16:9": "landscape", "1:1": "square"}.get(aspect_ratio, "portrait")

