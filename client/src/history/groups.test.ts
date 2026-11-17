import { describe, expect, it } from "vitest";
import type { GroupState } from "../types";
import { applyLeave } from "./groups";

function group(): GroupState {
  return {
    id: "g1",
    name: "Family",
    version: 1,
    members: [
      { mailboxId: "aa", edPk: "ed-a", role: "admin" },
      { mailboxId: "bb", edPk: "ed-b", role: "member" },
    ],
  };
}

describe("leave group", () => {
  it("removes the member and bumps the version", () => {
    const next = applyLeave(group(), "bb");
    expect(next?.changed).toBe(true);
    expect(next?.group.members.map((m) => m.mailboxId)).toEqual(["aa"]);
    expect(next?.group.version).toBe(2);
  });

  it("ignores a leave from someone who is not a member", () => {
    const next = applyLeave(group(), "cc");
    expect(next?.changed).toBe(false);
    expect(next?.group.version).toBe(1);
    expect(next?.group.members).toHaveLength(2);
  });
});
