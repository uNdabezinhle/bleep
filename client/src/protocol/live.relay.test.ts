import { describe, expect, it } from "vitest";
import { b64e } from "./bytes";
import { bleepCode, generateIdentity } from "./keys";
import { api } from "../net/api";
import {
  makePrekeys,
  openIncoming,
  peerFromCode,
  registerOnRelay,
  sendSealed,
  upsertPeer,
} from "../session/session";
import { emptySnapshot } from "../store/vault";

async function relayUp(): Promise<boolean> {
  try {
    const h = await api.health();
    return Boolean(h?.ok);
  } catch {
    return false;
  }
}

describe("live Public Bleep (TV-L1-01 / TV-L1-02)", () => {
  it("two device-generated IDs exchange sealed text; relay holds no transcript", async () => {
    if (!(await relayUp())) {
      console.warn("relay not reachable at VITE_RELAY_BASE — skip live test");
      return;
    }
    const alice = generateIdentity("Alice");
    const bob = generateIdentity("Bob");
    const aKeys = makePrekeys(8);
    const bKeys = makePrekeys(8);
    const aSnap = emptySnapshot(alice);
    const bSnap = emptySnapshot(bob);
    aSnap.prekeys = {
      spk: { id: aKeys.spk.id, sk: b64e(aKeys.spk.sk), pk: b64e(aKeys.spk.pk) },
      opks: aKeys.opks.map((k) => ({ id: k.id, sk: b64e(k.sk), pk: b64e(k.pk) })),
    };
    bSnap.prekeys = {
      spk: { id: bKeys.spk.id, sk: b64e(bKeys.spk.sk), pk: b64e(bKeys.spk.pk) },
      opks: bKeys.opks.map((k) => ({ id: k.id, sk: b64e(k.sk), pk: b64e(k.pk) })),
    };
    const aReg = await registerOnRelay(alice, aKeys.spk, aKeys.opks);
    const bReg = await registerOnRelay(bob, bKeys.spk, bKeys.opks);
    const bobPeer = peerFromCode(bleepCode(bob));
    bobPeer.accepted = true;
    bobPeer.displayName = "Bob";
    upsertPeer(aSnap, bobPeer);

    await sendSealed(aSnap, aReg.token, bobPeer, {
      kind: "text",
      id: "tv-l1-01",
      text: "sawubona",
    });

    const mail = (await api.fetch(bReg.token)) as { blob_b64: string }[];
    expect(mail.length).toBeGreaterThan(0);
    expect(JSON.stringify(mail[0])).not.toContain(alice.mailboxId);

    const inc = openIncoming(bSnap, mail[0].blob_b64, bKeys);
    expect(inc).not.toBeNull();
    expect(inc!.payload).toMatchObject({ kind: "text", text: "sawubona" });

    const hold = await fetch(`http://127.0.0.1:8090/v1/ops/holdings/${alice.mailboxId}`, {
      headers: { Authorization: "Bearer dev-ops" },
    });
    const body = await hold.json();
    expect(body.transcript).toBe("not held");
    expect(String(body.who_they_talked_to)).toMatch(/not held/i);
  }, 30_000);
});
