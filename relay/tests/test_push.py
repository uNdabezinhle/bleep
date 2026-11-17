from tests.test_relay import HDR, client, register
from tests.helpers import dummy_blob
from app.push import WAKE, take_sent


def test_push_wake_has_no_message():
    with client() as c:
        a = register(c)
        b = register(c)
        auth = {**HDR, "Authorization": f"Bearer {b['token']}"}
        key = c.get("/v1/push/vapid", headers=auth)
        assert key.status_code == 200
        assert key.json()["public_key"] is None
        saved = c.put(
            "/v1/push",
            json={"endpoint": "https://push.example/sub", "p256dh": "p256dh-key", "auth": "auth-key"},
            headers=auth,
        )
        assert saved.status_code == 204, saved.text
        take_sent()
        blob = dummy_blob()
        dropped = c.post(
            "/v1/mail",
            json={"dest_mailbox_id": b["mailbox_id"], "blob_b64": blob},
            headers={**HDR, "Authorization": f"Bearer {a['token']}"},
        )
        assert dropped.status_code == 202, dropped.text
        sent = take_sent()
        assert sent == [WAKE]
        assert blob not in sent[0]
        assert b["mailbox_id"] not in sent[0]
