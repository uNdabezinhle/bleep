import asyncio

from fastapi.testclient import TestClient

from app.db import Base, engine
from app.main import app
from tests.helpers import b64e, register_body

HDR = {"X-Bleep-Region": "ZA-JHB"}


def reset_db() -> None:
    async def _():
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.drop_all)
            await conn.run_sync(Base.metadata.create_all)

    asyncio.run(_())


def client() -> TestClient:
    reset_db()
    return TestClient(app)


def auth(token: str) -> dict:
    return {**HDR, "Authorization": f"Bearer {token}"}


def sign_in_device(c: TestClient, account_token: str, reg: dict, label: str) -> dict:
    ch = c.get("/v1/auth/challenge", headers=HDR).json()["nonce"]
    msg = b"BLEEP-AUTH-v1|" + reg["mailbox_id"].encode() + b"|" + ch.encode()
    sig = reg["_kp"]["ed_sk"].sign(msg).signature
    r = c.post(
        "/v1/accounts/devices",
        json={
            "label": label,
            "mailbox_id": reg["mailbox_id"],
            "nonce": ch,
            "signature_b64": b64e(sig),
        },
        headers=auth(account_token),
    )
    assert r.status_code == 201, r.text
    return r.json()


def test_signup_login_and_legacy_mailbox():
    with client() as c:
        made = c.post("/v1/accounts", json={"email": "A@Example.com", "password": "password1"}, headers=HDR)
        assert made.status_code == 201, made.text
        again = c.post("/v1/accounts", json={"email": "a@example.com", "password": "password1"}, headers=HDR)
        assert again.status_code == 409
        missing = c.post("/v1/accounts/login", json={"email": "no@example.com", "password": "password1"}, headers=HDR)
        wrong = c.post("/v1/accounts/login", json={"email": "a@example.com", "password": "nope-nope"}, headers=HDR)
        assert missing.status_code == wrong.status_code == 401
        assert missing.json()["detail"] == wrong.json()["detail"]
        region = c.post(
            "/v1/accounts",
            json={"email": "b@example.com", "password": "password1"},
            headers={"X-Bleep-Region": "EU-STO"},
        )
        assert region.status_code == 421

        body = register_body()
        kp = body.pop("_kp")
        reg = c.post("/v1/mailboxes", json=body, headers=HDR)
        assert reg.status_code == 201, reg.text
        legacy = {**body, **reg.json(), "_kp": kp}
        drop = c.post(
            "/v1/mail",
            json={"dest_mailbox_id": legacy["mailbox_id"], "blob_b64": b64e(b"\x01" + b"\x22" * 80)},
            headers=auth(legacy["token"]),
        )
        assert drop.status_code == 202, drop.text


def test_device_history_directory_erase():
    with client() as c:
        account = c.post("/v1/accounts", json={"email": "ada@example.com", "password": "password1"}, headers=HDR).json()
        body = register_body()
        kp = body.pop("_kp")
        box = c.post("/v1/mailboxes", json=body, headers=HDR)
        assert box.status_code == 201, box.text
        reg = {**body, **box.json(), "_kp": kp}
        one = sign_in_device(c, account["account_token"], reg, "phone")
        bad = c.put(
            "/v1/handles",
            json={"handle": "ada"},
            headers=auth(reg["token"]),
        )
        assert bad.status_code == 401
        named = c.put("/v1/handles", json={"handle": "ada"}, headers=auth(one["token"]))
        assert named.status_code == 200, named.text
        for identifier in ("ada", "@ADA"):
            login = c.post(
                "/v1/accounts/login",
                json={"email": identifier, "password": "password1"},
                headers=HDR,
            )
            assert login.status_code == 200, login.text
            assert login.json()["mailbox_id"] == reg["mailbox_id"]
            assert login.json()["email"] == "ada@example.com"
        wrong = c.post("/v1/accounts/login", json={"email": "ada", "password": "wrong-password"}, headers=HDR)
        assert wrong.status_code == 401
        found = c.get("/v1/directory", params={"q": "ad"}, headers=auth(one["token"]))
        assert found.status_code == 200, found.text
        hit = found.json()[0]
        assert hit["handle"] == "ada"
        assert "email" not in hit
        email_q = c.get("/v1/directory", params={"q": "ada@example.com"}, headers=auth(one["token"]))
        assert email_q.json() == []

        blob = b64e(b"sealed-history")
        nonce = b64e(b"0123456789ab")
        put = c.put(
            "/v1/history",
            json={"generation": 1, "nonce_b64": nonce, "blob_b64": blob},
            headers=auth(one["token"]),
        )
        assert put.status_code == 200, put.text
        got = c.get("/v1/history", headers=auth(one["token"]))
        assert got.json()["generation"] == 1
        assert got.json()["blob_b64"] == blob
        stale = c.put(
            "/v1/history",
            json={"generation": 1, "nonce_b64": nonce, "blob_b64": blob},
            headers=auth(one["token"]),
        )
        assert stale.status_code == 409

        two = sign_in_device(c, account["account_token"], reg, "laptop")
        erased = c.post(f"/v1/accounts/devices/{two['device_id']}/erase", headers=auth(one["token"]))
        assert erased.status_code == 200, erased.text
        blocked = c.put(
            "/v1/history",
            json={"generation": 2, "nonce_b64": nonce, "blob_b64": blob},
            headers=auth(two["token"]),
        )
        assert blocked.status_code == 403
        assert blocked.json()["detail"] == "erase"

        recover = c.post("/v1/accounts/recover", json={"email": "ada@example.com"}, headers=HDR)
        assert recover.status_code == 200
        assert recover.json()["mail"] == "unconfigured"
        other = c.post("/v1/accounts/recover", json={"email": "missing@example.com"}, headers=HDR)
        assert other.json()["detail"] == recover.json()["detail"]


