from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .models import Mailbox
from .util import new_short_id


async def assign_short_id(session: AsyncSession, box: Mailbox) -> str:
    if box.short_id:
        return box.short_id
    for _ in range(8):
        candidate = new_short_id()
        taken = await session.scalar(select(Mailbox.id).where(Mailbox.short_id == candidate))
        if taken:
            continue
        box.short_id = candidate
        return candidate
    raise HTTPException(status_code=500, detail="short id")
