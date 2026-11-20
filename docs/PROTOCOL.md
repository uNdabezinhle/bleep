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
| PQ hybrid | ML-KEM-768 (FIPS 203) mixed by HKDF(X3DH \|\| KEM), info `bleep-pqxdh-v1` | `@noble/post-quantum` and Bouncy Castle 1.81. Outer envelope stays X25519. A peer with no ML-KEM key stays on X3DH. |
| Group v1 | signed membership on clients; pairwise sealed fan-out | residual T7 group leak |
| Store wrap | PIN → PBKDF2-SHA-256 → AES-256-GCM | Web Crypto |
| Account password | Argon2id verifier | relay: `argon2-cffi` |
| History blob | ChaCha20-Poly1305 under HKDF(recovery seed) | same AEAD as envelopes |
| Relay bodies | none | relays never see inner plaintext |

## Identity

A Bleep ID is an Ed25519 + X25519 pair made on the device. An account login is
an email and a password. The password opens the account. It does not open
messages. There is no phone number and no OTP.

For an account, the mailbox id and both private keys come from a 16-byte
recovery seed shown once on the device. The relay never receives that seed.

- Ed25519 seed = HKDF-SHA-256(seed, salt=`bleep-ed`, info=`ed25519`, 32)
- X25519 seed = HKDF-SHA-256(seed, salt=`bleep-x`, info=`x25519`, 32)
- mailbox id = hex(HKDF-SHA-256(seed, salt=`bleep-box`, info=`mailbox`, 16))

A device-only mailbox (no account) still uses a random mailbox id and random
keys. The mailbox routing ID is not the identity public key. Remote unlink
marks the mailbox dead and the client registers a new routing ID (T13).

`GET /v1/mail` returns sealed envelopes. A current client sends
`X-Bleep-Retain: 1`, and that fetch does not delete the rows. A fetch
without the header still deletes the rows it returned, so an older app
keeps working.
`POST /v1/mail/ack` deletes ids for that mailbox only. A client acks an
envelope after the signature checks and the ratchet opens. If the ratchet
does not open, the envelope stays until a device that can open it acks it,
or until the TTL sweep. There is still no sender column.

A 1:1 call is a sealed payload `kind=call`. `phase` is `offer`, `answer`,
`ice`, `hangup`, or `missed`. The SDP and the ICE candidate are inside that
payload. The relay cannot read them. TURN is that box on port 3478.
A signed-in phone asks `GET /v1/turn` for the user and password. The
website still receives those from its build. TURN does not receive a name. A group, a chamber, and a public channel have no
call. The call runs while that Bleep process is open.
A missed call is a thread row whose text is `Missed call`.

## Account, devices, history, directory

The relay stores the normalized email, an Argon2id verifier, one mailbox id,
and devices (id, label, SHA-256 credential hash, hour-band, revoked,
erase_requested). It does not store a private key, the recovery code, or a
plaintext vault.

Mailbox tokens: a legacy box is `{mailbox}.{exp}.{mac}`. An account-linked box
is `{mailbox}.{device}.{exp}.{mac}` and the token route requires a live device
credential. Erase sets `erase_requested` and revokes that credential. The wipe
runs the next time that device contacts the relay. A device that never comes
back keeps its local files. An erased device cannot upload history.

History is one sealed blob per account (ciphertext, 12-byte nonce, generation,
size). The key is HKDF-SHA-256(seed, salt=`bleep-hist`, info=`bleep-history-v1`,
32). There is no thread table and no sender column. Current blobs use
`kind=shared-v1`. Shared fields are peers, threads, messages (text and
attachment ids, not raw bytes), and requests. A direct thread id is `dm:`
plus the two mailbox ids in sorted order. A group thread id is `g:` plus
the group id. Both clients show that thread. Chamber threads stay out of
this blob. `sessions` holds one ratchet dump per peer, the same fields a
client already stores (`dhsSk`, `dhsPk`, `dhr`, `rk`, `cks`, `ckr`, `ns`,
`nr`, `pn`, `skipped`). A history pull keeps that dump beside the live
session and does not load it. A send pulls first. If the dump is there, that
send continues the same session and does not start another X3DH. The
advanced dump is pushed immediately. A second stale generation stops the
send. Each client also keeps a pocket the other must round-trip unchanged:
`web` holds that client's settings, `android` holds the Android prekey
snapshot. Older `web` and `android` blobs still decrypt. Private keys, the
recovery seed, and device credentials stay out of the blob. Leaving a group
is a sealed `ctrl` op `leave-group` to the current members. The relay still
cannot read the group.

