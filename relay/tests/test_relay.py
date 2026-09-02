import asyncio

from fastapi.testclient import TestClient
from sqlalchemy import inspect, select

from app.db import Base, engine
from app.main import app
from app.models import Envelope
from tests.helpers import dummy_blob, register_body

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


def register(c: TestClient):
    body = register_body()
    kp = body.pop("_kp")
    r = c.post("/v1/mailboxes", json=body, headers=HDR)
    assert r.status_code == 201, r.text
    return {**body, **r.json(), "_kp": kp}


def test_health_region():
    with client() as c:
        r = c.get("/v1/health", headers=HDR)
        assert r.status_code == 200
        assert r.json()["region"] == "ZA-JHB"
        assert r.json()["fail_closed"] is True


def test_wrong_region_fails_closed():
    with client() as c:
        r = c.get("/v1/health", headers={"X-Bleep-Region": "eu-west"})
        assert r.status_code == 421
        assert r.json()["detail"]["error"] == "region_mismatch"


def test_register_without_phone():
    with client() as c:
        acc = register(c)
        assert acc["mailbox_id"]
        assert acc["token"]


def test_envelope_has_no_sender_column():
    reset_db()

    async def cols():
        async with engine.begin() as conn:
            return await conn.run_sync(
                lambda syn: [col["name"] for col in inspect(syn).get_columns("envelopes")]
            )

    names = asyncio.run(cols())
    assert "sender" not in names
    assert "sender_mailbox_id" not in names
    assert "from_id" not in names
    assert "dest_mailbox_id" in names
    assert "blob" in names


def test_drop_does_not_persist_edge():
    with client() as c:
        a = register(c)
        b = register(c)
        r = c.post(
            "/v1/mail",
            json={"dest_mailbox_id": b["mailbox_id"], "blob_b64": dummy_blob(), "ttl_seconds": 3600},
            headers={**HDR, "Authorization": f"Bearer {a['token']}"},
        )
        assert r.status_code == 202, r.text

        async def rows():
            async with engine.connect() as conn:
                return (await conn.execute(select(Envelope))).all()

        got = asyncio.run(rows())
        assert len(got) == 1
        mapping = got[0]._mapping
        assert a["mailbox_id"] not in str(dict(mapping))
        assert mapping["dest_mailbox_id"] == b["mailbox_id"]


def test_fetch_is_opaque_blob():
    with client() as c:
        a = register(c)
        b = register(c)
        blob = dummy_blob()
        c.post(
            "/v1/mail",
            json={"dest_mailbox_id": b["mailbox_id"], "blob_b64": blob},
            headers={**HDR, "Authorization": f"Bearer {a['token']}"},
        )
        got = c.get("/v1/mail", headers={**HDR, "Authorization": f"Bearer {b['token']}"})
        assert got.status_code == 200
        items = got.json()
        assert len(items) == 1
        assert items[0]["blob_b64"] == blob
        assert "sender" not in items[0]


def test_holdings_match_le_guide():
    with client() as c:
        a = register(c)
        r = c.get(
            f"/v1/ops/holdings/{a['mailbox_id']}",
            headers={"Authorization": "Bearer test-ops"},
        )
        assert r.status_code == 200
        body = r.json()
        assert body["transcript"] == "not held"
        assert body["who_they_talked_to"].startswith("not held")
        assert body["guardian_hits"] == "not held"
        assert body["phone_number"] == "not held"


def test_ops_schema_lists_no_sender():
    with client() as c:
        r = c.get("/v1/ops/schema", headers={"Authorization": "Bearer test-ops"})
        assert r.status_code == 200
        env = " ".join(r.json()["tables"]["envelopes"])
        assert "NO sender" in env
        assert "message plaintext" in r.json()["not_held"]


def test_unlink_kills_mailbox():
    with client() as c:
        a = register(c)
        r = c.post(
            f"/v1/mailboxes/{a['mailbox_id']}/unlink",
            headers={**HDR, "Authorization": f"Bearer {a['token']}"},
        )
        assert r.status_code == 200
        r2 = c.get("/v1/mail", headers={**HDR, "Authorization": f"Bearer {a['token']}"})
        assert r2.status_code == 401
