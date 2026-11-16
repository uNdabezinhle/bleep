import { describe, expect, it } from "vitest";
import { cycleMute, disappearAction, nextDisappear, replySnippet } from "./prefs";

describe("chat prefs", () => {
  it("cycles mute from off through 8 hours, a week, and until turned off", () => {
    const now = 1_700_000_000_000;
    const thread = { muted: false, mutedUntil: undefined as number | undefined };
    cycleMute(thread, now);
    expect(thread.muted).toBe(true);
    expect(thread.mutedUntil).toBe(now + 8 * 3600_000);
    cycleMute(thread, now);
    expect(thread.mutedUntil).toBe(now + 7 * 86400_000);
    cycleMute(thread, now);
    expect(thread.muted).toBe(true);
    expect(thread.mutedUntil).toBeUndefined();
    cycleMute(thread, now);
    expect(thread.muted).toBe(false);
  });

  it("cycles disappearing time", () => {
    expect(nextDisappear(null)).toBe(86400);
    expect(disappearAction(86400)).toBe("Disappear 7 days");
    expect(nextDisappear(604800)).toBe(7776000);
    expect(nextDisappear(7776000)).toBeNull();
  });

  it("keeps a short reply snippet", () => {
    expect(replySnippet("  hello   there ")).toBe("hello there");
    expect(replySnippet("", "photo")).toBe("Photo");
  });
});