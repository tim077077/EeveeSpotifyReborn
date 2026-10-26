"""One interface over any LLM key: Anthropic natively, everything else via the OpenAI-compatible API.

Paste a key and the provider is detected from its prefix; model and base URL can be overridden.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import TypeVar

import anthropic
import httpx
from pydantic import BaseModel, ValidationError

from . import store
from .config import settings

T = TypeVar("T", bound=BaseModel)


@dataclass(frozen=True)
class Preset:
    label: str
    base_url: str
    default_model: str
    vision: bool = True


# Default models are starting points; any model the provider serves can be set in Settings.
PRESETS: dict[str, Preset] = {
    "anthropic": Preset("Anthropic (Claude)", "https://api.anthropic.com", "claude-opus-5-5"),
    "openai": Preset("OpenAI", "https://api.openai.com/v1", "gpt-5-mini"),
    "gemini": Preset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    "openrouter": Preset("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto"),
    "groq": Preset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", vision=False),
    "deepseek": Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", vision=False),
    "xai": Preset("xAI (Grok)", "https://api.x.ai/v1", "grok-4"),
    "mistral": Preset("Mistral", "https://api.mistral.ai/v1", "mistral-large-latest"),
    "together": Preset("Together AI", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo", vision=False),
    "ollama": Preset("Ollama (local)", "http://localhost:11434/v1", "llama3.1", vision=False),
    "custom": Preset("Custom OpenAI-compatible", "", "", vision=False),
}

# Environment variable names that pin the provider regardless of key shape.
ENV_PROVIDER = {
    "ANTHROPIC_API_KEY": "anthropic", "OPENAI_API_KEY": "openai", "GEMINI_API_KEY": "gemini",
    "GOOGLE_API_KEY": "gemini", "OPENROUTER_API_KEY": "openrouter", "GROQ_API_KEY": "groq",
    "DEEPSEEK_API_KEY": "deepseek", "XAI_API_KEY": "xai", "MISTRAL_API_KEY": "mistral", "TOGETHER_API_KEY": "together",
}


class LLMError(RuntimeError):
    pass


def detect_provider(key: str) -> str | None:
    key = key.strip()
    if key.startswith("sk-ant-"):
        return "anthropic"
    if key.startswith("sk-or-"):
        return "openrouter"
    if key.startswith("gsk_"):
        return "groq"
    if key.startswith("AIza"):
        return "gemini"
    if key.startswith("xai-"):
        return "xai"
    if key.startswith("sk-"):
        return "openai"  # DeepSeek keys share this prefix; pick DeepSeek explicitly in Settings
    if re.fullmatch(r"[0-9a-f]{64}", key):
        return "together"
    return None


@dataclass(frozen=True)
class LLMConfig:
    provider: str
    api_key: str
    model: str
    base_url: str

    @property
    def vision(self) -> bool:
        return PRESETS.get(self.provider, PRESETS["custom"]).vision or self.provider == "custom"


def resolve() -> LLMConfig | None:
    key = store.get("llm_api_key")
    provider = store.get("llm_provider")
    if not provider:
        provider = ENV_PROVIDER.get(store.env_source("llm_api_key") or "") or detect_provider(key) or ""
    if not provider:
        return None
    if not key and provider not in {"ollama", "custom"}:
        return None
    preset = PRESETS.get(provider, PRESETS["custom"])
    model = store.get("llm_model") or preset.default_model
    base_url = (store.get("llm_base_url") or preset.base_url).rstrip("/")
    if not model or not base_url:
        return None
    return LLMConfig(provider, key, model, base_url)


def configured() -> bool:
    return resolve() is not None


def _schema_instructions(model: type[BaseModel]) -> str:
    return (
        "Respond with a single JSON object and nothing else (no prose, no code fences). "
        f"It must validate against this JSON Schema:\n{json.dumps(model.model_json_schema())}"
    )


def _extract_json(text: str) -> str:
    text = text.strip()
    fence = re.search(r"```(?:json)?\s*(.*?)```", text, re.S)
    if fence:
        return fence.group(1).strip()
    start, end = text.find("{"), text.rfind("}")
    return text[start : end + 1] if start != -1 and end > start else text


def complete_json(system: str, user: str, model: type[T], images: list[str] | None = None, cfg: LLMConfig | None = None) -> T:
    """Ask the configured model for JSON matching `model`. `images` are http(s) or data: URLs."""
    cfg = cfg or resolve()
    if cfg is None:
        raise LLMError("No AI key configured. Add one in Settings (any of: Anthropic, OpenAI, Gemini, OpenRouter, Groq, …).")
    if images and not cfg.vision:
        images = None
    if cfg.provider == "anthropic":
        return _anthropic(cfg, system, user, model, images)
    return _openai_compatible(cfg, system, user, model, images)


def _anthropic(cfg: LLMConfig, system: str, user: str, model: type[T], images: list[str] | None) -> T:
    client = anthropic.Anthropic(api_key=cfg.api_key)
    content: list[dict] = []
    for url in images or []:
        if url.startswith("data:"):
            media, _, data = url[5:].partition(";base64,")
            content.append({"type": "image", "source": {"type": "base64", "media_type": media, "data": data}})
        else:
            content.append({"type": "image", "source": {"type": "url", "url": url}})
    content.append({"type": "text", "text": user})
    kwargs = dict(
        model=cfg.model,
        max_tokens=16000,
        system=system,
        messages=[{"role": "user", "content": content}],
        output_format=model,
    )
    if cfg.model.startswith(("claude-opus-5", "claude-fable", "claude-sonnet-5", "claude-haiku-5", "claude-opus-4-8")):
        kwargs["output_config"] = {"effort": settings.anthropic_effort}
    # Server-side refusal fallback is a Claude API feature for the current Claude models.
    if cfg.model.startswith(("claude-opus-5", "claude-fable", "claude-sonnet-5-5")):
        kwargs |= {"betas": ["server-side-fallback-2026-07-01"], "fallbacks": "default"}
    try:
        response = client.beta.messages.parse(**kwargs)
    except anthropic.AuthenticationError as exc:
        raise LLMError("Anthropic rejected the API key.") from exc
    except anthropic.RateLimitError as exc:
        raise LLMError("Anthropic rate limit hit; try again shortly.") from exc
    except anthropic.APIStatusError as exc:
        raise LLMError(f"Anthropic API error {exc.status_code}: {exc.message}") from exc
    except anthropic.APIConnectionError as exc:
        raise LLMError("Could not reach the Anthropic API.") from exc
    if response.stop_reason == "refusal":
        raise LLMError("The model declined this request; adjust the topic.")
    if response.parsed_output is None:
        raise LLMError("The model response was cut off or empty.")
    return response.parsed_output


def _openai_compatible(cfg: LLMConfig, system: str, user: str, model: type[T], images: list[str] | None) -> T:
    if images:
        user_content: str | list[dict] = [{"type": "text", "text": user}] + [
            {"type": "image_url", "image_url": {"url": u}} for u in images
        ]
    else:
        user_content = user
    messages: list[dict] = [
        {"role": "system", "content": f"{system}\n\n{_schema_instructions(model)}"},
        {"role": "user", "content": user_content},
    ]
    headers = {"Content-Type": "application/json"}
    if cfg.api_key:
        headers["Authorization"] = f"Bearer {cfg.api_key}"
    if cfg.provider == "openrouter":
        headers |= {"HTTP-Referer": "https://github.com/slidemill", "X-Title": "Slidemill"}

    last_error = ""
    for _ in range(2):
        body = {"model": cfg.model, "messages": messages, "response_format": {"type": "json_object"}}
        try:
            resp = httpx.post(f"{cfg.base_url}/chat/completions", json=body, headers=headers, timeout=180)
        except httpx.HTTPError as exc:
            raise LLMError(f"Could not reach {cfg.provider}: {exc}") from exc
        if resp.status_code == 400 and "response_format" in resp.text:
            body.pop("response_format")  # some servers reject JSON mode; the schema prompt still applies
            resp = httpx.post(f"{cfg.base_url}/chat/completions", json=body, headers=headers, timeout=180)
        if resp.status_code in (401, 403):
            raise LLMError(f"{cfg.provider} rejected the API key ({resp.status_code}).")
        if resp.status_code == 429:
            raise LLMError(f"{cfg.provider} rate limit or quota exceeded.")
        if resp.status_code >= 400:
            raise LLMError(f"{cfg.provider} error {resp.status_code}: {resp.text[:400]}")
        try:
            choice = resp.json()["choices"][0]
            text = choice["message"].get("content") or ""
        except (KeyError, IndexError, ValueError) as exc:
            raise LLMError(f"Unexpected response from {cfg.provider}: {resp.text[:300]}") from exc
        try:
            return model.model_validate_json(_extract_json(text))
        except ValidationError as exc:
            last_error = str(exc)[:1500]
            messages += [
                {"role": "assistant", "content": text},
                {"role": "user", "content": f"That JSON did not match the schema:\n{last_error}\nReturn the corrected JSON object only."},
            ]
    raise LLMError(f"Model did not return valid JSON: {last_error[:300]}")


class _Ping(BaseModel):
    ok: bool


def test(cfg: LLMConfig | None = None) -> str:
    cfg = cfg or resolve()
    if cfg is None:
        raise LLMError("No AI key configured.")
    result = complete_json("You are a health check.", 'Return {"ok": true}.', _Ping, cfg=cfg)
    if not result.ok:
        raise LLMError("Model answered but returned ok=false.")
    return f"{PRESETS.get(cfg.provider, PRESETS['custom']).label} · {cfg.model}"
