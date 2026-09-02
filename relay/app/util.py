import re
import secrets
from datetime import datetime, timezone

from fastapi import Header, HTTPException, Request

from .config import settings

MAILBOX_RE = re.compile(r"^[a-z0-9]{16,64}$")
HANDLE_RE = re.compile(r"^[a-z][a-z0-9_]{2,23}$")


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def hour_band(ts: datetime | None = None) -> str:
    t = ts or utcnow()
    return t.strftime("%Y-%m-%dT%H")


def new_id(n: int = 18) -> str:
    return secrets.token_hex(n)[: n * 2]


def require_region(x_bleep_region: str | None = Header(default=None, alias="X-Bleep-Region")) -> str:
    if x_bleep_region is None:
        return settings.bleep_region
    if x_bleep_region != settings.bleep_region:
        raise HTTPException(
            status_code=421,
            detail={
                "error": "region_mismatch",
                "pinned": settings.bleep_region,
                "got": x_bleep_region,
            },
        )
    return x_bleep_region


def client_ip(request: Request) -> str:
    # Coarse, in-memory rate limiting only. Not persisted.
    return request.client.host if request.client else "0.0.0.0"


def valid_mailbox_id(value: str) -> str:
    if not MAILBOX_RE.match(value):
        raise HTTPException(status_code=400, detail="bad mailbox id")
    return value
