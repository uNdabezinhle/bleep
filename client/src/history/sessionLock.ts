import { dumpRatchet, loadRatchet, type RatchetState, type SerializedRatchet } from "../protocol/ratchet";

export const OTHER_DEVICE = "This chat is open on the other device. Wait, then send again.";

export type SendChoice = "shared" | "local" | "x3dh";

/** A session already in the shared blob is reused. X3DH runs only when neither side has one. */
export function x3dhChoice(sharedPresent: boolean, liveEstablished: boolean): SendChoice {
  if (sharedPresent) return "shared";
  if (liveEstablished) return "local";
  return "x3dh";
}

/** The history write is the lock. One conflict is retried. A second conflict stops before another X3DH. */
export function conflictStep(attempt: number): "retry" | "stop" {
  return attempt === 0 ? "retry" : "stop";
}

export function planAttempts(
  rounds: { shared: boolean; live: boolean; conflict: boolean }[],
): { x3dh: number; stopped: boolean } {
  let x3dh = 0;
  for (let attempt = 0; attempt < rounds.length && attempt < 2; attempt++) {
    const round = rounds[attempt];
    if (x3dhChoice(round.shared, round.live) === "x3dh") x3dh += 1;
    if (!round.conflict) return { x3dh, stopped: false };
    if (conflictStep(attempt) === "stop") return { x3dh, stopped: true };
  }
  return { x3dh, stopped: true };
}

export function isRatchetDump(value: unknown): value is SerializedRatchet {
  if (!value || typeof value !== "object") return false;
  const row = value as SerializedRatchet;
  return typeof row.dhsSk === "string" && typeof row.ns === "number" && typeof row.nr === "number";
}

export function mergeSharedDumps(
  local: Record<string, SerializedRatchet> | undefined,
  remote: {
    top?: Record<string, unknown> | null;
    webSessions?: Record<string, { ratchet?: unknown } | undefined> | null;
    androidRatchets?: Record<string, unknown> | null;
  },
): Record<string, SerializedRatchet> {
  const next = { ...(local ?? {}) };
  for (const [id, row] of Object.entries(remote.top ?? {})) {
    if (isRatchetDump(row)) next[id] = row;
  }
  for (const [id, row] of Object.entries(remote.androidRatchets ?? {})) {
    if (!next[id] && isRatchetDump(row)) next[id] = row;
  }
  for (const [id, rec] of Object.entries(remote.webSessions ?? {})) {
    const ratchet = rec?.ratchet;
    if (!next[id] && isRatchetDump(ratchet)) next[id] = ratchet;
  }
  return next;
}

export function roundTripRatchet(state: RatchetState): RatchetState {
  return loadRatchet(dumpRatchet(state));
}
