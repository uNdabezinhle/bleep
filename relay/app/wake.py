from fastapi import WebSocket

# mailbox_id -> live sockets. Wake payload is opaque.
_sockets: dict[str, set[WebSocket]] = {}


async def register(mailbox_id: str, ws: WebSocket) -> None:
    _sockets.setdefault(mailbox_id, set()).add(ws)


def unregister(mailbox_id: str, ws: WebSocket) -> None:
    group = _sockets.get(mailbox_id)
    if not group:
        return
    group.discard(ws)
    if not group:
        _sockets.pop(mailbox_id, None)


async def ping(mailbox_id: str) -> None:
    group = list(_sockets.get(mailbox_id, ()))
    dead: list[WebSocket] = []
    for ws in group:
        try:
            await ws.send_json({"v": 1, "wake": True})
        except Exception:
            dead.append(ws)
    for ws in dead:
        unregister(mailbox_id, ws)
