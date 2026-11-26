"""Account login, devices, sealed history, and the handle directory.

The identity private key and the recovery code are not accepted here.
"""

from __future__ import annotations

import hashlib
import re
import secrets
import smtplib
from datetime import timedelta, timezone
from email.message import EmailMessage

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from .auth import Principal, consume_challenge, mint_account_token, mint_token, parse_account_token, require_mailbox
from .b64 import b64d, b64e
from .config import settings
from .crypto import auth_message, verify_ed25519
from .db import get_session
from .models import Account, ConfirmToken, Device, Envelope, HistoryBlob, HistoryExtra, HistoryFile, Mailbox, OneTimePrekey, ResetToken
from .rate import check
from .util import client_ip, hour_band, new_id, require_region, utcnow, valid_mailbox_id

router = APIRouter()
_ph = PasswordHasher()
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
SAME_LOGIN = "Email or password is wrong."
RESET_COPY = "If that inbox is ours, a link is on its way."
RESET_OFF = "Password reset mail is not configured on this box."
CONFIRM_OFF = "This address was not confirmed because reset mail is not configured on this box."
CONFIRM_SENT = "Check the inbox to confirm this address. The link does not contain the recovery code."
ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,80}$")


class AccountIn(BaseModel):
    email: str
    password: str = Field(min_length=8, max_length=200)


class DeviceIn(BaseModel):
    label: str = Field(default="browser", max_length=64)
    mailbox_id: str
    nonce: str
    signature_b64: str


class PasswordIn(BaseModel):
    current_password: str
    new_password: str = Field(min_length=8, max_length=200)


class RecoverIn(BaseModel):
    email: str


class RecoverCompleteIn(BaseModel):
    token: str
    password: str = Field(min_length=8, max_length=200)


class HistoryIn(BaseModel):
    generation: int = Field(ge=1)
    nonce_b64: str
    blob_b64: str


class CloseIn(BaseModel):
    password: str


class ConfirmIn(BaseModel):
    token: str


class FileIn(BaseModel):
    nonce_b64: str
    blob_b64: str
    mime: str = "application/octet-stream"
    name: str = "file"


class ExtraIn(BaseModel):
    generation: int = Field(ge=1)
    nonce_b64: str
    blob_b64: str


EXTRA_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


def _email(value: str) -> str:
    email = value.strip().lower()
    if not EMAIL_RE.match(email) or len(email) > 254:
        raise HTTPException(status_code=400, detail="bad email")
    return email


def _hash_cred(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def require_account(authorization: str | None = Header(default=None)) -> str:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="account token required")
    return parse_account_token(authorization.split(" ", 1)[1].strip())


async def guard_device(principal: Principal, session: AsyncSession) -> Mailbox:
    box = await session.get(Mailbox, principal.mailbox_id)
    if not box or box.unlink_dead:
        raise HTTPException(status_code=401, detail="unknown mailbox")
    if box.account_id and not principal.device_id:
        raise HTTPException(status_code=401, detail="device credential required")
    if principal.device_id:
        dev = await session.get(Device, principal.device_id)
        if not dev or dev.account_id != box.account_id:
            raise HTTPException(status_code=401, detail="device revoked")
        if dev.erase_requested:
            raise HTTPException(status_code=403, detail="erase")
        if dev.revoked:
            raise HTTPException(status_code=401, detail="device revoked")
    return box


def smtp_configured() -> bool:
    return bool(settings.smtp_host and settings.smtp_from)


def _send_link(email: str, subject: str, link: str) -> None:
    msg = EmailMessage()
    msg["From"] = settings.smtp_from
    msg["To"] = email
    msg["Subject"] = subject
    msg.set_content(
        f"{link}\n\n"
        "This link expires in 30 minutes. It does not contain your recovery code.\n"
    )
    with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=15) as smtp:
        smtp.starttls()
        if settings.smtp_user:
            smtp.login(settings.smtp_user, settings.smtp_pass)
        smtp.send_message(msg)


def _send_reset(email: str, token: str) -> None:
    link = f"{settings.bleep_public_origin.rstrip('/')}/register?reset={token}"
    _send_link(email, "Bleep password reset", link)


