"""Claude-backed slideshow writer: hook/topic + style -> structured slide plan."""

from __future__ import annotations

import difflib
import re

import anthropic

from .config import settings
from .schemas import SlideshowPlan

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

image_query: a stock-photo search phrase describing a background that fits the slide's mood (aesthetic, no text in the image, e.g. "cozy bedroom warm lamp night"). Keep queries visually consistent across slides."""


class GenerationError(RuntimeError):
    pass


_client: anthropic.Anthropic | None = None


def client() -> anthropic.Anthropic:
    global _client
    if _client is None:
        _client = anthropic.Anthropic()
    return _client


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
) -> str:
    parts = [f"<topic>\n{topic}\n</topic>"]
    if style:
        parts.append(f"<creative_direction>\n{style}\n</creative_direction>")
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
            "These slideshows were already posted on this account. Make this one clearly different "
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
) -> SlideshowPlan:
    prompt = build_prompt(topic, style, language, product, recent_titles, slide_count_hint)
    recent_fps = [fingerprint(t) for t in recent_titles or []]

    for attempt in range(2):
        try:
            response = client().beta.messages.parse(
                model=settings.anthropic_model,
                max_tokens=16000,
                system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": prompt}],
                output_format=SlideshowPlan,
                output_config={"effort": settings.anthropic_effort},
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
            )
        except anthropic.AuthenticationError as exc:
            raise GenerationError("Anthropic API key missing or invalid (set ANTHROPIC_API_KEY).") from exc
        except anthropic.RateLimitError as exc:
            raise GenerationError("Anthropic rate limit hit; try again shortly.") from exc
        except anthropic.APIStatusError as exc:
            raise GenerationError(f"Anthropic API error {exc.status_code}: {exc.message}") from exc
        except anthropic.APIConnectionError as exc:
            raise GenerationError("Could not reach the Anthropic API.") from exc

        if response.stop_reason == "refusal":
            raise GenerationError("The model declined to write this slideshow; adjust the topic.")
        if response.stop_reason == "max_tokens" or response.parsed_output is None:
            raise GenerationError("The model response was cut off; try a shorter request.")

        plan = response.parsed_output
        if not plan.slides:
            raise GenerationError("The model returned no slides.")
        hook_text = " ".join(t.text for t in plan.slides[0].text_items)
        if attempt == 0 and too_similar(hook_text, recent_fps):
            prompt += (
                f'\n\nYour previous draft hook "{hook_text}" is too close to an existing post. '
                "Use a completely different hook and list."
            )
            continue
        plan.caption = plan.caption[:89]
        return plan
    return plan
