import { describe, expect, it } from "vitest";
import { initAlice } from "../protocol/ratchet";
import { planAttempts, roundTripRatchet } from "./sessionLock";

describe("shared session lock", () => {
  it("round-trips ns and nr through the dump both clients store", () => {
    const state = initAlice(new Uint8Array(32).fill(4), new Uint8Array(32).fill(5));
    state.ns = 3;
    state.nr = 2;
    const back = roundTripRatchet(state);
    expect(back.ns).toBe(3);
    expect(back.nr).toBe(2);
    expect(back.dhsPk.length).toBe(32);
  });

  it("does not start a second X3DH when the blob already has a session and the push conflicts", () => {
    const locked = planAttempts([
      { shared: true, live: false, conflict: true },
      { shared: true, live: false, conflict: true },
    ]);
    expect(locked.x3dh).toBe(0);
    expect(locked.stopped).toBe(true);
  });

  it("starts X3DH once when the blob has no session", () => {
    const first = planAttempts([{ shared: false, live: false, conflict: false }]);
    expect(first.x3dh).toBe(1);
    expect(first.stopped).toBe(false);
  });
});
