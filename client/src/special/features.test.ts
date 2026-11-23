import { describe, expect, it } from "vitest";
import {
  applyVote,
  closePoll,
  enqueueLater,
  inQuietHours,
  LATER_CAP,
  mergePoll,
  mergeStar,
  RING_TEXT,
  RING_TITLE,
  shouldAlert,
  takeDue,
  tally,
  type LaterItem,
  type PollState,
} from "./features";

const poll = (): PollState => ({ question: "Lunch?", options: ["Yes", "No"], votes: {} });

describe("special features", () => {
  it("keeps quiet hours off when the range is empty or zero", () => {
    expect(inQuietHours(null, 60, 30)).toBe(false);
    expect(inQuietHours(60, 60, 60)).toBe(false);
  });

  it("wraps quiet hours past midnight", () => {
    expect(inQuietHours(22 * 60, 7 * 60, 23 * 60)).toBe(true);
    expect(inQuietHours(22 * 60, 7 * 60, 6 * 60)).toBe(true);
    expect(inQuietHours(22 * 60, 7 * 60, 12 * 60)).toBe(false);
    expect(inQuietHours(9 * 60, 17 * 60, 12 * 60)).toBe(true);
    expect(inQuietHours(9 * 60, 17 * 60, 8 * 60)).toBe(false);
  });

  it("sends a due item once and holds a future item", () => {
    const items: LaterItem[] = [
      { id: "a", threadId: "dm:1", text: "later", sendAt: 500 },
      { id: "b", threadId: "dm:1", text: "soon", sendAt: 100 },
    ];
    const split = takeDue(items, 200);
    expect(split.due.map((item) => item.id)).toEqual(["b"]);
    expect(split.waiting.map((item) => item.id)).toEqual(["a"]);
    expect(enqueueLater([], { id: "c", threadId: "", text: "x", sendAt: 1 })).toBeNull();
    const full = Array.from({ length: LATER_CAP }, (_, i) => ({
      id: String(i),
      threadId: "t",
      text: "x",
      sendAt: 9,
    }));
    expect(enqueueLater(full, { id: "z", threadId: "t", text: "x", sendAt: 9 })).toBeNull();
  });

  it("replaces a member vote and ignores a non-member", () => {
    const members = ["aa", "bb"];
    const once = applyVote(poll(), "aa", 0, members);
    expect(once?.votes).toEqual({ aa: 0 });
    const twice = applyVote(once!, "aa", 1, members);
    expect(twice?.votes).toEqual({ aa: 1 });
    expect(tally(twice!)).toEqual([0, 1]);
    expect(applyVote(twice!, "zz", 0, members)).toBeNull();
    expect(applyVote(closePoll(twice!, "aa", "aa")!, "bb", 0, members)).toBeNull();
    expect(closePoll(twice!, "bb", "aa")).toBeNull();
  });

  it("keeps a star from either side and drops it when the message is deleted", () => {
    expect(mergeStar(true, false, false)).toBe(true);
    expect(mergeStar(false, true, false)).toBe(true);
    expect(mergeStar(true, true, true)).toBe(false);
    const merged = mergePoll(
      { question: "Lunch?", options: ["Yes", "No"], votes: { aa: 0 } },
      { question: "Lunch?", options: ["Yes", "No"], votes: { aa: 1, bb: 0 } },
    );
    expect(merged?.votes).toEqual({ aa: 1, bb: 0 });
  });

  it("alerts for new mail and stays quiet for a vote, a call, and quiet hours", () => {
    expect(shouldAlert("text", { enabled: true })).toBe(true);
    expect(shouldAlert("photo")).toBe(true);
    expect(shouldAlert("poll")).toBe(true);
    expect(shouldAlert("poll-close")).toBe(true);
    expect(shouldAlert("intro")).toBe(true);
    expect(shouldAlert("ctrl", { op: "screen-capture" })).toBe(true);
    expect(shouldAlert("ctrl", { op: "status" })).toBe(true);
    expect(shouldAlert("ctrl", { op: "profile" })).toBe(true);
    expect(shouldAlert("ctrl", { op: "leave-group" })).toBe(true);
    expect(shouldAlert("typing")).toBe(false);
    expect(shouldAlert("receipt")).toBe(false);
    expect(shouldAlert("react")).toBe(false);
    expect(shouldAlert("vote")).toBe(false);
    expect(shouldAlert("call", { phase: "offer" })).toBe(false);
    expect(shouldAlert("call", { phase: "ice" })).toBe(false);
    expect(shouldAlert("ctrl", { op: "delete" })).toBe(false);
    expect(shouldAlert("location")).toBe(false);
    expect(shouldAlert("text", { quiet: true, enabled: true })).toBe(false);
    expect(shouldAlert("text", { enabled: false })).toBe(false);
  });

  it("uses a fixed ring notice with no peer field", () => {
    const notice = { title: RING_TITLE, text: RING_TEXT };
    expect(notice).toEqual({ title: "Bleep", text: " " });
    expect(Object.keys(notice)).toEqual(["title", "text"]);
  });
});
