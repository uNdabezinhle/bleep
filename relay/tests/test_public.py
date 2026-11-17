from tests.test_relay import HDR, client, register


def test_channel_is_plaintext_and_presence_is_exact():
    with client() as c:
        a = register(c)
        b = register(c)
        auth = {**HDR, "Authorization": f"Bearer {a['token']}"}
        made = c.post("/v1/channels", json={"name": "Joburg"}, headers=auth)
        assert made.status_code == 201, made.text
        cid = made.json()["id"]
        assert made.json()["plaintext"] is True
        posted = c.post(f"/v1/channels/{cid}/posts", json={"text": "hello room", "name": "Ada"}, headers=auth)
        assert posted.status_code == 201, posted.text
        other = {**HDR, "Authorization": f"Bearer {b['token']}"}
        got = c.get(f"/v1/channels/{cid}/posts", headers=other)
        assert got.status_code == 200
        assert got.json()["posts"][0]["text"] == "hello room"
        c.get("/v1/mail", headers=auth)
        seen = c.get(f"/v1/presence?mailbox_id={a['mailbox_id']}", headers=other)
        assert seen.status_code == 200, seen.text
        row = seen.json()["items"][0]
        assert row["online"] is True
        assert row["last_seen"]
