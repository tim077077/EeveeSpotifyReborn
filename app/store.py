"""Dashboard-editable settings, with environment variables as the fallback."""

from __future__ import annotations

import os

from . import db

# setting key -> environment variables consulted when the key is unset in the DB
ENV_FALLBACKS: dict[str, tuple[str, ...]] = {
    "llm_provider": ("LLM_PROVIDER",),
    "llm_api_key": (
        "LLM_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY",
        "OPENROUTER_API_KEY", "GROQ_API_KEY", "DEEPSEEK_API_KEY", "XAI_API_KEY", "MISTRAL_API_KEY", "TOGETHER_API_KEY",
    ),
    "llm_model": ("LLM_MODEL", "ANTHROPIC_MODEL"),
    "llm_base_url": ("LLM_BASE_URL",),
    "image_provider": ("IMAGE_PROVIDER",),
    "image_api_key": ("IMAGE_API_KEY",),
    "image_model": ("IMAGE_MODEL",),
    "pexels_api_key": ("PEXELS_API_KEY",),
    "unsplash_access_key": ("UNSPLASH_ACCESS_KEY",),
    "apify_token": ("APIFY_TOKEN",),
    # Browser extension posting
    "browser_posts_per_day": ("BROWSER_POSTS_PER_DAY",),
    "browser_min_gap_minutes": ("BROWSER_MIN_GAP_MINUTES",),
    "browser_username": ("BROWSER_TIKTOK_USERNAME",),
}
SECRET_KEYS = {"llm_api_key", "image_api_key", "pexels_api_key", "unsplash_access_key", "apify_token"}


def get(key: str, default: str = "") -> str:
    with db.session_scope() as session:
        row = session.get(db.Setting, key)
        if row is not None and row.value:
            return row.value
    for env in ENV_FALLBACKS.get(key, ()):
        if os.environ.get(env):
            return os.environ[env]
    return default


def env_source(key: str) -> str | None:
    """Name of the environment variable a value would come from, if any."""
    for env in ENV_FALLBACKS.get(key, ()):
        if os.environ.get(env):
            return env
    return None


def set_many(values: dict[str, str]) -> None:
    with db.session_scope() as session:
        for key, value in values.items():
            if key not in ENV_FALLBACKS:
                raise KeyError(key)
            row = session.get(db.Setting, key)
            if row is None:
                session.add(db.Setting(key=key, value=value.strip()))
            else:
                row.value = value.strip()


def mask(value: str) -> str:
    if not value:
        return ""
    return value[:4] + "…" + value[-4:] if len(value) > 12 else "••••"


def public_view() -> dict[str, dict[str, str | bool | None]]:
    out = {}
    for key in ENV_FALLBACKS:
        value = get(key)
        out[key] = {
            "value": mask(value) if key in SECRET_KEYS else value,
            "set": bool(value),
            "from_env": env_source(key) if value and not _db_has(key) else None,
        }
    return out


def _db_has(key: str) -> bool:
    with db.session_scope() as session:
        row = session.get(db.Setting, key)
        return bool(row and row.value)
