import { describe, expect, it } from "vitest";
import { freshX } from "./keys";
import { initAlice, initBob, ratchetDecrypt, ratchetEncrypt, x3dhAlice, x3dhBob } from "./ratchet";
import { utf8, fromUtf8 } from "./bytes";

describe("X3DH + double ratchet", () => {
  it("round-trips and heals after a DH step", () => {
    const alice = freshX();
    const bob = freshX();
    const bobSpk = freshX();
    const eph = freshX();
    const skA = x3dhAlice(alice.sk, eph.sk, bob.pk, bobSpk.pk);
    const skB = x3dhBob(bob.sk, bobSpk.sk, alice.pk, eph.pk);
    expect([...skA]).toEqual([...skB]);

    const stA = initAlice(skA, bobSpk.pk);
    const stB = initBob(skB, bobSpk.sk, bobSpk.pk);

    const e1 = ratchetEncrypt(stA, utf8("sawubona"));
    const p1 = ratchetDecrypt(stB, e1.header, e1.ciphertext);
    expect(fromUtf8(p1)).toBe("sawubona");

    const e2 = ratchetEncrypt(stB, utf8("yebo"));
    const p2 = ratchetDecrypt(stA, e2.header, e2.ciphertext);
    expect(fromUtf8(p2)).toBe("yebo");

    const e3 = ratchetEncrypt(stA, utf8("voice note later"));
    const p3 = ratchetDecrypt(stB, e3.header, e3.ciphertext);
    expect(fromUtf8(p3)).toBe("voice note later");
  });
});
