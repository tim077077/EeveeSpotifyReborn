"""Encryption at rest for API keys and TikTok tokens stored in the database.

The key comes from SLIDEMILL_SECRET_KEY (a Fernet key) or, if unset, from <data dir>/secret.key,
created on first run with owner-only permissions. Losing the key means re-entering stored API
keys and reconnecting TikTok accounts - back it up alongside the database.
"""

from __future__ import annotations

import os
from functools import lru_cache

from cryptography.fernet import Fernet, InvalidToken
from sqlalchemy.types import Text, TypeDecorator

from .config import settings

PREFIX = "enc:"


@lru_cache(maxsize=1)
def _fernet() -> Fernet:
    env_key = os.environ.get("SLIDEMILL_SECRET_KEY", "").strip()
    if env_key:
        return Fernet(env_key.encode())
    path = settings.data_dir / "secret.key"
    if not path.is_file():
        path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as fh:
            fh.write(Fernet.generate_key())
    return Fernet(path.read_bytes().strip())


def encrypt(value: str) -> str:
    if not value or value.startswith(PREFIX):
        return value
    return PREFIX + _fernet().encrypt(value.encode()).decode()


def decrypt(value: str) -> str:
    if not value or not value.startswith(PREFIX):
        return value  # empty, or a legacy plaintext row (re-encrypted on startup)
    try:
        return _fernet().decrypt(value[len(PREFIX):].encode()).decode()
    except InvalidToken as exc:
        raise RuntimeError(
            "Stored secrets can't be decrypted: the secret key changed. Restore the old "
            "SLIDEMILL_SECRET_KEY / data/secret.key, or re-enter keys in Settings."
        ) from exc


class EncryptedText(TypeDecorator):
    """Text column that is transparently encrypted in the database."""

    impl = Text
    cache_ok = True

    def process_bind_param(self, value, dialect):
        return encrypt(value) if value is not None else value

    def process_result_value(self, value, dialect):
        return decrypt(value) if value is not None else value
