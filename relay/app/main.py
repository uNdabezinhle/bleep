import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import timedelta

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import func, inspect, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from . import wake
from .auth import (
    consume_challenge,
    issue_challenge,
    mint_token,
    require_mailbox,
    require_ops,
    Principal,
)
from .b64 import b64d, b64e
from .config import settings
from .crypto import auth_message, registration_message, verify_ed25519
from .db import Base, SessionLocal, engine, get_session
from .accounts import guard_device, router as account_router
from .channels import router as channel_router
from . import push
from .models import Account, DemandLog, Device, Envelope, HistoryBlob, HistoryExtra, HistoryFile, Mailbox, OneTimePrekey
from .rate import check_drop, check_handle, check_prekey
from .schemas import (
    AckIn,
    AuthIn,
    ChallengeOut,
    DropIn,
    DropOut,
    EnvelopeOut,
    HandleIn,
    HandleOut,
    HealthOut,
    PrekeyBundleOut,
    PrekeyIn,
    RegisterIn,
    SignedPrekeyIn,
    TokenOut,
)
from .shortid import assign_short_id
from .ttl import sweeper
from .util import (
    HANDLE_RE,
    as_utc,
    client_ip,
    hour_band,
    new_id,
    normalize_short_id,
    require_region,
    utcnow,
    valid_mailbox_id,
)

log = logging.getLogger("bleep")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

@asynccontextmanager
def _add_account_id(sync_conn) -> None:
    insp = inspect(sync_conn)
    if "mailboxes" not in insp.get_table_names():
        return
    cols = {c["name"] for c in insp.get_columns("mailboxes")}
    if "account_id" not in cols:
        sync_conn.execute(text("ALTER TABLE mailboxes ADD COLUMN account_id VARCHAR(32)"))
    if "short_id" not in cols:
        sync_conn.execute(text("ALTER TABLE mailboxes ADD COLUMN short_id VARCHAR(8)"))
    blob = "BYTEA" if sync_conn.dialect.name == "postgresql" else "BLOB"
    stamp = "TIMESTAMPTZ" if sync_conn.dialect.name == "postgresql" else "DATETIME"
    if "mlkem_pub" not in cols:
        sync_conn.execute(text(f"ALTER TABLE mailboxes ADD COLUMN mlkem_pub {blob}"))
    if "last_seen" not in cols:
        sync_conn.execute(text(f"ALTER TABLE mailboxes ADD COLUMN last_seen {stamp}"))
    sync_conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS ix_mailboxes_short_id ON mailboxes (short_id)"))
    if "accounts" in insp.get_table_names():
        acols = {c["name"] for c in insp.get_columns("accounts")}
        if "email_confirmed" not in acols:
            sync_conn.execute(text("ALTER TABLE accounts ADD COLUMN email_confirmed BOOLEAN NOT NULL DEFAULT TRUE"))


async def lifespan(_app: FastAPI):
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        await conn.run_sync(_add_account_id)
    stop = asyncio.Event()
    task = asyncio.create_task(sweeper(stop))
    yield
    stop.set()
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


app = FastAPI(
    title="Public Bleep relay",
    version="0.1.0",
    description="Sealed mailboxes. Handle prefix directory. No email search. No graph. No plaintext.",
    lifespan=lifespan,
)
app.include_router(account_router)
app.include_router(channel_router)
app.include_router(push.router)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def no_body_logs(request: Request, call_next):
    # Deliberate: do not log path+auth together in a way that paints edges.
    response = await call_next(request)
    return response


@app.get("/v1/health", response_model=HealthOut)
async def health(_region: str = Depends(require_region)):
    return HealthOut(
        ok=True,
        service="public-bleep-relay",
        region=settings.bleep_region,
        fail_closed=True,
        env=settings.bleep_env,
        holdings="sealed envelopes with TTL; sealed history blob; sealed file bytes; sealed extra blobs; email and password verifier; public channel posts are plaintext; precise last_seen; wake-only push subscription; no sender column on envelopes",
    )


