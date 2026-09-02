import hashlib
import hmac
import secrets
import time
from dataclasses import dataclass

from fastapi import Header, HTTPException

from .config import settings

CHALLENGES: dict[str, float] = {}
CHALLENGE_TTL = 120


def issue_challenge() -> str:
    nonce = secrets.token_urlsafe(24)
    CHALLENGES[nonce] = time.time() + CHALLENGE_TTL
    _gc_challenges()
    return nonce


def consume_challenge(nonce: str) -> bool:
    exp = CHALLENGES.pop(nonce, None)
    if exp is None:
        return False
    return exp >= time.time()


def _gc_challenges() -> None:
    now = time.time()
    dead = [k for k, v in CHALLENGES.items() if v < now]
    for k in dead:
        CHALLENGES.pop(k, None)


def mint_token(mailbox_id: str, ttl: int = 86400) -> str:
    exp = int(time.time()) + ttl
    body = f"{mailbox_id}|{exp}"
    mac = hmac.new(
        settings.bleep_token_secret.encode(), body.encode(), hashlib.sha256
    ).hexdigest()
    return f"{mailbox_id}.{exp}.{mac}"


@dataclass
class Principal:
    mailbox_id: str


def parse_token(token: str) -> Principal:
    try:
        mailbox_id, exp_s, mac = token.split(".", 2)
        exp = int(exp_s)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="bad token") from exc
    if exp < time.time():
        raise HTTPException(status_code=401, detail="token expired")
    body = f"{mailbox_id}|{exp}"
    expect = hmac.new(
        settings.bleep_token_secret.encode(), body.encode(), hashlib.sha256
    ).hexdigest()
    if not hmac.compare_digest(expect, mac):
        raise HTTPException(status_code=401, detail="bad token")
    return Principal(mailbox_id=mailbox_id)


async def require_mailbox(authorization: str | None = Header(default=None)) -> Principal:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="mailbox token required")
    return parse_token(authorization.split(" ", 1)[1].strip())


def require_ops(authorization: str | None) -> None:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="ops token required")
    got = authorization.split(" ", 1)[1].strip()
    if not hmac.compare_digest(got, settings.bleep_ops_token):
        raise HTTPException(status_code=403, detail="ops token rejected")
