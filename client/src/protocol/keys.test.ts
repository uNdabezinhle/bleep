import { describe, expect, it } from "vitest";
import { formatShortId, handleFromInput, peerLabel, shortIdFromInput } from "./keys";

describe("Bleep ID or handle", () => {
  it("reads a handle and leaves a long code alone", () => {
    expect(handleFromInput("@Lebo")).toBe("lebo");
    expect(handleFromInput("lebo")).toBe("lebo");
    expect(handleFromInput("BLEEP1:ZA-JHB:abc:ed:x")).toBeNull();
    expect(handleFromInput("ab")).toBeNull();
    expect(handleFromInput("1lebo")).toBeNull();
  });
  it("reads a short id with or without a hyphen", () => {
    expect(shortIdFromInput("K7M2-PQ9X")).toBe("k7m2pq9x");
    expect(shortIdFromInput("k7m2pq9x")).toBe("k7m2pq9x");
    expect(formatShortId("k7m2pq9x")).toBe("k7m2-pq9x");
    expect(shortIdFromInput("oooooooo")).toBeNull();
    expect(handleFromInput("k7m2-pq9x")).toBeNull();
    expect(peerLabel("Lebo", "k7m2-pq9x")).toBe("@lebo");
    expect(peerLabel(null, "k7m2-pq9x")).toBe("No handle");
    expect(peerLabel(null, "Lebo")).toBe("Lebo");
  });
});
