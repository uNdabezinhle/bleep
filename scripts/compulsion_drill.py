"""Answer “what do we hold on user X?” from the live schema (SDLC-008)."""

from __future__ import annotations

import json
import os
import sys
import urllib.request

BASE = os.environ.get("BLEEP_BASE", "http://127.0.0.1:8090")
TOKEN = os.environ.get("BLEEP_OPS_TOKEN", "dev-ops")


def get(path: str) -> dict:
    req = urllib.request.Request(
        BASE + path, headers={"Authorization": f"Bearer {TOKEN}"}
    )
    with urllib.request.urlopen(req) as res:
        return json.loads(res.read().decode())


def main() -> int:
    mailbox = sys.argv[1] if len(sys.argv) > 1 else None
    schema = get("/v1/ops/schema")
    print("Live schema (not memory):")
    print(json.dumps(schema, indent=2))
    print()
    if not mailbox:
        print("Pass a mailbox id to print holdings for that routing id.")
        print("Typical yield: created band, last connect hour-band, optional handle,")
        print("sealed envelope count. Never a transcript, never who-talked-to-whom.")
        return 0
    hold = get(f"/v1/ops/holdings/{mailbox}")
    print(f"Holdings for {mailbox}:")
    print(json.dumps(hold, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
