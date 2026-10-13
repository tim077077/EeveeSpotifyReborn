"""Persistence: SQLAlchemy models and session helpers."""

from __future__ import annotations

import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Any, Iterator

from sqlalchemy import JSON, Boolean, DateTime, ForeignKey, Integer, String, Text, create_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, relationship, sessionmaker

from .config import settings


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def new_id() -> str:
    return uuid.uuid4().hex[:16]


class Base(DeclarativeBase):
    type_annotation_map = {dict[str, Any]: JSON, list[Any]: JSON}


class TikTokAccount(Base):
    __tablename__ = "tiktok_accounts"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    open_id: Mapped[str] = mapped_column(String(128), unique=True)
    username: Mapped[str] = mapped_column(String(128), default="")
    display_name: Mapped[str] = mapped_column(String(256), default="")
    avatar_url: Mapped[str] = mapped_column(Text, default="")
    access_token: Mapped[str] = mapped_column(Text)
    refresh_token: Mapped[str] = mapped_column(Text, default="")
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    refresh_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Product(Base):
    __tablename__ = "products"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(String(256))
    description: Mapped[str] = mapped_column(Text, default="")
    url: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Collection(Base):
    __tablename__ = "collections"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(String(256))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    images: Mapped[list["Image"]] = relationship(back_populates="collection", cascade="all, delete-orphan")


class Image(Base):
    __tablename__ = "images"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    collection_id: Mapped[str | None] = mapped_column(ForeignKey("collections.id", ondelete="CASCADE"))
    # Path relative to the media directory.
    path: Mapped[str] = mapped_column(Text)
    source: Mapped[str] = mapped_column(String(32), default="upload")
    source_url: Mapped[str] = mapped_column(Text, default="")
    query: Mapped[str] = mapped_column(Text, default="")
    width: Mapped[int] = mapped_column(Integer, default=0)
    height: Mapped[int] = mapped_column(Integer, default=0)
    use_count: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    collection: Mapped[Collection | None] = relationship(back_populates="images")


class Automation(Base):
    __tablename__ = "automations"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    title: Mapped[str] = mapped_column(String(256), default="Untitled automation")
    tiktok_account_id: Mapped[str | None] = mapped_column(ForeignKey("tiktok_accounts.id", ondelete="SET NULL"))
    product_id: Mapped[str | None] = mapped_column(ForeignKey("products.id", ondelete="SET NULL"))
    # Topic templates rotated across runs ("5 habits that ...").
    hooks: Mapped[list[Any]] = mapped_column(default=list)
    # Free-form creative direction: tone, slide count, fonts, text placement.
    style: Mapped[str] = mapped_column(Text, default="")
    language: Mapped[str] = mapped_column(String(64), default="English")
    post_settings: Mapped[dict[str, Any]] = mapped_column(default=dict)
    image_settings: Mapped[dict[str, Any]] = mapped_column(default=dict)
    # Schedule crons are interpreted in this IANA timezone ("" = server timezone).
    timezone: Mapped[str] = mapped_column(String(64), default="")
    # ISO country code the posting times were optimized for, if any.
    target_country: Mapped[str] = mapped_column(String(8), default="")
    paused: Mapped[bool] = mapped_column(Boolean, default=False)
    favorite: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    schedules: Mapped[list["ScheduleJob"]] = relationship(back_populates="automation", cascade="all, delete-orphan")


class ScheduleJob(Base):
    __tablename__ = "schedule_jobs"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    automation_id: Mapped[str] = mapped_column(ForeignKey("automations.id", ondelete="CASCADE"))
    cron: Mapped[str] = mapped_column(String(128))
    automation: Mapped[Automation] = relationship(back_populates="schedules")


class Slideshow(Base):
    __tablename__ = "slideshows"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    automation_id: Mapped[str | None] = mapped_column(ForeignKey("automations.id", ondelete="SET NULL"))
    created_by_cron_id: Mapped[str | None] = mapped_column(String(32))
    title: Mapped[str] = mapped_column(String(256), default="Untitled slideshow")
    prompt: Mapped[str] = mapped_column(Text, default="")
    # draft | generating | rendering | completed | failed
    status: Mapped[str] = mapped_column(String(32), default="draft")
    # Full render spec (see schemas.SlideshowSpec); editable from the UI.
    spec: Mapped[dict[str, Any]] = mapped_column(default=dict)
    caption: Mapped[str] = mapped_column(Text, default="")
    description: Mapped[str] = mapped_column(Text, default="")
    rendered: Mapped[list[Any]] = mapped_column(default=list)
    video_path: Mapped[str] = mapped_column(Text, default="")
    fingerprint: Mapped[str] = mapped_column(Text, default="")
    error: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    posts: Mapped[list["Post"]] = relationship(back_populates="slideshow", cascade="all, delete-orphan")


