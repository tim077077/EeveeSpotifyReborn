"""Runtime settings, read once from the environment (or a .env file)."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


_load_dotenv(ROOT / ".env")


def _bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    data_dir: Path = field(default_factory=lambda: Path(os.environ.get("SLIDEMILL_DATA_DIR", ROOT / "data")))
    # Public URL this server is reachable at. TikTok pulls slide images from here,
    # so in production it must be an HTTPS domain verified in the TikTok dev portal.
    public_base_url: str = os.environ.get("PUBLIC_BASE_URL", "http://localhost:8000").rstrip("/")
    # Bearer token for the dashboard and the REST API.
    api_key: str = os.environ.get("SLIDEMILL_API_KEY", "")
    timezone: str = os.environ.get("SLIDEMILL_TIMEZONE", "America/Los_Angeles")

    # Thinking effort for Claude models that support it: low | medium | high.
    anthropic_effort: str = os.environ.get("ANTHROPIC_EFFORT", "medium")

    tiktok_client_key: str = os.environ.get("TIKTOK_CLIENT_KEY", "")
    tiktok_client_secret: str = os.environ.get("TIKTOK_CLIENT_SECRET", "")
    # TikTok caps direct posts per creator; reel.farm documents 6 per 24h.
    tiktok_direct_posts_per_day: int = int(os.environ.get("TIKTOK_DIRECT_POSTS_PER_DAY", "6"))
    # When the direct-post cap is hit, fall back to a draft upload instead of failing.
    tiktok_fallback_to_draft: bool = _bool("TIKTOK_FALLBACK_TO_DRAFT", True)

    max_concurrent_generations: int = int(os.environ.get("MAX_CONCURRENT_GENERATIONS", "3"))
    run_scheduler: bool = _bool("SLIDEMILL_SCHEDULER", True)

    @property
    def media_dir(self) -> Path:
        return self.data_dir / "media"

    @property
    def database_url(self) -> str:
        return os.environ.get("DATABASE_URL", f"sqlite:///{self.data_dir / 'slidemill.db'}")

    @property
    def tiktok_redirect_uri(self) -> str:
        return f"{self.public_base_url}/auth/tiktok/callback"


settings = Settings()
settings.media_dir.mkdir(parents=True, exist_ok=True)
FONTS_DIR = ROOT / "fonts"
STATIC_DIR = ROOT / "static"