@router.post("/v1/accounts", status_code=201)
async def create_account(
    body: AccountIn,
    request: Request,
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    check(f"acct:{client_ip(request)}", 20, window=3600)
    email = _email(body.email)
    taken = await session.scalar(select(Account).where(Account.email == email))
    if taken:
        raise HTTPException(status_code=409, detail="email taken")
    confirmed = not smtp_configured()
    row = Account(
        id=new_id(16),
        email=email,
        password_hash=_ph.hash(body.password),
        email_confirmed=confirmed,
        created_at=utcnow(),
    )
    session.add(row)
    mail = "unconfigured"
    detail = CONFIRM_OFF
    if not confirmed:
        token = secrets.token_urlsafe(32)
        session.add(
            ConfirmToken(
                id=new_id(16),
                account_id=row.id,
                token_hash=_hash_cred(token),
                expires_at=utcnow() + timedelta(minutes=30),
                used=False,
            )
        )
        mail = "sent"
        detail = CONFIRM_SENT
    await session.commit()
    if mail == "sent":
        link = f"{settings.bleep_public_origin.rstrip('/')}/register?confirm={token}"
        try:
            _send_link(row.email, "Bleep address confirm", link)
        except Exception:
            pass
    return {
        "account_id": row.id,
        "account_token": mint_account_token(row.id),
        "email_confirmed": confirmed,
        "mail": mail,
        "detail": detail,
    }


@router.post("/v1/accounts/login")
async def login_account(
    body: AccountIn,
    request: Request,
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    check(f"login:{client_ip(request)}", 30, window=600)
    email = _email(body.email)
    row = await session.scalar(select(Account).where(Account.email == email))
    if not row:
        raise HTTPException(status_code=401, detail=SAME_LOGIN)
    try:
        _ph.verify(row.password_hash, body.password)
    except VerifyMismatchError:
        raise HTTPException(status_code=401, detail=SAME_LOGIN) from None
    box = await session.scalar(select(Mailbox).where(Mailbox.account_id == row.id, Mailbox.unlink_dead.is_(False)))
    prior_mailbox = box or await session.scalar(select(Mailbox.id).where(Mailbox.account_id == row.id).limit(1))
    return {
        "account_id": row.id,
        "account_token": mint_account_token(row.id),
        "mailbox_id": box.id if box else None,
        "setup_pending": row.email_confirmed and prior_mailbox is None,
        "email_confirmed": row.email_confirmed,
    }


@router.post("/v1/accounts/password")
async def change_password(
    body: PasswordIn,
    account_id: str = Depends(require_account),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    row = await session.get(Account, account_id)
    if not row:
        raise HTTPException(status_code=401, detail=SAME_LOGIN)
    try:
        _ph.verify(row.password_hash, body.current_password)
    except VerifyMismatchError:
        raise HTTPException(status_code=401, detail=SAME_LOGIN) from None
    row.password_hash = _ph.hash(body.new_password)
    await session.commit()
    return {"ok": True}


@router.post("/v1/accounts/devices", status_code=201)
async def add_device(
    body: DeviceIn,
    account_id: str = Depends(require_account),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    # Serialize first-device enrollment, including on SQLite.
    await session.execute(update(Account).where(Account.id == account_id).values(id=account_id))
    account = await session.get(Account, account_id)
    if not account or not account.email_confirmed:
        raise HTTPException(status_code=403, detail="confirm email")
    valid_mailbox_id(body.mailbox_id)
    box = await session.get(Mailbox, body.mailbox_id)
    if not box or box.unlink_dead:
        raise HTTPException(status_code=401, detail="unknown mailbox")
    if box.account_id and box.account_id != account_id:
        raise HTTPException(status_code=409, detail="mailbox linked elsewhere")
    if box.account_id is None:
        linked = await session.scalar(select(Mailbox.id).where(Mailbox.account_id == account_id).limit(1))
        if linked is not None:
            raise HTTPException(status_code=409, detail="account already has a mailbox")
    try:
        sig = b64d(body.signature_b64)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="bad b64") from exc
    if not consume_challenge(body.nonce):
        raise HTTPException(status_code=401, detail="challenge expired")
    if not verify_ed25519(box.identity_ed25519, auth_message(body.mailbox_id, body.nonce), sig):
        raise HTTPException(status_code=401, detail="bad signature")
    box.account_id = account_id
    credential = secrets.token_urlsafe(32)
    dev = Device(
        id=new_id(16),
        account_id=account_id,
        label=body.label.strip() or "browser",
        credential_hash=_hash_cred(credential),
        created_band=hour_band(),
        revoked=False,
        erase_requested=False,
    )
    session.add(dev)
    from .shortid import assign_short_id

    short_id = await assign_short_id(session, box)
    await session.commit()
    return {
        "device_id": dev.id,
        "device_credential": credential,
        "token": mint_token(box.id, dev.id),
        "mailbox_id": box.id,
        "short_id": short_id,
        "handle": box.handle,
    }


@router.get("/v1/accounts/devices")
async def list_devices(
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    box = await guard_device(principal, session)
    if not box.account_id:
        return []
    rows = (
        await session.scalars(select(Device).where(Device.account_id == box.account_id))
    ).all()
    return [
        {
            "device_id": d.id,
            "label": d.label,
            "created_band": d.created_band,
            "revoked": d.revoked,
            "erase_requested": d.erase_requested,
            "this_device": d.id == principal.device_id,
        }
        for d in rows
    ]


@router.post("/v1/accounts/devices/{device_id}/revoke")
async def revoke_device(
    device_id: str,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    box = await guard_device(principal, session)
    dev = await session.get(Device, device_id)
    if not dev or dev.account_id != box.account_id:
        raise HTTPException(status_code=404, detail="no device")
    dev.revoked = True
    await session.commit()
    return {"ok": True}


@router.post("/v1/accounts/devices/{device_id}/erase")
async def erase_device_remote(
    device_id: str,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    box = await guard_device(principal, session)
    dev = await session.get(Device, device_id)
    if not dev or dev.account_id != box.account_id:
        raise HTTPException(status_code=404, detail="no device")
    dev.revoked = True
    dev.erase_requested = True
    await session.commit()
    return {"ok": True, "note": "The wipe runs when that device next contacts this box."}


@router.post("/v1/accounts/recover")
async def recover(
    body: RecoverIn,
    request: Request,
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    check(f"recover:{client_ip(request)}", 10, window=3600)
    if not smtp_configured():
        return {"ok": True, "mail": "unconfigured", "detail": RESET_OFF}
    email = body.email.strip().lower()
    row = await session.scalar(select(Account).where(Account.email == email)) if EMAIL_RE.match(email) else None
    if row:
        token = secrets.token_urlsafe(32)
        session.add(
            ResetToken(
                id=new_id(16),
                account_id=row.id,
                token_hash=_hash_cred(token),
                expires_at=utcnow() + timedelta(minutes=30),
                used=False,
            )
        )
        await session.commit()
        try:
            _send_reset(row.email, token)
        except Exception:
            pass
    return {"ok": True, "mail": "sent", "detail": RESET_COPY}


@router.post("/v1/accounts/recover/complete")
async def recover_complete(
    body: RecoverCompleteIn,
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    digest = _hash_cred(body.token)
    row = await session.scalar(select(ResetToken).where(ResetToken.token_hash == digest, ResetToken.used.is_(False)))
    exp = row.expires_at if row else None
    if exp is not None and exp.tzinfo is None:
        exp = exp.replace(tzinfo=timezone.utc)
    if not row or exp is None or exp < utcnow():
        raise HTTPException(status_code=400, detail="reset link expired")
    account = await session.get(Account, row.account_id)
    if not account:
        raise HTTPException(status_code=400, detail="reset link expired")
    account.password_hash = _ph.hash(body.password)
    row.used = True
    await session.commit()
    return {"ok": True}


@router.get("/v1/history")
async def get_history(
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    box = await guard_device(principal, session)
    if not box.account_id:
        raise HTTPException(status_code=404, detail="no history")
    row = await session.get(HistoryBlob, box.account_id)
    if not row:
        return {"generation": 0}
    return {
        "generation": row.generation,
        "nonce_b64": b64e(row.nonce),
        "blob_b64": b64e(row.blob),
    }


@router.put("/v1/history")
async def put_history(
    body: HistoryIn,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    box = await guard_device(principal, session)
    if not box.account_id or not principal.device_id:
        raise HTTPException(status_code=401, detail="device credential required")
    try:
        nonce = b64d(body.nonce_b64)
        blob = b64d(body.blob_b64)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="bad b64") from exc
    if len(nonce) != 12 or len(blob) > settings.bleep_envelope_max_bytes:
        raise HTTPException(status_code=400, detail="bad history")
    row = await session.get(HistoryBlob, box.account_id)
    if row and body.generation <= row.generation:
        raise HTTPException(status_code=409, detail="stale generation")
    now = utcnow()
    if row:
        row.generation = body.generation
        row.nonce = nonce
        row.blob = blob
        row.size_bytes = len(blob)
        row.updated_at = now
    else:
        session.add(
            HistoryBlob(
                account_id=box.account_id,
                generation=body.generation,
                nonce=nonce,
                blob=blob,
                size_bytes=len(blob),
                updated_at=now,
            )
        )
    await session.commit()
    return {"generation": body.generation}


@router.get("/v1/directory")
async def directory(
    q: str = "",
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    await guard_device(principal, session)
    check(f"dir:{principal.mailbox_id}", 60, window=600)
    query = q.strip().lower()
    if len(query) < 2 or not re.fullmatch(r"[a-z0-9_]+", query):
        return []
    rows = (
        await session.scalars(
            select(Mailbox)
            .where(
                Mailbox.handle.is_not(None),
                Mailbox.unlink_dead.is_(False),
                Mailbox.handle.startswith(query),
            )
            .limit(20)
        )
    ).all()
    return [
        {
            "handle": box.handle,
            "mailbox_id": box.id,
            "identity_ed25519_b64": b64e(box.identity_ed25519),
            "identity_x25519_b64": b64e(box.identity_x25519),
        }
        for box in rows
        if box.handle
    ]


def _sealed(nonce_b64: str, blob_b64: str) -> tuple[bytes, bytes]:
    try:
        nonce = b64d(nonce_b64)
        blob = b64d(blob_b64)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="bad b64") from exc
    if len(nonce) != 12 or len(blob) > settings.bleep_envelope_max_bytes:
        raise HTTPException(status_code=400, detail="bad blob")
    return nonce, blob


def _live_account(box: Mailbox, principal: Principal) -> str:
    if not box.account_id or not principal.device_id:
        raise HTTPException(status_code=401, detail="device credential required")
    return box.account_id


@router.post("/v1/accounts/close")
async def close_account(
    body: CloseIn,
    account_id: str = Depends(require_account),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    row = await session.get(Account, account_id)
    if not row:
        raise HTTPException(status_code=401, detail=SAME_LOGIN)
    try:
        _ph.verify(row.password_hash, body.password)
    except VerifyMismatchError:
        raise HTTPException(status_code=401, detail=SAME_LOGIN) from None
    boxes = (await session.scalars(select(Mailbox).where(Mailbox.account_id == account_id))).all()
    for box in boxes:
        box.unlink_dead = True
        box.handle = None
        box.account_id = None
        box.bytes_in_flight = 0
        await session.execute(delete(OneTimePrekey).where(OneTimePrekey.mailbox_id == box.id))
        await session.execute(delete(Envelope).where(Envelope.dest_mailbox_id == box.id))
    await session.execute(delete(Device).where(Device.account_id == account_id))
    await session.execute(delete(ResetToken).where(ResetToken.account_id == account_id))
    await session.execute(delete(ConfirmToken).where(ConfirmToken.account_id == account_id))
    await session.execute(delete(HistoryBlob).where(HistoryBlob.account_id == account_id))
    await session.execute(delete(HistoryFile).where(HistoryFile.account_id == account_id))
    await session.execute(delete(HistoryExtra).where(HistoryExtra.account_id == account_id))
    await session.delete(row)
    await session.commit()
    return {"ok": True}


@router.post("/v1/accounts/confirm")
async def confirm_account(
    body: ConfirmIn,
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    digest = _hash_cred(body.token)
    row = await session.scalar(
        select(ConfirmToken).where(ConfirmToken.token_hash == digest, ConfirmToken.used.is_(False))
    )
    exp = row.expires_at if row else None
    if exp is not None and exp.tzinfo is None:
        exp = exp.replace(tzinfo=timezone.utc)
    if not row or exp is None or exp < utcnow():
        raise HTTPException(status_code=400, detail="confirm link expired")
    account = await session.get(Account, row.account_id)
    if not account:
        raise HTTPException(status_code=400, detail="confirm link expired")
    account.email_confirmed = True
    row.used = True
    await session.commit()
    return {"ok": True}


@router.put("/v1/history/files/{file_id}")
async def put_history_file(
    file_id: str,
    body: FileIn,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    box = await guard_device(principal, session)
    account_id = _live_account(box, principal)
    if not ID_RE.fullmatch(file_id):
        raise HTTPException(status_code=400, detail="bad id")
    nonce, blob = _sealed(body.nonce_b64, body.blob_b64)
    mime = (body.mime or "application/octet-stream").replace("\n", "")[:128] or "application/octet-stream"
    name = (body.name or "file").replace("\n", "")[:200] or "file"
    row = await session.get(HistoryFile, (account_id, file_id))
    if row:
        row.nonce = nonce
        row.blob = blob
        row.size_bytes = len(blob)
        row.mime = mime
        row.name = name
    else:
        session.add(
            HistoryFile(
                account_id=account_id,
                file_id=file_id,
                nonce=nonce,
                blob=blob,
                size_bytes=len(blob),
                mime=mime,
                name=name,
            )
        )
    await session.commit()
    return {"ok": True}


@router.get("/v1/history/files/{file_id}")
async def get_history_file(
    file_id: str,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    box = await guard_device(principal, session)
    if not box.account_id:
        raise HTTPException(status_code=404, detail="no file")
    if not ID_RE.fullmatch(file_id):
        raise HTTPException(status_code=400, detail="bad id")
    row = await session.get(HistoryFile, (box.account_id, file_id))
    if not row:
        raise HTTPException(status_code=404, detail="no file")
    return {
        "nonce_b64": b64e(row.nonce),
        "blob_b64": b64e(row.blob),
        "mime": row.mime,
        "name": row.name,
    }


@router.get("/v1/history/extras")
async def list_history_extras(
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    box = await guard_device(principal, session)
    if not box.account_id:
        return []
    rows = (
        await session.scalars(select(HistoryExtra).where(HistoryExtra.account_id == box.account_id))
    ).all()
    return [{"id": row.extra_id, "generation": row.generation} for row in rows]


@router.get("/v1/history/extras/{extra_id}")
async def get_history_extra(
    extra_id: str,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    box = await guard_device(principal, session)
    if not box.account_id:
        raise HTTPException(status_code=404, detail="no extra")
    if not EXTRA_RE.fullmatch(extra_id):
        raise HTTPException(status_code=400, detail="bad id")
    row = await session.get(HistoryExtra, (box.account_id, extra_id))
    if not row:
        raise HTTPException(status_code=404, detail="no extra")
    return {
        "id": row.extra_id,
        "generation": row.generation,
        "nonce_b64": b64e(row.nonce),
        "blob_b64": b64e(row.blob),
    }


@router.put("/v1/history/extras/{extra_id}")
async def put_history_extra(
    extra_id: str,
    body: ExtraIn,
    principal: Principal = Depends(require_mailbox),
    session: AsyncSession = Depends(get_session),
    _region: str = Depends(require_region),
):
    box = await guard_device(principal, session)
    account_id = _live_account(box, principal)
    if not EXTRA_RE.fullmatch(extra_id):
        raise HTTPException(status_code=400, detail="bad id")
    nonce, blob = _sealed(body.nonce_b64, body.blob_b64)
    row = await session.get(HistoryExtra, (account_id, extra_id))
    if row and body.generation <= row.generation:
        raise HTTPException(status_code=409, detail="stale generation")
    now = utcnow()
    if row:
        row.generation = body.generation
        row.nonce = nonce
        row.blob = blob
        row.size_bytes = len(blob)
        row.updated_at = now
    else:
        session.add(
            HistoryExtra(
                account_id=account_id,
                extra_id=extra_id,
                generation=body.generation,
                nonce=nonce,
                blob=blob,
                size_bytes=len(blob),
                updated_at=now,
            )
        )
    await session.commit()
    return {"generation": body.generation}
