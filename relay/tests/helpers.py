import base64
import secrets

from nacl.signing import SigningKey


def b64e(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def mailbox_id() -> str:
    return secrets.token_hex(16)


def keypair():
    sk = SigningKey.generate()
    # X25519: use 32 random bytes as private, derive public via nacl box
    from nacl.public import PrivateKey

    x = PrivateKey.generate()
    return {
        "ed_sk": sk,
        "ed_pk": bytes(sk.verify_key),
        "x_sk": x,
        "x_pk": bytes(x.public_key),
    }


def register_body():
    mid = mailbox_id()
    kp = keypair()
    spk_id = 1
    from nacl.public import PrivateKey

    spk = PrivateKey.generate()
    spk_msg = b"BLEEP-SPK-v1|" + spk_id.to_bytes(4, "big") + bytes(spk.public_key)
    spk_sig = kp["ed_sk"].sign(spk_msg).signature
    reg_msg = b"BLEEP-REG-v1|" + mid.encode() + b"|" + kp["x_pk"]
    reg_sig = kp["ed_sk"].sign(reg_msg).signature
    opks = []
    for i in range(5):
        k = PrivateKey.generate()
        opks.append({"key_id": i + 1, "pub_b64": b64e(bytes(k.public_key))})
    return {
        "mailbox_id": mid,
        "identity_ed25519_b64": b64e(kp["ed_pk"]),
        "identity_x25519_b64": b64e(kp["x_pk"]),
        "signed_prekey": {
            "key_id": spk_id,
            "pub_b64": b64e(bytes(spk.public_key)),
            "sig_b64": b64e(spk_sig),
        },
        "one_time_prekeys": opks,
        "registration_sig_b64": b64e(reg_sig),
        "_kp": kp,
    }


def dummy_blob() -> str:
    return b64e(b"\x01" + b"\x00" * 32 + b"\x11" * 12 + os_urandom(64))


def os_urandom(n: int) -> bytes:
    return secrets.token_bytes(n)
