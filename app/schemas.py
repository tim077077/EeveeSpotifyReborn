"""Shared data shapes: the render spec, the AI plan, and API request bodies."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

AspectRatio = Literal["4:5", "9:16", "1:1", "16:9"]
TextPosition = Literal["top", "center", "bottom"]
FontName = Literal["tiktok", "bebas", "anton", "archivo", "cormorant", "cormorant_italic", "playfair_italic"]
FontSize = Literal["extra_extra_small", "extra_small", "small", "medium", "large", "extra_large"]
TextStyle = Literal["outline", "text", "background", "light_background"]
TextColor = Literal["white", "black", "yellow", "red", "pink", "orange", "green", "blue", "purple"]
TextWidth = Literal["full", "100%", "80%", "50%"]
ImageLayout = Literal["single", "1:2", "1:3", "2:1", "2:2"]
SlideRole = Literal["hook", "body", "cta"]


class TextItem(BaseModel):
    text: str
    font: FontName = "tiktok"
    font_size: FontSize | str = "medium"  # named size, or raw "48px"
    text_style: TextStyle = "outline"
    text_color: TextColor = "white"
    text_width: TextWidth = "80%"
    text_align: Literal["left", "center", "right"] = "center"


class Slide(BaseModel):
    role: SlideRole = "body"
    # Image references are media-relative paths ("library/abc.jpg") or http(s) URLs.
    image: str | None = None
    images: list[str] = Field(default_factory=list)
    image_layout: ImageLayout = "single"
    text_items: list[TextItem] = Field(default_factory=list)
    text_position: TextPosition | None = None
    # Search phrase used to source a background when no image is set.
    image_query: str = ""


class SlideshowSpec(BaseModel):
    title: str = "Untitled slideshow"
    aspect_ratio: AspectRatio = "4:5"
    text_position: TextPosition = "center"
    overlay_body: bool = False
    overlay_hook: bool = False
    overlay_cta: bool = False
    background_opacity: int = Field(default=20, ge=0, le=100)
    keep_original_aspect_ratio: bool = False
    slides: list[Slide] = Field(default_factory=list, max_length=20)


# --- AI plan (what Claude returns) -------------------------------------------------


class PlanTextItem(BaseModel):
    text: str = Field(description="Exact on-slide text. Short lines; no hashtags; no emojis unless the style asks.")
    font: FontName
    font_size: FontSize
    text_style: TextStyle
    text_color: TextColor


class PlanSlide(BaseModel):
    role: SlideRole
    text_items: list[PlanTextItem]
    image_query: str = Field(description="3-6 word stock-photo search phrase for this slide's background, aesthetic and specific.")


class SlideshowPlan(BaseModel):
    title: str = Field(description="Internal title for the dashboard, under 60 characters.")
    caption: str = Field(description="TikTok post title, at most 89 characters.")
    description: str = Field(description="TikTok post description: one or two sentences plus 3-5 relevant hashtags.")
    text_position: TextPosition
    slides: list[PlanSlide]


# --- API bodies ---------------------------------------------------------------------


class GenerateRequest(BaseModel):
    prompt: str = Field(description="Topic, slide count, tone, fonts, text placement - in plain language.")
    automation_id: str | None = None
    images: list[str] = Field(default_factory=list, description="Background images mapped by index to slides.")
    aspect_ratio: AspectRatio | None = None
    language: str | None = None


class PostSettings(BaseModel):
    caption_mode: Literal["prompt", "static"] = "prompt"
    caption_static: str = ""
    description_mode: Literal["prompt", "static"] = "prompt"
    description_static: str = ""
    auto_post: bool = True
    post_mode: Literal["DIRECT_POST", "MEDIA_UPLOAD"] = "MEDIA_UPLOAD"
    visibility: Literal["PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "FOLLOWER_OF_CREATOR", "SELF_ONLY"] = "PUBLIC_TO_EVERYONE"
    auto_music: bool = True
    allow_comments: bool = True
    disclose_ai_content: bool = True


class ImageSettings(BaseModel):
    # "auto" searches a stock provider per slide; "collection" draws from collection_id.
    source: Literal["auto", "collection"] = "auto"
    collection_id: str | None = None
    hook_collection_id: str | None = None
    cta_collection_id: str | None = None
    aspect_ratio: AspectRatio = "4:5"
    overlay_body: bool = False
    overlay_hook: bool = False
    overlay_cta: bool = False
    background_opacity: int = Field(default=30, ge=0, le=100)
    keep_original_aspect_ratio: bool = False
    text_on_first_slide_only: bool = False
    cta_slide: bool = False


class AutomationIn(BaseModel):
    title: str = "Untitled automation"
    tiktok_account_id: str | None = None
    product_id: str | None = None
    hooks: list[str] = Field(default_factory=list)
    style: str = ""
    language: str = "English"
    post_settings: PostSettings = Field(default_factory=PostSettings)
    image_settings: ImageSettings = Field(default_factory=ImageSettings)
    schedule: list[str] = Field(default_factory=list, description="Cron expressions in the server timezone.")


class AutomationPatch(BaseModel):
    title: str | None = None
    tiktok_account_id: str | None = None
    product_id: str | None = None
    hooks: list[str] | None = None
    style: str | None = None
    language: str | None = None
    post_settings: PostSettings | None = None
    image_settings: ImageSettings | None = None
    schedule: list[str] | None = None
    paused: bool | None = None
    favorite: bool | None = None


class RunRequest(BaseModel):
    hook: str | None = None
    mode: Literal["export", "draft_only"] = "export"


class PublishRequest(BaseModel):
    tiktok_account_id: str | None = None
    post_mode: Literal["DIRECT_POST", "MEDIA_UPLOAD"] | None = None
    caption: str | None = None
    description: str | None = None


class ProductIn(BaseModel):
    name: str
    description: str = ""
    url: str = ""


class CollectionIn(BaseModel):
    name: str


class ImportImagesRequest(BaseModel):
    query: str
    count: int = Field(default=12, ge=1, le=40)
