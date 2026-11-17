"""Wake-only Web Push. The body is always {"v":1,"wake":true}."""

import asyncio
import logging

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .auth import Principal, require_mailbox
from .config import settings
from .db import get_session
from .models import PushSub
from .rate import check
from .util import new_id, require_region, utcnow

log = logging.getLogger("bleep")
router = APIRouter()
WAKE = '{"v":1,"wake":true}'
_sent: list[str] = []


class PushIn(BaseModel):
    endpoint: str = Field(min_length=12, max_length=2000)
    p256dh: str = Field(default="", max_length=200)
    auth: str = Field(default="", max_length=64)


def take_sent() -> list[str]:
    out = list(_sent)
    _sent.clear()
    return out


def _check_endpoint(endpoint: str) -> str:
    ep = endpoint.strip()
    if not ep.startswith("https://") or len(ep) > 2000:
        raise HTTPException(status_code=400, detail="push endpoint")
    return ep


@router.get("/v1/push/vapid")
async def vapid_key(
    _principal: Principal = Depends(require_mailbox),
    _region: str = Depends(require_region),
):
    return {"public_key": settings.bleep_vapid_public or None}


@router.put("/v1/push", status_code=204)
async def save_push(
    body: PushIn,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    check(f"push:{principal.mailbox_id}", 30)
    if len(body.p256dh) < 8 or len(body.auth) < 8:
        raise HTTPException(status_code=400, detail="push keys")
    ep = _check_endpoint(body.endpoint)
    row = await session.scalar(select(PushSub).where(PushSub.endpoint == ep))
    if row:
        row.mailbox_id = principal.mailbox_id
        row.p256dh = body.p256dh
        row.auth = body.auth
    else:
        existing = (
            await session.execute(
                select(PushSub).where(PushSub.mailbox_id == principal.mailbox_id).order_by(PushSub.created_at.asc())
            )
        ).scalars().all()
        if len(existing) >= 8:
            await session.delete(existing[0])
        session.add(
            PushSub(
                id=new_id(8),
                mailbox_id=principal.mailbox_id,
                endpoint=ep,
                p256dh=body.p256dh,
                auth=body.auth,
                created_at=utcnow(),
            )
        )
    await session.commit()


@router.delete("/v1/push", status_code=204)
async def drop_push(
    body: PushIn,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    ep = _check_endpoint(body.endpoint)
    row = await session.scalar(select(PushSub).where(PushSub.endpoint == ep))
    if row and row.mailbox_id == principal.mailbox_id:
        await session.delete(row)
        await session.commit()


async def notify(mailbox_id: str, session: AsyncSession) -> None:
    rows = (await session.execute(select(PushSub).where(PushSub.mailbox_id == mailbox_id))).scalars().all()
    for row in rows:
        _sent.append(WAKE)
        if len(_sent) > 40:
            del _sent[:-40]
        if not settings.bleep_vapid_private:
            continue
        await asyncio.to_thread(_deliver, row.endpoint, row.p256dh, row.auth)


def _deliver(endpoint: str, p256dh: str, auth: str) -> None:
    try:
        from pywebpush import webpush

        webpush(
            subscription_info={"endpoint": endpoint, "keys": {"p256dh": p256dh, "auth": auth}},
            data=WAKE,
            vapid_private_key=settings.bleep_vapid_private,
            vapid_claims={"sub": settings.bleep_vapid_sub},
        )
    except Exception:
        log.warning("push wake failed")