class Post(Base):
    __tablename__ = "posts"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    slideshow_id: Mapped[str] = mapped_column(ForeignKey("slideshows.id", ondelete="CASCADE"))
    tiktok_account_id: Mapped[str | None] = mapped_column(ForeignKey("tiktok_accounts.id", ondelete="SET NULL"))
    post_mode: Mapped[str] = mapped_column(String(32))
    publish_id: Mapped[str] = mapped_column(String(128), default="")
    # pending | processing | published | sent_to_drafts | failed
    status: Mapped[str] = mapped_column(String(32), default="pending")
    post_id: Mapped[str] = mapped_column(String(128), default="")
    error: Mapped[str] = mapped_column(Text, default="")
    views: Mapped[int] = mapped_column(Integer, default=0)
    likes: Mapped[int] = mapped_column(Integer, default=0)
    comments: Mapped[int] = mapped_column(Integer, default=0)
    shares: Mapped[int] = mapped_column(Integer, default=0)
    metrics_updated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    slideshow: Mapped[Slideshow] = relationship(back_populates="posts")


class Setting(Base):
    """Key/value settings editable from the dashboard (API keys, provider choices)."""

    __tablename__ = "settings"

    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[str] = mapped_column(Text, default="")


class LibraryProfile(Base):
    """A public TikTok account tracked in the slideshow library."""

    __tablename__ = "library_profiles"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    username: Mapped[str] = mapped_column(String(128), unique=True)
    tiktok_user_id: Mapped[str] = mapped_column(String(64), default="")
    nickname: Mapped[str] = mapped_column(String(256), default="")
    avatar_path: Mapped[str] = mapped_column(Text, default="")
    bio: Mapped[str] = mapped_column(Text, default="")
    bio_link: Mapped[str] = mapped_column(Text, default="")
    followers: Mapped[int] = mapped_column(Integer, default=0)
    following: Mapped[int] = mapped_column(Integer, default=0)
    total_likes: Mapped[int] = mapped_column(Integer, default=0)
    video_count: Mapped[int] = mapped_column(Integer, default=0)
    region: Mapped[str] = mapped_column(String(16), default="")
    verified: Mapped[bool] = mapped_column(Boolean, default=False)
    niche: Mapped[str] = mapped_column(String(64), default="")
    # What the account promotes, e.g. "app", "physical product", "creator", "none".
    product_medium: Mapped[str] = mapped_column(String(64), default="")
    scraped_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    posts: Mapped[list["LibraryPost"]] = relationship(back_populates="profile", cascade="all, delete-orphan")


class LibraryPost(Base):
    __tablename__ = "library_posts"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)  # TikTok item id
    profile_id: Mapped[str] = mapped_column(ForeignKey("library_profiles.id", ondelete="CASCADE"))
    url: Mapped[str] = mapped_column(Text, default="")
    description: Mapped[str] = mapped_column(Text, default="")
    is_slideshow: Mapped[bool] = mapped_column(Boolean, default=False)
    # Local copies (media-relative); TikTok CDN links expire within hours.
    images: Mapped[list[Any]] = mapped_column(default=list)
    cover_path: Mapped[str] = mapped_column(Text, default="")
    # Text read off each slide by a vision model, when analyzed.
    slide_texts: Mapped[list[Any]] = mapped_column(default=list)
    music: Mapped[str] = mapped_column(Text, default="")
    views: Mapped[int] = mapped_column(Integer, default=0)
    likes: Mapped[int] = mapped_column(Integer, default=0)
    comments: Mapped[int] = mapped_column(Integer, default=0)
    shares: Mapped[int] = mapped_column(Integer, default=0)
    saves: Mapped[int] = mapped_column(Integer, default=0)
    posted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    scraped_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    profile: Mapped[LibraryProfile] = relationship(back_populates="posts")


class Job(Base):
    """Background task with a pollable status (library scrapes, studio batches)."""

    __tablename__ = "jobs"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    kind: Mapped[str] = mapped_column(String(32))
    # queued | running | completed | failed
    status: Mapped[str] = mapped_column(String(16), default="queued")
    message: Mapped[str] = mapped_column(Text, default="")
    result: Mapped[dict[str, Any]] = mapped_column(default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


_connect_args = {"check_same_thread": False} if settings.database_url.startswith("sqlite") else {}
engine = create_engine(settings.database_url, connect_args=_connect_args)
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)


def init_db() -> None:
    Base.metadata.create_all(engine)
    _add_missing_columns()


def _add_missing_columns() -> None:
    """Additive migration: create columns added to models after a table already existed."""
    from sqlalchemy import inspect, text

    inspector = inspect(engine)
    with engine.begin() as conn:
        for table in Base.metadata.sorted_tables:
            if not inspector.has_table(table.name):
                continue
            existing = {c["name"] for c in inspector.get_columns(table.name)}
            for column in table.columns:
                if column.name in existing:
                    continue
                ddl_type = column.type.compile(dialect=engine.dialect)
                default = column.default.arg if column.default is not None and not callable(column.default.arg) else None
                clause = ""
                if isinstance(default, bool):
                    clause = f" DEFAULT {int(default)}"
                elif isinstance(default, (int, float)):
                    clause = f" DEFAULT {default}"
                elif isinstance(default, str):
                    clause = " DEFAULT '" + default.replace("'", "''") + "'"
                conn.execute(text(f'ALTER TABLE {table.name} ADD COLUMN "{column.name}" {ddl_type}{clause}'))


@contextmanager
def session_scope() -> Iterator[Session]:
    session = SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
