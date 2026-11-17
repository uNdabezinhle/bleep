import { describe, expect, it } from "vitest";
import { ml_kem768 } from "@noble/post-quantum/ml-kem.js";
import { hybridRoot, pqDecaps, pqEncaps, pqPublic } from "./pq";

describe("ML-KEM-768 hybrid", () => {
  it("round-trips a shared secret and leaves the X3DH root alone when there is no KEM", () => {
    const seed = new Uint8Array(64).fill(7);
    const pub = pqPublic(seed);
    expect(pub.length).toBe(1184);
    const msg = new Uint8Array(32).fill(0x11);
    const enc = ml_kem768.encapsulate(pub, msg);
    expect(enc.cipherText.length).toBe(1088);
    expect(enc.sharedSecret.length).toBe(32);
    expect(pqDecaps(enc.cipherText, seed)).toEqual(enc.sharedSecret);
    const random = pqEncaps(pub);
    expect(pqDecaps(random.cipherText, seed)).toEqual(random.sharedSecret);
    const x3dh = new Uint8Array(32).fill(3);
    expect(hybridRoot(x3dh, null)).toEqual(x3dh);
    expect(hybridRoot(x3dh, enc.sharedSecret)).not.toEqual(x3dh);
  });
});
