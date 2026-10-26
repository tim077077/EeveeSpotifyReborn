"""Slideshow writer: hook/topic + style -> structured slide plan, on whichever AI key is configured."""

from __future__ import annotations

import difflib
import re

from . import llm
from .schemas import CampaignPlan, SlideshowPlan

SYSTEM_PROMPT = """You write viral TikTok photo-carousel slideshows (the "slideshow" post type).

What works on TikTok slideshows:
- Slide 1 is the hook: one punchy line that creates curiosity or names a payoff ("5 habits that quietly fixed my sleep", "things I wish I knew before 25"). It must make people swipe.
- Body slides are a listicle: one idea per slide, a short bold heading plus at most one supporting line. Concrete, specific, a little surprising. Numbered when the hook promises a number, and the count must match.
- Write like a real person posting from their own account, not a brand. No corporate tone, no "In this slideshow".
- Keep on-slide text short: headings under ~8 words, supporting lines under ~20 words. Never put hashtags on slides.
- If a product is given, mention it once, naturally, as one of the list items or a soft final tip (a "soft CTA") - never a hard sell, never a URL on a slide. Mark that slide role "cta" only if it is a dedicated final slide.
- If no product is given, do not invent one, and do not add a CTA slide.

Styling defaults (override whenever the creative direction says otherwise):
- hook: font "tiktok", font_size "large", text_style "outline", text_color "white".
- body heading: font "tiktok", font_size "medium", text_style "outline"; supporting line font_size "small".
- Use "background"/"light_background" text styles when the direction asks for boxed or highlighted text.
- Default text_position "center" unless the direction says top or bottom.

Backgrounds: each slide gets a photo behind the text. image_query is a short stock-photo search; image_prompt describes the same photo for an AI image generator. Keep every slide's background in one consistent visual style (same palette, lighting and photographic feel), leave calm space where the text sits, and never ask for text, letters or logos inside the image."""

CAMPAIGN_PROMPT = """You plan TikTok slideshow content from a short brief written by a creator.

Turn the brief into a reusable format:
- name: short name for this content series.
- format_summary: one line describing the slideshow format.
- style: creative direction for the slide writer - slide count, voice, list structure, fonts and text placement. Pick what performs on TikTok for this niche if the brief doesn't say.
- visual_style: the look of every background photo (subject matter, palette, lighting, camera feel), consistent across the series, suitable for both stock search and AI image generation. No text in images.
- aspect_ratio: "4:5" unless the brief asks otherwise.
- hooks: distinct, scroll-stopping first-slide hooks for separate slideshows in this series. Vary the angle (numbers, mistakes, before/after, "things nobody tells you", POV). No two may be near-duplicates.
- language: the language the slides should be written in (the brief's language unless it says otherwise)."""


class GenerationError(RuntimeError):
    pass


def fingerprint(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def too_similar(candidate: str, previous: list[str], threshold: float = 0.86) -> bool:
    fp = fingerprint(candidate)
    return any(difflib.SequenceMatcher(None, fp, p).ratio() >= threshold for p in previous if p)


def build_prompt(
    topic: str,
    style: str = "",
    language: str = "English",
    product: dict | None = None,
    recent_titles: list[str] | None = None,
    slide_count_hint: int | None = None,
    visual_style: str = "",
    reference: str = "",
) -> str:
    parts = [f"<topic>\n{topic}\n</topic>"]
    if style:
        parts.append(f"<creative_direction>\n{style}\n</creative_direction>")
    if visual_style:
        parts.append(f"<visual_style>\n{visual_style}\n</visual_style>")
    if reference:
        parts.append(
            "<reference_slideshow>\nA slideshow that performed well. Copy its format, pacing and tone, but write entirely "
            f"new text (never reuse its sentences):\n{reference}\n</reference_slideshow>"
        )
    if product:
        parts.append(
            "<product>\n"
            f"name: {product.get('name', '')}\n"
            f"description: {product.get('description', '')}\n"
            "</product>"
        )
    if slide_count_hint:
        parts.append(f"Make exactly {slide_count_hint} slides including the hook.")
    if recent_titles:
        listed = "\n".join(f"- {t}" for t in recent_titles[:30])
        parts.append(
            "These slideshows were already made for this account. Make this one clearly different "
            f"(new angle, new hook wording, new list items):\n{listed}"
        )
    parts.append(f"Write all on-slide text, the caption and the description in {language}.")
    return "\n\n".join(parts)


def generate_plan(
    topic: str,
    style: str = "",
    language: str = "English",
    product: dict | None = None,
    recent_titles: list[str] | None = None,
    slide_count_hint: int | None = None,
    visual_style: str = "",
    reference: str = "",
) -> SlideshowPlan:
    prompt = build_prompt(topic, style, language, product, recent_titles, slide_count_hint, visual_style, reference)
    recent_fps = [fingerprint(t) for t in recent_titles or []]
    plan: SlideshowPlan | None = None
    for attempt in range(2):
        try:
            plan = llm.complete_json(SYSTEM_PROMPT, prompt, SlideshowPlan)
        except llm.LLMError as exc:
            raise GenerationError(str(exc)) from exc
        if not plan.slides:
            raise GenerationError("The model returned no slides.")
        hook_text = " ".join(t.text for t in plan.slides[0].text_items)
        if attempt == 0 and too_similar(hook_text, recent_fps):
            prompt += (
                f'\n\nYour previous draft hook "{hook_text}" is too close to an existing post. '
                "Use a completely different hook and list."
            )
            continue
        break
    assert plan is not None
    plan.caption = plan.caption[:89]
    return plan


def plan_campaign(brief: str, hook_count: int) -> CampaignPlan:
    prompt = f"<brief>\n{brief}\n</brief>\n\nWrite exactly {hook_count} hooks."
    try:
        campaign = llm.complete_json(CAMPAIGN_PROMPT, prompt, CampaignPlan)
    except llm.LLMError as exc:
        raise GenerationError(str(exc)) from exc
    if not campaign.hooks:
        raise GenerationError("The model returned no hooks.")
    return campaign
