"""Public channels. Posts are plaintext and readable by any live mailbox."""

import re

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .auth import Principal, require_mailbox
from .db import get_session
from .models import Channel, ChannelPost
from .rate import check
from .util import new_id, require_region, utcnow

router = APIRouter()
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 _-]{0,39}$")


class ChannelIn(BaseModel):
    name: str = Field(min_length=1, max_length=40)


class PostIn(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    name: str = Field(default="Someone", max_length=40)


@router.post("/v1/channels", status_code=201)
async def create_channel(
    body: ChannelIn,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    check(f"channel:{principal.mailbox_id}", 20)
    name = body.name.strip()
    if not NAME_RE.match(name):
        raise HTTPException(status_code=400, detail="channel name")
    taken = await session.scalar(select(Channel).where(Channel.name == name))
    if taken:
        raise HTTPException(status_code=409, detail="channel exists")
    row = Channel(id=new_id(8), name=name, created_at=utcnow(), owner_mailbox=principal.mailbox_id)
    session.add(row)
    await session.commit()
    return {"id": row.id, "name": row.name, "plaintext": True}


@router.get("/v1/channels")
async def list_channels(
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    check(f"channel-list:{principal.mailbox_id}", 60, window=600)
    rows = (await session.execute(select(Channel).order_by(Channel.created_at.desc()).limit(100))).scalars().all()
    return {"items": [{"id": r.id, "name": r.name} for r in rows], "plaintext": True}


@router.get("/v1/channels/{channel_id}/posts")
async def list_posts(
    channel_id: str,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    check(f"channel-read:{principal.mailbox_id}", 120, window=600)
    room = await session.get(Channel, channel_id)
    if not room:
        raise HTTPException(status_code=404, detail="no channel")
    rows = (
        await session.execute(
            select(ChannelPost).where(ChannelPost.channel_id == channel_id).order_by(ChannelPost.created_at.asc()).limit(200)
        )
    ).scalars().all()
    return {
        "id": room.id,
        "name": room.name,
        "plaintext": True,
        "posts": [
            {
                "id": p.id,
                "name": p.author_name,
                "text": p.body,
                "at": p.created_at.isoformat(),
            }
            for p in rows
        ],
    }


@router.post("/v1/channels/{channel_id}/posts", status_code=201)
async def add_post(
    channel_id: str,
    body: PostIn,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    check(f"channel-post:{principal.mailbox_id}", 60)
    room = await session.get(Channel, channel_id)
    if not room:
        raise HTTPException(status_code=404, detail="no channel")
    text = body.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="empty")
    name = body.name.strip()[:40] or "Someone"
    row = ChannelPost(
        id=new_id(8),
        channel_id=room.id,
        author_mailbox=principal.mailbox_id,
        author_name=name,
        body=text[:2000],
        created_at=utcnow(),
    )
    session.add(row)
    await session.commit()
    return {"id": row.id}
