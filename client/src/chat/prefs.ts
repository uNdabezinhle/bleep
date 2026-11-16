export const HOUR_8 = 8 * 3600_000;
export const WEEK_MS = 7 * 86400_000;

export type MuteFields = { muted: boolean; mutedUntil?: number | null };

export function expireMute(thread: MuteFields, now = Date.now()): void {
  if (thread.muted && thread.mutedUntil && thread.mutedUntil > 0 && thread.mutedUntil <= now) {
    thread.muted = false;
    thread.mutedUntil = undefined;
  }
}

/** Off, then 8 hours, then 1 week, then until turned off. */
export function cycleMute(thread: MuteFields, now = Date.now()): void {
  expireMute(thread, now);
  if (!thread.muted) {
    thread.muted = true;
    thread.mutedUntil = now + HOUR_8;
    return;
  }
  if (thread.mutedUntil && thread.mutedUntil - now <= HOUR_8 + 120_000) {
    thread.mutedUntil = now + WEEK_MS;
    return;
  }
  if (thread.mutedUntil) {
    thread.mutedUntil = undefined;
    return;
  }
  thread.muted = false;
}

export function muteAction(thread: MuteFields, now = Date.now()): string {
  expireMute(thread, now);
  if (!thread.muted) return "Mute 8 hours";
  if (!thread.mutedUntil) return "Unmute";
  if (thread.mutedUntil - now <= HOUR_8 + 120_000) return "Mute 1 week";
  return "Mute until I turn it off";
}

export function nextDisappear(sec: number | null): number | null {
  if (sec == null) return 86400;
  if (sec === 86400) return 604800;
  if (sec === 604800) return 7776000;
  return null;
}

export function disappearAction(sec: number | null): string {
  if (sec == null) return "Disappear 24h";
  if (sec === 86400) return "Disappear 7 days";
  if (sec === 604800) return "Disappear 90 days";
  return "Keep history";
}

export function disappearPhrase(sec: number | null): string {
  if (sec === 86400) return "disappear 24h";
  if (sec === 604800) return "disappear 7 days";
  if (sec === 7776000) return "disappear 90 days";
  if (!sec) return "keep on device";
  return `disappear ${Math.round(sec / 3600)}h`;
}

export function replySnippet(text: string, kind?: string): string {
  const t = text.replace(/\s+/g, " ").trim();
  if (t) return t.slice(0, 80);
  if (kind === "photo") return "Photo";
  if (kind === "voice") return "Voice note";
  if (kind === "file") return "File";
  return "Message";
}
