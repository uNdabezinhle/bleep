import type { GroupState } from "../types";

/** Drop a member who sent leave-group. A mailbox that is not in the group is ignored. */
export function applyLeave(
  group: GroupState | undefined,
  leaver: string,
): { group: GroupState; changed: boolean } | null {
  if (!group) return null;
  if (!group.members.some((m) => m.mailboxId === leaver)) return { group, changed: false };
  return {
    changed: true,
    group: {
      ...group,
      members: group.members.filter((m) => m.mailboxId !== leaver),
      version: group.version + 1,
    },
  };
}
