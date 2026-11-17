from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, LargeBinary, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base


class Mailbox(Base):
    """Routing destination. Identity private keys never live here."""

    __tablename__ = "mailboxes"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    identity_ed25519: Mapped[bytes] = mapped_column(LargeBinary(32), unique=True)
    identity_x25519: Mapped[bytes] = mapped_column(LargeBinary(32))
    spk_id: Mapped[int] = mapped_column(Integer)
    spk_pub: Mapped[bytes] = mapped_column(LargeBinary(32))
    spk_sig: Mapped[bytes] = mapped_column(LargeBinary(64))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    last_connect_band: Mapped[str | None] = mapped_column(String(16), nullable=True)
    handle: Mapped[str | None] = mapped_column(String(24), unique=True, nullable=True)
    short_id: Mapped[str | None] = mapped_column(String(8), unique=True, nullable=True)
    unlink_dead: Mapped[bool] = mapped_column(Boolean, default=False)
    region: Mapped[str] = mapped_column(String(16), default="ZA-JHB")
    bytes_in_flight: Mapped[int] = mapped_column(Integer, default=0)
    account_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    mlkem_pub: Mapped[bytes | None] = mapped_column(LargeBinary, nullable=True)
    last_seen: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Channel(Base):
    """Public room. Posts are plaintext. The relay can read them."""

    __tablename__ = "channels"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    name: Mapped[str] = mapped_column(String(40), unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    owner_mailbox: Mapped[str] = mapped_column(String(64))


class ChannelPost(Base):
    __tablename__ = "channel_posts"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    channel_id: Mapped[str] = mapped_column(String(32), ForeignKey("channels.id"), index=True)
    author_mailbox: Mapped[str] = mapped_column(String(64))
    author_name: Mapped[str] = mapped_column(String(40))
    body: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class Account(Base):
    """Login record. Identity private keys never live here."""

    __tablename__ = "accounts"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    email: Mapped[str] = mapped_column(String(254), unique=True)
    password_hash: Mapped[str] = mapped_column(String(256))
    email_confirmed: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    account_id: Mapped[str] = mapped_column(String(32), ForeignKey("accounts.id"), index=True)
    label: Mapped[str] = mapped_column(String(64), default="device")
    credential_hash: Mapped[str] = mapped_column(String(64))
    created_band: Mapped[str] = mapped_column(String(16))
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)
    erase_requested: Mapped[bool] = mapped_column(Boolean, default=False)


class HistoryBlob(Base):
    """Sealed account history. Ciphertext only. No thread table."""

    __tablename__ = "history_blobs"

    account_id: Mapped[str] = mapped_column(String(32), ForeignKey("accounts.id"), primary_key=True)
    generation: Mapped[int] = mapped_column(Integer, default=0)
    nonce: Mapped[bytes] = mapped_column(LargeBinary(12))
    blob: Mapped[bytes] = mapped_column(LargeBinary)
    size_bytes: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class HistoryFile(Base):
    """Sealed attachment. Ciphertext only. Not a thread row."""

    __tablename__ = "history_files"

    account_id: Mapped[str] = mapped_column(String(32), ForeignKey("accounts.id"), primary_key=True)
    file_id: Mapped[str] = mapped_column(String(80), primary_key=True)
    nonce: Mapped[bytes] = mapped_column(LargeBinary(12))
    blob: Mapped[bytes] = mapped_column(LargeBinary)
    size_bytes: Mapped[int] = mapped_column(Integer)
    mime: Mapped[str] = mapped_column(String(128), default="application/octet-stream")
    name: Mapped[str] = mapped_column(String(200), default="file")


class HistoryExtra(Base):
    """Sealed side blob, keyed by an id the client chose. Ciphertext only."""

    __tablename__ = "history_extras"

    account_id: Mapped[str] = mapped_column(String(32), ForeignKey("accounts.id"), primary_key=True)
    extra_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    generation: Mapped[int] = mapped_column(Integer, default=0)
    nonce: Mapped[bytes] = mapped_column(LargeBinary(12))
    blob: Mapped[bytes] = mapped_column(LargeBinary)
    size_bytes: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class ConfirmToken(Base):
    __tablename__ = "confirm_tokens"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    account_id: Mapped[str] = mapped_column(String(32), ForeignKey("accounts.id"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    used: Mapped[bool] = mapped_column(Boolean, default=False)


class ResetToken(Base):
    __tablename__ = "reset_tokens"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    account_id: Mapped[str] = mapped_column(String(32), ForeignKey("accounts.id"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    used: Mapped[bool] = mapped_column(Boolean, default=False)


class OneTimePrekey(Base):
    __tablename__ = "one_time_prekeys"

    pk: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    mailbox_id: Mapped[str] = mapped_column(String(64), ForeignKey("mailboxes.id"), index=True)
    key_id: Mapped[int] = mapped_column(Integer)
    pub: Mapped[bytes] = mapped_column(LargeBinary(32))


class Envelope(Base):
    """Sealed blob addressed to one mailbox. No sender column (T7)."""

    __tablename__ = "envelopes"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    dest_mailbox_id: Mapped[str] = mapped_column(String(64), index=True)
    blob: Mapped[bytes] = mapped_column(LargeBinary)
    size_bytes: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)


class DemandLog(Base):
    """What we were asked, not what a chat contained."""

    __tablename__ = "demand_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    mailbox_id: Mapped[str] = mapped_column(String(64), index=True)
    kind: Mapped[str] = mapped_column(String(32))
    note: Mapped[str] = mapped_column(Text, default="")
