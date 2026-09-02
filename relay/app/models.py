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
    unlink_dead: Mapped[bool] = mapped_column(Boolean, default=False)
    region: Mapped[str] = mapped_column(String(16), default="ZA-JHB")
    bytes_in_flight: Mapped[int] = mapped_column(Integer, default=0)


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
