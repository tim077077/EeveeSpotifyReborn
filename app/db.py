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


_connect_args = {"check_same_thread": False} if settings.database_url.startswith("sqlite") else {}
engine = create_engine(settings.database_url, connect_args=_connect_args)
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)


def init_db() -> None:
    Base.metadata.create_all(engine)


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
