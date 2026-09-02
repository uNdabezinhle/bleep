import time
from collections import defaultdict

from fastapi import HTTPException

from .config import settings

_hits: dict[str, list[float]] = defaultdict(list)


def check(key: str, limit: int, window: float = 3600) -> None:
    now = time.time()
    bucket = _hits[key]
    while bucket and bucket[0] < now - window:
        bucket.pop(0)
    if len(bucket) >= limit:
        raise HTTPException(status_code=429, detail="rate limited")
    bucket.append(now)


def check_drop(mailbox_id: str) -> None:
    check(f"drop:{mailbox_id}", settings.bleep_drops_per_hour)


def check_prekey(mailbox_id: str) -> None:
    check(f"prekey:{mailbox_id}", settings.bleep_prekey_fetch_per_hour)


def check_handle(ip: str) -> None:
    check(f"handle:{ip}", 30, window=600)
