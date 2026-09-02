# Bleep protocol bind (Personal v1)

Recorded for BLEEP-TM-001 §12 and BLEEP-SDLC-002: the library and construction
used by this tree. This is not a paid audit. Store text must not say “secure”
or “audited” until that review exists.

## Library

| Layer | Construction | Library |
| --- | --- | --- |
| Identity sign | Ed25519 | client: `@noble/curves` · relay: PyNaCl |
| DH | X25519 | `@noble/curves` |
| AEAD | ChaCha20-Poly1305 | `@noble/ciphers` |
| KDF | HKDF-SHA-256 | `@noble/hashes` |
| 1:1 session | X3DH + Double Ratchet (Signal lineage) | this repo `client/src/protocol` |
| PQ hybrid | not in v1 | residual; add when a reviewed construction exists |
| Group v1 | signed membership on clients; pairwise sealed fan-out | residual T7 group leak |
| Store wrap | PIN → PBKDF2-SHA-256 → AES-256-GCM | Web Crypto |
| Relay bodies | none | relays never see inner plaintext |

## Identity

A Bleep ID is a device-generated Ed25519 + X25519 pair. No phone, email, or
OTP is required (T8). The mailbox routing ID is random and is not the identity
key. Remote unlink marks the mailbox dead and the client registers a new
routing ID (T13).

Bleep code (also the QR payload):

```
BLEEP1:<region>:<mailbox_id>:<ed25519_b64url>:<x25519_b64url>
```

Safety number: 60 decimal digits, groups of 5, from SHA-256 of the sorted
pair of Ed25519 public keys. A key-change is a blocking warning, not a toast
(T5).

## Sealed mailbox

Relays store `{dest_mailbox_id, blob, created_at, expires_at, size}`. There is
**no sender column**. Default TTL is 36 hours, cap 36 hours (hours, not
months). The blob is:

```
version (1 byte) || eph_x25519_pub (32) || nonce (12) || chacha20poly1305(inner)
```

Outer key = HKDF(DH(eph, dest_identity_x25519), info=`bleep-outer-v1`). Only
the destination identity private key opens it. Inner JSON names the sender,
carries the ratchet header, and is Ed25519-signed.

Authenticated drop is required (mailbox token) so unsigned mail cannot fill a
box (T24/T25). The authenticated mailbox ID is used for quota and then
discarded — it is not written next to the destination (T7).

Wake-up (WebSocket) payload is `{"v":1,"wake":true}` only (T10).

## Holdings the relay may produce

See `GET /v1/ops/holdings/{mailbox_id}` (ops token) and BLEEP-SDLC-008. Typical
yield: mailbox created band, last connect hour-band, whether a handle was
published, envelope count still inside TTL. Never plaintext, never a
recipient×sender edge list, never Guardian hits, never a Chamber flag.

## Region

Default and only v1 pin: `ZA-JHB`. Clients send `X-Bleep-Region`. A mismatch
returns `421` and the client fails closed (T21). Silent roam is a bug.

## Chamber and Guardian

Chamber and Guardian are client modes, not relay features. A Chamber is a
fresh keypair + mailbox + store + lock + burn deadline. Relays must not mark
mailboxes as Chambers. Guardian runs on the composer buffer before `seal()`
and on inbound plaintext after decrypt. It never phones home.
