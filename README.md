# Bleep

Personal messenger whose job is to keep conversation content unreadable to anyone but the participants, keep the social graph from forming on infrastructure we operate, and let the user choose where sealed packets live.

Parents: **BLEEP-TM-001 v0.1**, **BLEEP-FC-001 v0.3**, SDLC pack in `Bleep-SDLC-pack/`.

This tree is Loop 1 + Loop 2 of that pack, runnable as a **one-command box**:

- **Relay** — Public Bleep sealed mailboxes (ZA-JHB pin, short TTL, no sender column, no search).
- **Invite client** — protocol-compatible web vault so two people can exercise the loop without an APK. The production vault in the charter is a signed Android client; this browser store is labelled as such and is not a hosted PWA Chamber.
- **Hash page** — `/hashes.html` (T6 placeholder).
- **TURN** — in-compose coturn. Names never go there.

It is a working draft implementation, **not an audit**. Store text must not say “secure” or “audited”.

## Run (containerized)

```bash
docker compose up --build
```

The invite client is published on **http://localhost:8090** (8080 is often taken by other local stacks).

Open:

- http://localhost:8090/?p=alice
- http://localhost:8090/?p=bob  (other window / profile)

Create a Bleep ID (no phone number). Add the other person by QR or by pasting the `BLEEP1:…` code. First contact is a **request to message**.

## What ships here (FC-001)

| Loop | Present |
| --- | --- |
| 1 | Device keypair ID, QR/code add, request-to-message, text + photo + voice note, reactions, disappearing, Bleep lock, empty wake-up, visible region chip, fail-closed region, Guardian checksums, E2EE receipts/typing (Chamber default off), signed edit, view-once with honest capture warning |
| 2 | Small groups (explicit invite + join), Status to accepted peers, Chamber (fresh ID, lock, burn, honest screenshot copy), inbound Guardian, block, Low Data / unknown-sender tap-to-open, 1:1 voice and video (P2P then in-region TURN, missed-call row, no names on TURN) |
| 3 | Encrypted export + restore (forced passphrase), remote unlink + erase this device, offline send queue |
| Never | Address-book upload, last-seen, ads SDK, cloud backup, cloud model on bodies, Guardian-as-a-contact, hosted PWA Chamber, decoy vault |

## Local tests (no Docker)

Relay:

```bash
cd relay
python -m pip install -r requirements.txt
python -m pytest -q
```

Client:

```bash
cd client
npm install
npm test
npm run dev
```

Dev client proxies `/v1` to `http://127.0.0.1:8000`. Run the relay with SQLite:

```bash
cd relay
python -m uvicorn app.main:app --reload --port 8000
```

## Compulsion drill

```bash
python scripts/compulsion_drill.py
python scripts/compulsion_drill.py <mailbox_id>
```

Typical yield matches `BLEEP-SDLC-008`: mailbox created band, last connect hour-band, optional handle, sealed envelope count. **No transcript. No who-talked-to-whom.**

## Protocol

See `docs/PROTOCOL.md`. Crypto is Signal-lineage X3DH + double ratchet (`@noble/curves`, ChaCha20-Poly1305). Relays never hold inner plaintext.

## Android (Loop 1)

Native Kotlin / Compose client in `android/`. Same sealed-mailbox protocol. Needs **JDK 17+** (this machine: Temurin 21).

```bash
cd android
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.4.7-hotspot
gradlew.bat assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk`. Emulator talks to Public Bleep at `http://10.0.2.2:8090` (editable on setup). Loop 1 + phone-died: ID, QR, request-to-message, text/photo/voice, Guardian, blocking key-change, opaque local notifications (no preview), offline send queue, encrypted export/restore, remote unlink, erase this device. Not Play-signed. Not FCM. Not an audit. Hash: `android/APK-HASH.txt`.

The invite web client remains the two-browser loop for proving relays (TV-L1-01…07) before a Play listing.
