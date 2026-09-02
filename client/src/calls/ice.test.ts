import { describe, expect, it } from "vitest";
import { iceServers } from "./ice";

describe("TURN / STUN", () => {
  it("does not use a US public STUN vendor", () => {
    // jsdom location.hostname is localhost
    const urls = iceServers().flatMap((s) => (Array.isArray(s.urls) ? s.urls : [s.urls]));
    expect(urls.some((u) => u.includes("google"))).toBe(false);
    expect(urls.some((u) => u.includes("3478"))).toBe(true);
  });
});