def test_self_erase_leaves_the_other_device():
    with client() as c:
        account = c.post("/v1/accounts", json={"email": "bea@example.com", "password": "password1"}, headers=HDR).json()
        body = register_body()
        kp = body.pop("_kp")
        box = c.post("/v1/mailboxes", json=body, headers=HDR)
        reg = {**body, **box.json(), "_kp": kp}
        phone = sign_in_device(c, account["account_token"], reg, "phone")
        laptop = sign_in_device(c, account["account_token"], reg, "laptop")
        nonce = b64e(b"0123456789ab")
        blob = b64e(b"shared")
        put = c.put(
            "/v1/history",
            json={"generation": 1, "nonce_b64": nonce, "blob_b64": blob},
            headers=auth(laptop["token"]),
        )
        assert put.status_code == 200, put.text
        gone = c.post(f"/v1/accounts/devices/{phone['device_id']}/erase", headers=auth(phone["token"]))
        assert gone.status_code == 200, gone.text
        still = c.get("/v1/history", headers=auth(laptop["token"]))
        assert still.status_code == 200, still.text
        assert still.json()["blob_b64"] == blob
        wiped = c.get("/v1/history", headers=auth(phone["token"]))
        assert wiped.status_code == 403
        assert wiped.json()["detail"] == "erase"
        named = c.put("/v1/handles", json={"handle": "bea"}, headers=auth(laptop["token"]))
        assert named.status_code == 200, named.text


def test_close_frees_the_email():
    with client() as c:
        made = c.post("/v1/accounts", json={"email": "cara@example.com", "password": "password1"}, headers=HDR)
        assert made.status_code == 201
        account = made.json()
        body = register_body()
        kp = body.pop("_kp")
        box = c.post("/v1/mailboxes", json=body, headers=HDR)
        reg = {**body, **box.json(), "_kp": kp}
        dev = sign_in_device(c, account["account_token"], reg, "phone")
        c.put("/v1/handles", json={"handle": "cara"}, headers=auth(dev["token"]))
        nonce = b64e(b"0123456789ab")
        c.put(
            "/v1/history",
            json={"generation": 1, "nonce_b64": nonce, "blob_b64": b64e(b"hist")},
            headers=auth(dev["token"]),
        )
        c.put(
            "/v1/history/files/file-one1",
            json={"nonce_b64": nonce, "blob_b64": b64e(b"bytes"), "mime": "image/jpeg", "name": "a.jpg"},
            headers=auth(dev["token"]),
        )
        c.put(
            "/v1/history/extras/chamber01",
            json={"generation": 1, "nonce_b64": nonce, "blob_b64": b64e(b"chamber")},
            headers=auth(dev["token"]),
        )
        bad = c.post("/v1/accounts/close", json={"password": "nope-nope"}, headers=auth(account["account_token"]))
        assert bad.status_code == 401
        assert bad.json()["detail"] == "Email or password is wrong."
        closed = c.post(
            "/v1/accounts/close",
            json={"password": "password1"},
            headers=auth(account["account_token"]),
        )
        assert closed.status_code == 200, closed.text
        dead = c.get("/v1/history", headers=auth(dev["token"]))
        assert dead.status_code == 401
        again = c.post("/v1/accounts", json={"email": "cara@example.com", "password": "password2"}, headers=HDR)
        assert again.status_code == 201, again.text


