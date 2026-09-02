import asyncio
import logging
from sqlalchemy import delete, select

from .db import SessionLocal
from .models import Envelope, Mailbox
from .util import utcnow

log = logging.getLogger("bleep.ttl")


async def sweep_once() -> int:
    now = utcnow()
    async with SessionLocal() as session:
        rows = (
            await session.execute(select(Envelope).where(Envelope.expires_at <= now))
        ).scalars().all()
        if not rows:
            return 0
        by_dest: dict[str, int] = {}
        ids = []
        for env in rows:
            ids.append(env.id)
            by_dest[env.dest_mailbox_id] = by_dest.get(env.dest_mailbox_id, 0) + env.size_bytes
        await session.execute(delete(Envelope).where(Envelope.id.in_(ids)))
        for dest, nbytes in by_dest.items():
            box = await session.get(Mailbox, dest)
            if box:
                box.bytes_in_flight = max(0, box.bytes_in_flight - nbytes)
        await session.commit()
        return len(ids)


async def sweeper(stop: asyncio.Event) -> None:
    while not stop.is_set():
        try:
            n = await sweep_once()
            if n:
                log.info("ttl sweep dropped %s envelopes", n)
        except Exception:
            log.exception("ttl sweep failed")
        try:
            await asyncio.wait_for(stop.wait(), timeout=30)
        except asyncio.TimeoutError:
            continue
