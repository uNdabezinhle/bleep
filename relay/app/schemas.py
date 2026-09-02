from pydantic import BaseModel, Field


class ChallengeOut(BaseModel):
    nonce: str
    region: str


class TokenOut(BaseModel):
    token: str
    mailbox_id: str
    region: str


class AuthIn(BaseModel):
    mailbox_id: str
    nonce: str
    signature_b64: str


class PrekeyIn(BaseModel):
    key_id: int
    pub_b64: str


class SignedPrekeyIn(PrekeyIn):
    sig_b64: str


class RegisterIn(BaseModel):
    mailbox_id: str = Field(min_length=16, max_length=64)
    identity_ed25519_b64: str
    identity_x25519_b64: str
    signed_prekey: SignedPrekeyIn
    one_time_prekeys: list[PrekeyIn] = Field(default_factory=list, max_length=100)
    registration_sig_b64: str


class PrekeyBundleOut(BaseModel):
    mailbox_id: str
    identity_ed25519_b64: str
    identity_x25519_b64: str
    signed_prekey: SignedPrekeyIn
    one_time_prekey: PrekeyIn | None = None


class DropIn(BaseModel):
    dest_mailbox_id: str
    blob_b64: str
    ttl_seconds: int | None = None


class DropOut(BaseModel):
    id: str
    expires_at: str


class EnvelopeOut(BaseModel):
    id: str
    blob_b64: str
    expires_at: str


class HandleIn(BaseModel):
    handle: str


class HandleOut(BaseModel):
    handle: str
    mailbox_id: str
    identity_ed25519_b64: str
    identity_x25519_b64: str


class HealthOut(BaseModel):
    ok: bool
    service: str
    region: str
    fail_closed: bool
    env: str
    holdings: str