def test_files_extras_and_unpublish():
    with client() as c:
        account = c.post("/v1/accounts", json={"email": "dina@example.com", "password": "password1"}, headers=HDR).json()
        body = register_body()
        kp = body.pop("_kp")
        box = c.post("/v1/mailboxes", json=body, headers=HDR)
        reg = {**body, **box.json(), "_kp": kp}
        dev = sign_in_device(c, account["account_token"], reg, "phone")
        nonce = b64e(b"0123456789ab")
        hist = b64e(b"main-history-only")
        c.put(
            "/v1/history",
            json={"generation": 1, "nonce_b64": nonce, "blob_b64": hist},
            headers=auth(dev["token"]),
        )
        put = c.put(
            "/v1/history/files/photo-abc",
            json={"nonce_b64": nonce, "blob_b64": b64e(b"jpeg-bytes"), "mime": "image/jpeg", "name": "shot.jpg"},
            headers=auth(dev["token"]),
        )
        assert put.status_code == 200, put.text
        got = c.get("/v1/history/files/photo-abc", headers=auth(dev["token"]))
        assert got.status_code == 200, got.text
        assert got.json()["name"] == "shot.jpg"
        assert got.json()["blob_b64"] == b64e(b"jpeg-bytes")
        main = c.get("/v1/history", headers=auth(dev["token"]))
        assert b"jpeg-bytes" not in main.content
        extra = c.put(
            "/v1/history/extras/chamber01",
            json={"generation": 1, "nonce_b64": nonce, "blob_b64": b64e(b"chamber-secret")},
            headers=auth(dev["token"]),
        )
        assert extra.status_code == 200, extra.text
        listed = c.get("/v1/history/extras", headers=auth(dev["token"]))
        assert listed.json() == [{"id": "chamber01", "generation": 1}]
        fetched = c.get("/v1/history/extras/chamber01", headers=auth(dev["token"]))
        assert fetched.json()["blob_b64"] == b64e(b"chamber-secret")
        main = c.get("/v1/history", headers=auth(dev["token"]))
        assert b"chamber-secret" not in main.content
        stale = c.put(
            "/v1/history/extras/chamber01",
            json={"generation": 1, "nonce_b64": nonce, "blob_b64": b64e(b"nope")},
            headers=auth(dev["token"]),
        )
        assert stale.status_code == 409
        c.put("/v1/handles", json={"handle": "dina"}, headers=auth(dev["token"]))
        c.delete("/v1/handles", headers=auth(dev["token"]))
        found = c.get("/v1/directory", params={"q": "di"}, headers=auth(dev["token"]))
        assert found.json() == []


def test_confirm_gates_a_second_device(monkeypatch):
    from app import accounts as accounts_mod
    from app.config import settings

    sent: list[str] = []

    def fake_send(email: str, subject: str, link: str) -> None:
        sent.append(link)

    monkeypatch.setattr(settings, "smtp_host", "mail.example")
    monkeypatch.setattr(settings, "smtp_from", "bleep@example")
    monkeypatch.setattr(accounts_mod, "_send_link", fake_send)
    try:
        with client() as c:
            made = c.post("/v1/accounts", json={"email": "erin@example.com", "password": "password1"}, headers=HDR)
            assert made.status_code == 201, made.text
            body = made.json()
            assert body["email_confirmed"] is False
            assert body["mail"] == "sent"
            assert "recovery" not in body["detail"].lower() or "does not contain" in body["detail"]
            assert sent and "confirm=" in sent[0]
            token = sent[0].split("confirm=", 1)[1]
            assert "password1" not in token
            reg_body = register_body()
            kp = reg_body.pop("_kp")
            box = c.post("/v1/mailboxes", json=reg_body, headers=HDR)
            reg = {**reg_body, **box.json(), "_kp": kp}
            blocked = c.post(
                "/v1/accounts/devices",
                json={
                    "label": "phone",
                    "mailbox_id": reg["mailbox_id"],
                    "nonce": "unused",
                    "signature_b64": "aa",
                },
                headers=auth(body["account_token"]),
            )
            assert blocked.status_code == 403
            assert blocked.json()["detail"] == "confirm email"
            done = c.post("/v1/accounts/confirm", json={"token": token}, headers=HDR)
            assert done.status_code == 200, done.text
            credentials = {"email": "erin@example.com", "password": "password1"}
            pending = c.post("/v1/accounts/login", json=credentials, headers=HDR).json()
            assert pending["setup_pending"] is True
            assert pending["mailbox_id"] is None
            again = c.post("/v1/accounts/confirm", json={"token": token}, headers=HDR)
            assert again.status_code == 400
            enrolled = sign_in_device(c, body["account_token"], reg, "phone")
            assert enrolled["device_id"]
            linked = c.post("/v1/accounts/login", json=credentials, headers=HDR).json()
            assert linked["setup_pending"] is False
            assert linked["mailbox_id"] == reg["mailbox_id"]
    finally:
        monkeypatch.setattr(settings, "smtp_host", "")
        monkeypatch.setattr(settings, "smtp_from", "")