Attachment bytes are separate rows at `PUT/GET /v1/history/files/{id}`
(nonce, ciphertext, size, mime, name), same history key, same size cap as an
envelope. A Chamber is not in the main blob. While unlocked, the client uploads
one sealed extra at `PUT /v1/history/extras/{id}`. That key is
HKDF(PIN, salt=chamber id, info=`bleep-chamber-v1`), not the account recovery
key. Opening Bleep does not open the Chamber.

`POST /v1/accounts/devices/{id}/erase` marks that device. It does not unlink
the mailbox. `POST /v1/accounts/close` checks the password, deletes the
account, devices, reset and confirm tokens, history blob, file rows, and extra
blobs, and marks the mailbox unlinked so the email can be reused. The password
is not stored as a reason and is not logged.

When SMTP is set, signup still creates the account and mails a single-use
confirm link (hashed, 30 minutes). A device cannot be added until that link is
opened. The link does not contain the recovery code. When SMTP is not set,
signup still works and the response says the address was not confirmed because
reset mail is not configured.

`GET /v1/directory?q=` is a prefix of a published handle, at least 2 characters,
authenticated with a mailbox token, 20 hits, rate-limited. Hits return the
handle and identity public keys. They never return email. Queries are not
stored. Exact `@handle` resolve stays. Search by email or display name is not
a route.

Password reset stores a single-use token hash for 30 minutes and replaces the
verifier only. The mail does not contain the recovery code. A known email and
an unknown email get the same sentence. Until SMTP is set, the response says
reset mail is not configured.

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

Wake-up (WebSocket and Web Push) payload is `{"v":1,"wake":true}` only (T10). A push subscription stores the browser endpoint. The notice does not include the message or the sender. Android listening is an opt-in foreground loop on the phone. It stops when the process is killed.

## Holdings the relay may produce

See `GET /v1/ops/holdings/{mailbox_id}` (ops token) and BLEEP-SDLC-008. Typical
yield: mailbox created band, last connect hour-band, whether a handle was
published, envelope count still inside TTL, the account email when the mailbox
is linked, the sealed history size, and counts of sealed file rows and extra
blobs. Never plaintext, never the recovery code, never a recipient×sender edge
list, never Guardian hits, never a Chamber flag. Reading history still takes
the recovery code or a device that already has the key. Reading a Chamber extra
takes the Chamber PIN.

## Region

Default and only v1 pin: `ZA-JHB`. Clients send `X-Bleep-Region`. A mismatch
returns `421` and the client fails closed (T21). Silent roam is a bug.

## Chamber and Guardian

A public channel post is plaintext on the relay. Any live mailbox can read it. Precise last-seen is the mailbox timestamp of the latest mail fetch, returned only to an authenticated mailbox, and “online” means that timestamp is under 45 seconds old. Live location is a sealed 1:1 message the sender starts and can stop. It is not a background tracker. The Android client sets FLAG_SECURE, which blocks the system screenshot and screen recording. A photograph of the screen still works. The browser cannot stop a screenshot.

Chamber and Guardian are client modes. A Chamber is a fresh keypair + mailbox
+ store + lock + burn deadline. The relay stores an extra ciphertext under an
id the client chose and does not mark mailboxes as Chambers. Guardian runs on
the composer buffer before `seal()` and on inbound plaintext after decrypt. It
never phones home.
