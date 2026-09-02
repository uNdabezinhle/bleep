from nacl.exceptions import BadSignatureError
from nacl.signing import VerifyKey


def verify_ed25519(public_key: bytes, message: bytes, signature: bytes) -> bool:
    if len(public_key) != 32 or len(signature) != 64:
        return False
    try:
        VerifyKey(public_key).verify(message, signature)
        return True
    except (BadSignatureError, ValueError):
        return False


def registration_message(mailbox_id: str, identity_x25519: bytes) -> bytes:
    return b"BLEEP-REG-v1|" + mailbox_id.encode("utf-8") + b"|" + identity_x25519


def auth_message(mailbox_id: str, nonce: str) -> bytes:
    return b"BLEEP-AUTH-v1|" + mailbox_id.encode("utf-8") + b"|" + nonce.encode("utf-8")