@app.get("/v1/region")
async def region():
    return {"region": settings.bleep_region, "fail_closed": True}


@app.get("/v1/auth/challenge", response_model=ChallengeOut)
async def challenge(_region: str = Depends(require_region)):
    return ChallengeOut(nonce=issue_challenge(), region=settings.bleep_region)


@app.post("/v1/auth/token", response_model=TokenOut)
async def token(
    body: AuthIn,
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    valid_mailbox_id(body.mailbox_id)
    if not consume_challenge(body.nonce):
        raise HTTPException(status_code=401, detail="challenge expired")
    box = await session.get(Mailbox, body.mailbox_id)
    if not box or box.unlink_dead:
        raise HTTPException(status_code=401, detail="unknown mailbox")
    try:
        sig = b64d(body.signature_b64)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="bad b64") from exc
    if not verify_ed25519(box.identity_ed25519, auth_message(body.mailbox_id, body.nonce), sig):
        raise HTTPException(status_code=401, detail="bad signature")
    device_id = None
    if box.account_id:
        if not body.device_id or not body.device_credential:
            raise HTTPException(status_code=401, detail="device credential required")
        dev = await session.get(Device, body.device_id)
        from .accounts import _hash_cred

        if (
            not dev
            or dev.account_id != box.account_id
            or dev.credential_hash != _hash_cred(body.device_credential)
        ):
            raise HTTPException(status_code=401, detail="device credential required")
        if dev.erase_requested:
            raise HTTPException(status_code=403, detail="erase")
        if dev.revoked:
            raise HTTPException(status_code=401, detail="device revoked")
        device_id = dev.id
    box.last_connect_band = hour_band()
    short_id = await assign_short_id(session, box)
    await session.commit()
    return TokenOut(
        token=mint_token(box.id, device_id),
        mailbox_id=box.id,
        region=settings.bleep_region,
        short_id=short_id,
        handle=box.handle,
    )


@app.post("/v1/mailboxes", response_model=TokenOut, status_code=201)
async def register(
    body: RegisterIn,
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    valid_mailbox_id(body.mailbox_id)
    try:
        ed = b64d(body.identity_ed25519_b64)
        x = b64d(body.identity_x25519_b64)
        spk = b64d(body.signed_prekey.pub_b64)
        spk_sig = b64d(body.signed_prekey.sig_b64)
        reg_sig = b64d(body.registration_sig_b64)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="bad b64") from exc
    if len(ed) != 32 or len(x) != 32 or len(spk) != 32:
        raise HTTPException(status_code=400, detail="bad key length")
    if not verify_ed25519(ed, registration_message(body.mailbox_id, x), reg_sig):
        raise HTTPException(status_code=400, detail="bad registration signature")
    if not verify_ed25519(ed, b"BLEEP-SPK-v1|" + body.signed_prekey.key_id.to_bytes(4, "big") + spk, spk_sig):
        raise HTTPException(status_code=400, detail="bad signed-prekey signature")
    mlkem = None
    if body.identity_mlkem_b64 or body.mlkem_sig_b64:
        if not body.identity_mlkem_b64 or not body.mlkem_sig_b64:
            raise HTTPException(status_code=400, detail="mlkem pair")
        try:
            mlkem = b64d(body.identity_mlkem_b64)
            mlkem_sig = b64d(body.mlkem_sig_b64)
        except Exception as exc:
            raise HTTPException(status_code=400, detail="bad mlkem") from exc
        if len(mlkem) != 1184:
            raise HTTPException(status_code=400, detail="bad mlkem length")
        if not verify_ed25519(ed, b"BLEEP-MLKEM-v1|" + mlkem, mlkem_sig):
            raise HTTPException(status_code=400, detail="bad mlkem signature")
    existing = await session.get(Mailbox, body.mailbox_id)
    if existing:
        raise HTTPException(status_code=409, detail="mailbox exists")
    dup = await session.scalar(
        select(Mailbox).where(Mailbox.identity_ed25519 == ed)
    )
    if dup:
        raise HTTPException(status_code=409, detail="identity already registered")
    now = utcnow()
    box = Mailbox(
        id=body.mailbox_id,
        identity_ed25519=ed,
        identity_x25519=x,
        spk_id=body.signed_prekey.key_id,
        spk_pub=spk,
        spk_sig=spk_sig,
        created_at=now,
        last_connect_band=hour_band(now),
        unlink_dead=False,
        region=settings.bleep_region,
        bytes_in_flight=0,
        mlkem_pub=mlkem,
    )
    session.add(box)
    for opk in body.one_time_prekeys:
        try:
            pub = b64d(opk.pub_b64)
        except Exception as exc:
            raise HTTPException(status_code=400, detail="bad opk") from exc
        if len(pub) != 32:
            raise HTTPException(status_code=400, detail="bad opk length")
        session.add(OneTimePrekey(mailbox_id=box.id, key_id=opk.key_id, pub=pub))
    short_id = await assign_short_id(session, box)
    await session.commit()
    return TokenOut(
        token=mint_token(box.id),
        mailbox_id=box.id,
        region=settings.bleep_region,
        short_id=short_id,
        handle=box.handle,
    )


