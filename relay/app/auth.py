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


@dataclass
class Principal:
    mailbox_id: str
    device_id: str | None = None


def mint_token(mailbox_id: str, device_id: str | None = None, ttl: int = 86400) -> str:
    exp = int(time.time()) + ttl
    if device_id:
        body = f"{mailbox_id}|{device_id}|{exp}"
        mac = _mac(body)
        return f"{mailbox_id}.{device_id}.{exp}.{mac}"
    body = f"{mailbox_id}|{exp}"
    return f"{mailbox_id}.{exp}.{_mac(body)}"


def mint_account_token(account_id: str, ttl: int = 3600) -> str:
    exp = int(time.time()) + ttl
    body = f"acct|{account_id}|{exp}"
    return f"a.{account_id}.{exp}.{_mac(body)}"


def parse_account_token(token: str) -> str:
    try:
        kind, account_id, exp_s, mac = token.split(".", 3)
        exp = int(exp_s)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="bad token") from exc
    if kind != "a":
        raise HTTPException(status_code=401, detail="bad token")
    if exp < time.time():
        raise HTTPException(status_code=401, detail="token expired")
    body = f"acct|{account_id}|{exp}"
    if not hmac.compare_digest(_mac(body), mac):
        raise HTTPException(status_code=401, detail="bad token")
    return account_id


def _mac(body: str) -> str:
    return hmac.new(settings.bleep_token_secret.encode(), body.encode(), hashlib.sha256).hexdigest()


def parse_token(token: str) -> Principal:
    parts = token.split(".")
    try:
        if len(parts) == 4 and parts[0] != "a":
            mailbox_id, device_id, exp_s, mac = parts
            exp = int(exp_s)
            body = f"{mailbox_id}|{device_id}|{exp}"
        elif len(parts) == 3:
            mailbox_id, exp_s, mac = parts
            device_id = None
            exp = int(exp_s)
            body = f"{mailbox_id}|{exp}"
        else:
            raise ValueError("shape")
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="bad token") from exc
    if exp < time.time():
        raise HTTPException(status_code=401, detail="token expired")
    if not hmac.compare_digest(_mac(body), mac):
        raise HTTPException(status_code=401, detail="bad token")
    return Principal(mailbox_id=mailbox_id, device_id=device_id)


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
