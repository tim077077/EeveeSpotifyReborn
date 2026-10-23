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
BrowserAction = Literal["publish", "draft"]
PrivacyLevel = Literal["PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "FOLLOWER_OF_CREATOR", "SELF_ONLY"]


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
    # Search phrase / generation prompt used to source a background when no image is set.
    image_query: str = ""
    image_prompt: str = ""


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
    image_prompt: str = Field(
        description="One-sentence description of this slide's background photo for an AI image generator: subject, setting, "
        "lighting, framing. Never include text, captions, letters or logos in the image."
    )


class SlideshowPlan(BaseModel):
    title: str = Field(description="Internal title for the dashboard, under 60 characters.")
    caption: str = Field(description="TikTok post title, at most 89 characters.")
    description: str = Field(description="TikTok post description: one or two sentences plus 3-5 relevant hashtags.")
    text_position: TextPosition
    slides: list[PlanSlide]


class CampaignPlan(BaseModel):
    name: str
    format_summary: str
    style: str
    visual_style: str
    aspect_ratio: AspectRatio
    hooks: list[str]
    language: str


class SlideTextRead(BaseModel):
    """Text a vision model read off one library slide."""

    slides: list[str] = Field(description="The visible overlay text of each image, in order. Empty string if none.")


class ProfileClassification(BaseModel):
    niche: str = Field(description="One or two lowercase words, e.g. fitness, skincare, personal finance, studying, relationships.")
    product_medium: Literal["app", "physical product", "digital product", "service", "creator", "none"]


# --- API bodies ---------------------------------------------------------------------


class GenerateRequest(BaseModel):
    prompt: str = Field(description="Topic, slide count, tone, fonts, text placement - in plain language.")
    image_source: Literal["auto", "stock", "ai"] | None = None
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
    # BROWSER = posted by the Slidemill browser extension from your logged-in tiktok.com tab.
    post_mode: Literal["DIRECT_POST", "MEDIA_UPLOAD", "BROWSER"] = "MEDIA_UPLOAD"
    # Browser posts: publish right away, or save to TikTok drafts.
    browser_action: BrowserAction = "publish"
    visibility: Literal["PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "FOLLOWER_OF_CREATOR", "SELF_ONLY"] = "PUBLIC_TO_EVERYONE"
    auto_music: bool = True
    allow_comments: bool = True
    disclose_ai_content: bool = True
    # Commercial content disclosure (TikTok requires this to be asked, off by default).
    brand_organic: bool = False  # promoting your own business
    branded_content: bool = False  # paid partnership / promoting a third party
    # The account owner agreed to TikTok's Music Usage Confirmation (and Branded Content Policy if
    # branded) for unattended direct posts. Without it, scheduled posts go to drafts instead.
    consented: bool = False


class ImageSettings(BaseModel):
    # "auto": stock photos when a stock key is set, otherwise AI-generated.
    # "stock" / "ai" force one; "collection" draws from collection_id.
    source: Literal["auto", "stock", "ai", "collection"] = "auto"
    # Look of every background (fed to AI image generation and the slide writer).
    visual_style: str = ""
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
    schedule: list[str] = Field(default_factory=list, description="Cron expressions in `timezone`.")
    timezone: str = Field(default="", description="IANA timezone for the schedule; blank = server timezone.")
    target_country: str = ""


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
    timezone: str | None = None
    target_country: str | None = None
    paused: bool | None = None
    favorite: bool | None = None


class StudioRequest(BaseModel):
    brief: str = Field(description="What kind of slideshows you want, in plain language.")
    count: int = Field(default=3, ge=1, le=10)
    image_source: Literal["auto", "stock", "ai"] = "auto"
    aspect_ratio: AspectRatio | None = None
    language: str | None = None
    product_id: str | None = None
    # Optionally keep going: save the format as an automation that posts on a schedule.
    save_as_automation: bool = False
    tiktok_account_id: str | None = None
    schedule: list[str] = Field(default_factory=list)
    # When set, the saved automation posts at that country's best times (overrides `schedule`).
    target_country: str | None = None
    posts_per_day: int = Field(default=2, ge=1, le=6)
    auto_publish: bool = False
    privacy_level: PrivacyLevel | None = None
    consent: bool = False
    # Post through the browser extension instead of the TikTok API.
    via_browser: bool = False
    browser_action: BrowserAction = "publish"


class RemixRequest(BaseModel):
    count: int = Field(default=1, ge=1, le=5)
    topic: str | None = Field(default=None, description="New topic; defaults to the same subject.")
    image_source: Literal["auto", "stock", "ai"] = "auto"
    aspect_ratio: AspectRatio | None = None


class LibraryImportRequest(BaseModel):
    items: list[str] = Field(description="TikTok usernames (@name), profile URLs or post URLs.")
    max_posts: int = Field(default=30, ge=1, le=200)


class LibraryDiscoverRequest(BaseModel):
    query: str = Field(description="Search phrase or #hashtag.")
    max_posts: int = Field(default=50, ge=1, le=300)


class SettingsUpdate(BaseModel):
    values: dict[str, str]


class ApplyTimingRequest(BaseModel):
    country: str
    posts_per_day: int = Field(default=3, ge=1, le=6)
    # True: publish by itself at those hours. False: send each one to TikTok drafts.
    auto_publish: bool = True
    # Required for auto_publish: who can see the posts, and the owner's consent (TikTok rules).
    privacy_level: PrivacyLevel | None = None
    consent: bool = False
    allow_comments: bool = False
    brand_organic: bool = False
    branded_content: bool = False
    # Post through the browser extension instead of the TikTok API (no consent screen involved).
    via_browser: bool = False
    browser_action: BrowserAction = "publish"


class ExtensionResult(BaseModel):
    ok: bool
    item_id: str = ""
    username: str = ""
    error: str = ""
    # The extension can't do this yet (e.g. drafts before the request is captured): keep it queued.
    unsupported: bool = False


class RunRequest(BaseModel):
    hook: str | None = None
    mode: Literal["export", "draft_only"] = "export"


class PublishRequest(BaseModel):
    tiktok_account_id: str | None = None
    post_mode: Literal["DIRECT_POST", "MEDIA_UPLOAD", "BROWSER"] | None = None
    caption: str | None = None
    description: str | None = None
    browser_action: BrowserAction = "publish"
    sound: bool = True
    # Direct posts: the user must pick these explicitly (TikTok content-sharing guidelines).
    privacy_level: PrivacyLevel | None = None
    allow_comments: bool | None = None
    brand_organic: bool = False
    branded_content: bool = False
    consent: bool = False


class ProductIn(BaseModel):
    name: str
    description: str = ""
    url: str = ""


class CollectionIn(BaseModel):
    name: str


class ImportImagesRequest(BaseModel):
    query: str
    count: int = Field(default=12, ge=1, le=40)
