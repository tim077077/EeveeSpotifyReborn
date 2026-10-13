"""AI background generation over whichever image key is available.

Resolution order: explicit image provider in Settings -> reuse the LLM key when that provider also
makes images (OpenAI, Gemini, Together) -> Pollinations (free, no key, watermarked).
"""

from __future__ import annotations

import base64
import random
from dataclasses import dataclass
from urllib.parse import quote

import httpx

from . import llm, store

IMAGE_PROVIDERS = {
    "openai": "gpt-image-1",
    "gemini": "gemini-2.5-flash-image",
    "together": "black-forest-labs/FLUX.1-schnell",
    "pollinations": "flux",
}

# Pixel targets per aspect ratio; providers snap to what they support.
SIZES = {"4:5": (1080, 1350), "9:16": (1080, 1920), "1:1": (1080, 1080), "16:9": (1920, 1080)}


class ImageGenError(RuntimeError):
    pass


@dataclass(frozen=True)
class ImageConfig:
    provider: str
    api_key: str
    model: str


def resolve() -> ImageConfig:
    provider = store.get("image_provider")
    key = store.get("image_api_key")
    if not provider or provider == "auto":
        cfg = llm.resolve()
        if key:
            provider = llm.detect_provider(key) or "pollinations"
            if provider not in IMAGE_PROVIDERS:
                provider = "pollinations"
        elif cfg and cfg.provider in IMAGE_PROVIDERS:
            provider, key = cfg.provider, cfg.api_key
        else:
            provider = "pollinations"
    if provider not in IMAGE_PROVIDERS:
        raise ImageGenError(f"Unknown image provider {provider!r}")
    if provider != "pollinations" and not key:
        llm_cfg = llm.resolve()
        if llm_cfg and llm_cfg.provider == provider:
            key = llm_cfg.api_key
        else:
            raise ImageGenError(f"No API key for image provider {provider}.")
    model = store.get("image_model") or IMAGE_PROVIDERS[provider]
    return ImageConfig(provider, key, model)


def describe() -> str:
    try:
        cfg = resolve()
    except ImageGenError as exc:
        return f"unavailable: {exc}"
    note = " (free, watermarked)" if cfg.provider == "pollinations" and not cfg.api_key else ""
    return f"{cfg.provider} · {cfg.model}{note}"


def build_prompt(prompt: str, visual_style: str = "") -> str:
    parts = [prompt.strip().rstrip(".")]
    if visual_style:
        parts.append(visual_style.strip().rstrip("."))
    parts.append(
        "Photorealistic vertical social-media photo, natural and candid, uncluttered area for overlay text. "
        "Absolutely no text, letters, captions, watermarks or logos in the image"
    )
    return ". ".join(parts) + "."


def generate(prompt: str, aspect_ratio: str = "4:5", visual_style: str = "", cfg: ImageConfig | None = None) -> bytes:
    """Return encoded image bytes (PNG/JPEG) for the prompt."""
    cfg = cfg or resolve()
    full = build_prompt(prompt, visual_style)
    try:
        if cfg.provider == "openai":
            return _openai(cfg, full, aspect_ratio)
        if cfg.provider == "gemini":
            return _gemini(cfg, full, aspect_ratio)
        if cfg.provider == "together":
            return _together(cfg, full, aspect_ratio)
        return _pollinations(cfg, full, aspect_ratio)
    except httpx.HTTPError as exc:
        raise ImageGenError(f"{cfg.provider} image request failed: {exc}") from exc


def _check(resp: httpx.Response, provider: str) -> dict:
    if resp.status_code >= 400:
        raise ImageGenError(f"{provider} image error {resp.status_code}: {resp.text[:300]}")
    return resp.json()


def _openai(cfg: ImageConfig, prompt: str, aspect: str) -> bytes:
    size = {"16:9": "1536x1024", "1:1": "1024x1024"}.get(aspect, "1024x1536")
    data = _check(
        httpx.post(
            "https://api.openai.com/v1/images/generations",
            headers={"Authorization": f"Bearer {cfg.api_key}"},
            json={"model": cfg.model, "prompt": prompt, "size": size, "n": 1},
            timeout=180,
        ),
        "openai",
    )
    item = data["data"][0]
    if item.get("b64_json"):
        return base64.b64decode(item["b64_json"])
    return httpx.get(item["url"], timeout=60).content


def _gemini(cfg: ImageConfig, prompt: str, aspect: str) -> bytes:
    data = _check(
        httpx.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/{cfg.model}:generateContent",
            headers={"x-goog-api-key": cfg.api_key},
            json={
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {"responseModalities": ["IMAGE"], "imageConfig": {"aspectRatio": aspect}},
            },
            timeout=180,
        ),
        "gemini",
    )
    for cand in data.get("candidates", []):
        for part in cand.get("content", {}).get("parts", []):
            inline = part.get("inlineData") or part.get("inline_data")
            if inline and inline.get("data"):
                return base64.b64decode(inline["data"])
    raise ImageGenError("Gemini returned no image (it may have declined the prompt).")


def _together(cfg: ImageConfig, prompt: str, aspect: str) -> bytes:
    w, h = SIZES.get(aspect, SIZES["4:5"])
    scale = 1024 / max(w, h)
    data = _check(
        httpx.post(
            "https://api.together.xyz/v1/images/generations",
            headers={"Authorization": f"Bearer {cfg.api_key}"},
            json={
                "model": cfg.model, "prompt": prompt, "n": 1, "steps": 4, "response_format": "base64",
                "width": int(w * scale) // 16 * 16, "height": int(h * scale) // 16 * 16,
            },
            timeout=180,
        ),
        "together",
    )
    return base64.b64decode(data["data"][0]["b64_json"])


def _pollinations(cfg: ImageConfig, prompt: str, aspect: str) -> bytes:
    w, h = SIZES.get(aspect, SIZES["4:5"])
    params = {"width": w, "height": h, "nologo": "true", "model": cfg.model, "seed": random.randint(1, 10**9)}
    if cfg.api_key:
        params["token"] = cfg.api_key
    resp = httpx.get(f"https://image.pollinations.ai/prompt/{quote(prompt[:1500])}", params=params, timeout=180, follow_redirects=True)
    if resp.status_code >= 400 or not resp.headers.get("content-type", "").startswith("image/"):
        raise ImageGenError(f"pollinations error {resp.status_code}")
    return resp.content
