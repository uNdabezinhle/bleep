/** Device-side extras. The relay stores none of these fields. */

export type PollState = {
  question: string;
  options: string[];
  closed?: boolean;
  votes?: Record<string, number>;
};

export type LaterItem = {
  id: string;
  threadId: string;
  text: string;
  sendAt: number;
};

export const LATER_CAP = 20;
export const RING_TITLE = "Bleep";
export const RING_TEXT = " ";

export function inQuietHours(
  startMin: number | null | undefined,
  endMin: number | null | undefined,
  nowMin: number,
): boolean {
  if (startMin == null || endMin == null) return false;
  if (startMin < 0 || endMin < 0 || startMin > 1439 || endMin > 1439) return false;
  if (startMin === endMin) return false;
  const now = ((nowMin % 1440) + 1440) % 1440;
  if (startMin < endMin) return now >= startMin && now < endMin;
  return now >= startMin || now < endMin;
}

export function minutesOfClock(raw: string): number | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(raw.trim());
  if (!m) return null;
  const h = Number(m[1]);
  const min = Number(m[2]);
  if (h > 23 || min > 59) return null;
  return h * 60 + min;
}

export function clockOfMinutes(min: number | null | undefined): string {
  if (min == null || min < 0 || min > 1439) return "";
  const h = Math.floor(min / 60);
  const m = min % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

export function enqueueLater(items: LaterItem[], item: LaterItem): LaterItem[] | null {
  const text = item.text.trim();
  if (!text || !item.threadId || !(item.sendAt > 0)) return null;
  if (items.length >= LATER_CAP) return null;
  return [...items, { ...item, text }];
}

export function takeDue(items: LaterItem[], now: number): { due: LaterItem[]; waiting: LaterItem[] } {
  const due = items
    .filter((item) => item.sendAt <= now)
    .sort((a, b) => a.sendAt - b.sendAt || a.id.localeCompare(b.id));
  const dueIds = new Set(due.map((item) => item.id));
  return { due, waiting: items.filter((item) => !dueIds.has(item.id)) };
}

export function applyVote(
  poll: PollState,
  mailbox: string,
  option: number,
  members: readonly string[],
): PollState | null {
  if (poll.closed) return null;
  if (!mailbox || !members.includes(mailbox)) return null;
  if (!Number.isInteger(option) || option < 0 || option >= poll.options.length) return null;
  return { ...poll, votes: { ...(poll.votes ?? {}), [mailbox]: option } };
}

export function closePoll(poll: PollState, sender: string, author: string): PollState | null {
  if (!sender || sender !== author) return null;
  return { ...poll, closed: true };
}

export function tally(poll: PollState): number[] {
  const counts = poll.options.map(() => 0);
  for (const index of Object.values(poll.votes ?? {})) {
    if (index >= 0 && index < counts.length) counts[index] += 1;
  }
  return counts;
}

export function mergeStar(a?: boolean, b?: boolean, deleted?: boolean): boolean {
  if (deleted) return false;
  return Boolean(a || b);
}

export function mergePoll(a?: PollState, b?: PollState): PollState | undefined {
  if (!a) return b;
  if (!b) return a;
  return {
    question: b.question || a.question,
    options: b.options.length ? b.options : a.options,
    closed: Boolean(a.closed || b.closed),
    votes: { ...(a.votes ?? {}), ...(b.votes ?? {}) },
  };
}