@app.get("/v1/mailboxes/{mailbox_id}/prekey", response_model=PrekeyBundleOut)
async def fetch_prekey(
    mailbox_id: str,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    valid_mailbox_id(mailbox_id)
    check_prekey(principal.mailbox_id)
    await guard_device(principal, session)
    box = await session.get(Mailbox, mailbox_id)
    if not box or box.unlink_dead:
        raise HTTPException(status_code=404, detail="no mailbox")
    opk_row = await session.scalar(
        select(OneTimePrekey)
        .where(OneTimePrekey.mailbox_id == mailbox_id)
        .limit(1)
    )
    opk = None
    if opk_row:
        opk = PrekeyIn(key_id=opk_row.key_id, pub_b64=b64e(opk_row.pub))
        await session.delete(opk_row)
        await session.commit()
    return PrekeyBundleOut(
        mailbox_id=box.id,
        identity_ed25519_b64=b64e(box.identity_ed25519),
        identity_x25519_b64=b64e(box.identity_x25519),
        signed_prekey=SignedPrekeyIn(
            key_id=box.spk_id, pub_b64=b64e(box.spk_pub), sig_b64=b64e(box.spk_sig)
        ),
        one_time_prekey=opk,
        identity_mlkem_b64=b64e(box.mlkem_pub) if box.mlkem_pub else None,
    )


@app.post("/v1/mailboxes/{mailbox_id}/prekeys")
async def replenish_prekeys(
    mailbox_id: str,
    keys: list[PrekeyIn],
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    if principal.mailbox_id != mailbox_id:
        raise HTTPException(status_code=403, detail="not your mailbox")
    await guard_device(principal, session)
    box = await session.get(Mailbox, mailbox_id)
    if not box or box.unlink_dead:
        raise HTTPException(status_code=404, detail="no mailbox")
    if len(keys) > 100:
        raise HTTPException(status_code=400, detail="too many prekeys")
    for opk in keys:
        pub = b64d(opk.pub_b64)
        if len(pub) != 32:
            raise HTTPException(status_code=400, detail="bad opk")
        session.add(OneTimePrekey(mailbox_id=mailbox_id, key_id=opk.key_id, pub=pub))
    await session.commit()
    return {"ok": True, "added": len(keys)}


@app.post("/v1/mailboxes/{mailbox_id}/unlink")
async def unlink(
    mailbox_id: str,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    if principal.mailbox_id != mailbox_id:
        raise HTTPException(status_code=403, detail="not your mailbox")
    box = await guard_device(principal, session)
    if not box:
        raise HTTPException(status_code=404, detail="no mailbox")
    box.unlink_dead = True
    box.handle = None
    await session.execute(
        OneTimePrekey.__table__.delete().where(OneTimePrekey.mailbox_id == mailbox_id)
    )
    envs = (
        await session.execute(select(Envelope).where(Envelope.dest_mailbox_id == mailbox_id))
    ).scalars().all()
    for env in envs:
        await session.delete(env)
    box.bytes_in_flight = 0
    await session.commit()
    return {"ok": True, "dead": True}


@app.post("/v1/mail", response_model=DropOut, status_code=202)
async def drop_mail(
    body: DropIn,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    """Store a sealed blob. Authenticated mailbox is used for quota only — not stored."""
    await guard_device(principal, session)
    valid_mailbox_id(body.dest_mailbox_id)
    check_drop(principal.mailbox_id)
    dest = await session.get(Mailbox, body.dest_mailbox_id)
    if not dest or dest.unlink_dead:
        raise HTTPException(status_code=404, detail="no mailbox")
    try:
        blob = b64d(body.blob_b64)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="bad blob") from exc
    if len(blob) < 45 or len(blob) > settings.bleep_envelope_max_bytes:
        raise HTTPException(status_code=413, detail="blob size")
    sender = await session.get(Mailbox, principal.mailbox_id)
    if not sender or sender.unlink_dead:
        raise HTTPException(status_code=401, detail="dead mailbox")
    if dest.bytes_in_flight + len(blob) > settings.bleep_mailbox_quota_bytes:
        raise HTTPException(status_code=429, detail="dest quota")
    ttl = body.ttl_seconds or settings.bleep_max_ttl_seconds
    ttl = max(60, min(ttl, settings.bleep_max_ttl_seconds))
    now = utcnow()
    env = Envelope(
        id=new_id(16),
        dest_mailbox_id=dest.id,
        blob=blob,
        size_bytes=len(blob),
        created_at=now,
        expires_at=now + timedelta(seconds=ttl),
    )
    dest.bytes_in_flight += len(blob)
    session.add(env)
    await session.commit()
    await wake.ping(dest.id)
    await push.notify(dest.id, session)
    # principal.mailbox_id deliberately not written on Envelope (T7).
    return DropOut(id=env.id, expires_at=env.expires_at.isoformat())


@app.get("/v1/mail", response_model=list[EnvelopeOut])
async def fetch_mail(
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    await guard_device(principal, session)
    box = await session.get(Mailbox, principal.mailbox_id)
    if not box or box.unlink_dead:
        raise HTTPException(status_code=401, detail="dead mailbox")
    now = utcnow()
    rows = (
        await session.execute(
            select(Envelope)
            .where(Envelope.dest_mailbox_id == principal.mailbox_id, Envelope.expires_at > now)
            .order_by(Envelope.created_at.asc())
            .limit(50)
        )
    ).scalars().all()
    out = [
        EnvelopeOut(id=r.id, blob_b64=b64e(r.blob), expires_at=r.expires_at.isoformat())
        for r in rows
    ]
    box.last_connect_band = hour_band()
    box.last_seen = now
    await session.commit()
    return out


@app.post("/v1/mail/ack", status_code=204)
async def ack_mail(
    body: AckIn,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    """Delete envelopes this mailbox has opened. Unknown ids are ignored. No sender column."""
    await guard_device(principal, session)
    ids = [i for i in body.ids if i][:50]
    if not ids:
        return Response(status_code=204)
    rows = (
        await session.execute(
            select(Envelope).where(
                Envelope.dest_mailbox_id == principal.mailbox_id,
                Envelope.id.in_(ids),
            )
        )
    ).scalars().all()
    box = await session.get(Mailbox, principal.mailbox_id)
    freed = 0
    for row in rows:
        freed += row.size_bytes
        await session.delete(row)
    if box:
        box.bytes_in_flight = max(0, (box.bytes_in_flight or 0) - freed)
    await session.commit()
    return Response(status_code=204)


@app.get("/v1/presence")
async def presence(
    request: Request,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    ids = request.query_params.getlist("mailbox_id")[:30]
    now = utcnow()
    items = []
    for mid in ids:
        if not mid or len(mid) > 64:
            continue
        box = await session.get(Mailbox, mid)
        seen = as_utc(box.last_seen) if box else None
        online = bool(seen and (now - seen).total_seconds() < 45)
        items.append({
            "mailbox_id": mid,
            "last_seen": seen.isoformat() if seen else None,
            "online": online,
        })
    return {"items": items}


@app.put("/v1/handles", response_model=HandleOut)
async def publish_handle(
    body: HandleIn,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    await guard_device(principal, session)
    handle = body.handle.strip().lower()
    if not HANDLE_RE.match(handle):
        raise HTTPException(status_code=400, detail="handle shape")
    taken = await session.scalar(select(Mailbox).where(Mailbox.handle == handle))
    if taken and taken.id != principal.mailbox_id:
        raise HTTPException(status_code=409, detail="handle taken")
    box = await session.get(Mailbox, principal.mailbox_id)
    if not box or box.unlink_dead:
        raise HTTPException(status_code=401, detail="dead mailbox")
    box.handle = handle
    await session.commit()
    return HandleOut(
        handle=handle,
        mailbox_id=box.id,
        identity_ed25519_b64=b64e(box.identity_ed25519),
        identity_x25519_b64=b64e(box.identity_x25519),
    )


@app.delete("/v1/handles")
async def drop_handle(
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    box = await guard_device(principal, session)
    if not box:
        raise HTTPException(status_code=404, detail="no mailbox")
    box.handle = None
    await session.commit()
    return {"ok": True}


@app.get("/v1/ids/{short_id}")
async def resolve_short_id(
    short_id: str,
    request: Request,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    await guard_device(principal, session)
    check_handle(client_ip(request) + ":" + principal.mailbox_id)
    compact = normalize_short_id(short_id)
    if not compact:
        raise HTTPException(status_code=400, detail="bad id")
    box = await session.scalar(select(Mailbox).where(Mailbox.short_id == compact, Mailbox.unlink_dead.is_(False)))
    if not box:
        raise HTTPException(status_code=404, detail="no id")
    return {
        "short_id": compact,
        "mailbox_id": box.id,
        "handle": box.handle,
        "identity_ed25519_b64": b64e(box.identity_ed25519),
        "identity_x25519_b64": b64e(box.identity_x25519),
    }


@app.get("/v1/handles/{handle}", response_model=HandleOut)
async def resolve_handle(
    handle: str,
    request: Request,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    await guard_device(principal, session)
    check_handle(client_ip(request) + ":" + principal.mailbox_id)
    h = handle.strip().lower()
    box = await session.scalar(select(Mailbox).where(Mailbox.handle == h, Mailbox.unlink_dead.is_(False)))
    if not box:
        raise HTTPException(status_code=404, detail="no handle")
    return HandleOut(
        handle=h,
        mailbox_id=box.id,
        identity_ed25519_b64=b64e(box.identity_ed25519),
        identity_x25519_b64=b64e(box.identity_x25519),
    )


@app.get("/v1/ops/schema")
async def ops_schema(authorization: str | None = Header(default=None)):
    require_ops(authorization)
    return {
        "tables": {
            "mailboxes": [
                "id",
                "identity_ed25519",
                "identity_x25519",
                "spk_*",
                "created_at",
                "last_connect_band",
                "handle",
                "unlink_dead",
                "region",
                "bytes_in_flight",
                "mlkem_pub",
                "last_seen",
            ],
            "channels": ["id", "name", "created_at", "owner_mailbox"],
            "channel_posts": ["id", "channel_id", "author_mailbox", "author_name", "body plaintext", "created_at"],
            "push_subscriptions": ["id", "mailbox_id", "endpoint", "p256dh", "auth", "created_at"],
            "one_time_prekeys": ["mailbox_id", "key_id", "pub"],
            "envelopes": [
                "id",
                "dest_mailbox_id",
                "blob (sealed)",
                "size_bytes",
                "created_at",
                "expires_at",
                "NO sender column",
            ],
            "demand_log": ["at", "mailbox_id", "kind", "note"],
        },
        "held_plaintext": ["public channel posts", "precise last_seen timestamp", "push subscription endpoint"],
        "not_held": [
            "message plaintext",
            "attachment plaintext",
            "identity private keys",
            "recipient×sender edge list",
            "Guardian hits",
            "Chamber flag",
            "push preview",
            "phone number",
            "address book",
        ],
    }


@app.get("/v1/ops/holdings/{mailbox_id}")
async def ops_holdings(
    mailbox_id: str,
    authorization: str | None = Header(default=None),
    session: AsyncSession = Depends(get_session),
):
    require_ops(authorization)
    box = await session.get(Mailbox, mailbox_id)
    session.add(
        DemandLog(
            at=utcnow(),
            mailbox_id=mailbox_id,
            kind="holdings",
            note="compulsion drill / LE query — yield is the JSON of this endpoint",
        )
    )
    await session.commit()
    if not box:
        return {
            "mailbox_id": mailbox_id,
            "exists": False,
            "yield": "nothing — unknown routing id",
        }
    n_env = await session.scalar(
        select(func.count()).select_from(Envelope).where(Envelope.dest_mailbox_id == mailbox_id)
    )
    account = await session.get(Account, box.account_id) if box.account_id else None
    hist = await session.get(HistoryBlob, box.account_id) if box.account_id else None
    n_files = (
        await session.scalar(
            select(func.count()).select_from(HistoryFile).where(HistoryFile.account_id == box.account_id)
        )
        if box.account_id
        else 0
    )
    n_extras = (
        await session.scalar(
            select(func.count()).select_from(HistoryExtra).where(HistoryExtra.account_id == box.account_id)
        )
        if box.account_id
        else 0
    )
    return {
        "mailbox_id": mailbox_id,
        "exists": True,
        "created_at": box.created_at.isoformat(),
        "last_connect_band": box.last_connect_band,
        "last_seen": box.last_seen.isoformat() if box.last_seen else None,
        "handle_published": bool(box.handle),
        "handle": box.handle,
        "unlink_dead": box.unlink_dead,
        "region": box.region,
        "envelopes_in_flight": int(n_env or 0),
        "envelopes_are": "sealed blobs; operator cannot read",
        "who_they_talked_to": "not held — no sender column",
        "transcript": "not held as plaintext; a sealed history blob may exist for the account",
        "guardian_hits": "not held",
        "chamber": "mailboxes are not flagged as chambers",
        "phone_number": "not held",
        "email": account.email if account else None,
        "history_bytes": hist.size_bytes if hist else 0,
        "history_is": "sealed blob; operator cannot read" if hist else "none",
        "history_files": int(n_files or 0),
        "history_extras": int(n_extras or 0),
    }


@app.websocket("/v1/wake")
async def ws_wake(ws: WebSocket):
    token = ws.query_params.get("token") or ""
    from .auth import parse_token

    try:
        principal = parse_token(token)
    except HTTPException:
        await ws.close(code=4401)
        return
    await ws.accept()
    await wake.register(principal.mailbox_id, ws)
    try:
        await ws.send_json({"v": 1, "wake": True, "hello": True})
        while True:
            # Client may ping. Ignore payload content — never echo names.
            await ws.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        wake.unregister(principal.mailbox_id, ws)
