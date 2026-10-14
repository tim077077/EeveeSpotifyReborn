"""TikTok Login Kit (OAuth v2) + Content Posting API for photo carousels + Display API metrics.

Docs: https://developers.tiktok.com/doc/content-posting-api-reference-photo-post
Scopes needed: user.info.basic, video.publish (direct post), video.upload (drafts), video.list (metrics).
"""

from __future__ import annotations

import secrets
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode

import httpx
from sqlalchemy.orm import Session

from . import db
from .config import settings

AUTH_URL = "https://www.tiktok.com/v2/auth/authorize/"
API = "https://open.tiktokapis.com/v2"
SCOPES = "user.info.basic,video.publish,video.upload,video.list"


class TikTokError(RuntimeError):
    pass


def configured() -> bool:
    return bool(settings.tiktok_client_key and settings.tiktok_client_secret)


def authorize_url(state: str | None = None) -> tuple[str, str]:
    state = state or secrets.token_urlsafe(16)
    query = urlencode(
        {
            "client_key": settings.tiktok_client_key,
            "scope": SCOPES,
            "response_type": "code",
            "redirect_uri": settings.tiktok_redirect_uri,
            "state": state,
        }
    )
    return f"{AUTH_URL}?{query}", state


def _token_request(data: dict) -> dict:
    resp = httpx.post(
        f"{API}/oauth/token/",
        data={"client_key": settings.tiktok_client_key, "client_secret": settings.tiktok_client_secret, **data},
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        timeout=20,
    )
    body = resp.json()
    if resp.status_code != 200 or "access_token" not in body:
        raise TikTokError(body.get("error_description") or body.get("error") or f"token request failed ({resp.status_code})")
    return body


def _apply_tokens(account: db.TikTokAccount, tok: dict) -> None:
    now = datetime.now(timezone.utc)
    account.access_token = tok["access_token"]
    account.refresh_token = tok.get("refresh_token", account.refresh_token)
    account.expires_at = now + timedelta(seconds=int(tok.get("expires_in", 86400)))
    account.refresh_expires_at = now + timedelta(seconds=int(tok.get("refresh_expires_in", 31536000)))


def exchange_code(session: Session, code: str) -> db.TikTokAccount:
    tok = _token_request({"code": code, "grant_type": "authorization_code", "redirect_uri": settings.tiktok_redirect_uri})
    account = session.query(db.TikTokAccount).filter_by(open_id=tok["open_id"]).one_or_none()
    if account is None:
        account = db.TikTokAccount(open_id=tok["open_id"], access_token=tok["access_token"])
        session.add(account)
    _apply_tokens(account, tok)
    try:
        info = _call(account, "GET", "/user/info/", params={"fields": "open_id,avatar_url,display_name,username"})
        user = info.get("user", {})
        account.username = user.get("username", "")
        account.display_name = user.get("display_name", "")
        account.avatar_url = user.get("avatar_url", "")
    except TikTokError:
        pass
    session.flush()
    return account


def ensure_fresh(account: db.TikTokAccount) -> None:
    expires = account.expires_at
    if expires is not None and expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    if expires and expires - datetime.now(timezone.utc) > timedelta(minutes=5):
        return
    if not account.refresh_token:
        raise TikTokError("TikTok session expired; reconnect the account.")
    _apply_tokens(account, _token_request({"grant_type": "refresh_token", "refresh_token": account.refresh_token}))


def _call(account: db.TikTokAccount, method: str, path: str, json: dict | None = None, params: dict | None = None) -> dict:
    resp = httpx.request(
        method,
        f"{API}{path}",
        json=json,
        params=params,
        headers={"Authorization": f"Bearer {account.access_token}", "Content-Type": "application/json; charset=UTF-8"},
        timeout=30,
    )
    try:
        body = resp.json()
    except ValueError as exc:
        raise TikTokError(f"TikTok returned non-JSON ({resp.status_code})") from exc
    err = body.get("error", {})
    if resp.status_code >= 400 or (err.get("code") not in (None, "", "ok")):
        raise TikTokError(f"{err.get('code', resp.status_code)}: {err.get('message', 'request failed')}")
    return body.get("data", {})


def creator_info(account: db.TikTokAccount) -> dict:
    ensure_fresh(account)
    return _call(account, "POST", "/post/publish/creator_info/query/")


def publish_photos(
    account: db.TikTokAccount,
    image_urls: list[str],
    *,
    title: str,
    description: str,
    post_mode: str = "MEDIA_UPLOAD",
    privacy_level: str = "PUBLIC_TO_EVERYONE",
    auto_add_music: bool = True,
    allow_comments: bool = True,
    is_aigc: bool = True,
    brand_organic: bool = False,
    branded_content: bool = False,
) -> str:
    """Start a photo-carousel post. Returns TikTok's publish_id.

    Image URLs must live under a domain/URL prefix verified for the app in the TikTok
    developer portal (PULL_FROM_URL). MEDIA_UPLOAD lands in the creator's TikTok inbox as a draft.
    """
    ensure_fresh(account)
    post_info: dict = {"title": title[:90], "description": description[:4000]}
    if post_mode == "DIRECT_POST":
        info = creator_info(account)
        options = info.get("privacy_level_options") or []
        if options and privacy_level not in options:
            # Never silently change the creator's choice; unaudited apps only allow SELF_ONLY.
            raise TikTokError(f"Privacy {privacy_level} isn't available for this account (allowed: {', '.join(options)}).")
        if branded_content and privacy_level == "SELF_ONLY":
            raise TikTokError("Branded content can't be posted privately.")
        post_info |= {
            "privacy_level": privacy_level,
            "disable_comment": (not allow_comments) or bool(info.get("comment_disabled")),
            "auto_add_music": auto_add_music,
            "is_aigc": is_aigc,
            "brand_organic_toggle": brand_organic,
            "brand_content_toggle": branded_content,
        }
    body = {
        "post_info": post_info,
        "source_info": {"source": "PULL_FROM_URL", "photo_cover_index": 0, "photo_images": image_urls[:35]},
        "post_mode": post_mode,
        "media_type": "PHOTO",
    }
    data = _call(account, "POST", "/post/publish/content/init/", json=body)
    return data["publish_id"]


def publish_status(account: db.TikTokAccount, publish_id: str) -> dict:
    """status: PROCESSING_UPLOAD | PROCESSING_DOWNLOAD | SEND_TO_USER_INBOX | PUBLISH_COMPLETE | FAILED."""
    ensure_fresh(account)
    return _call(account, "POST", "/post/publish/status/fetch/", json={"publish_id": publish_id})


def video_metrics(account: db.TikTokAccount, video_ids: list[str]) -> list[dict]:
    if not video_ids:
        return []
    ensure_fresh(account)
    fields = "id,title,view_count,like_count,comment_count,share_count,create_time,cover_image_url,share_url"
    out: list[dict] = []
    for i in range(0, len(video_ids), 20):
        data = _call(
            account, "POST", "/video/query/", params={"fields": fields}, json={"filters": {"video_ids": video_ids[i : i + 20]}}
        )
        out.extend(data.get("videos", []))
    return out
