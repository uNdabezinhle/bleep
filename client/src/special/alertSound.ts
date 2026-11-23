/** A short tone generated in the page. No audio file and no network fetch. */

let audio: AudioContext | null = null;

function context(): AudioContext | null {
  const Ctx = window.AudioContext || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctx) return null;
  if (!audio) audio = new Ctx();
  return audio;
}

/** Call from a tap so the browser will allow a later tone. */
export function unlockAlert(): void {
  const ctx = context();
  if (!ctx || ctx.state === "running") return;
  void ctx.resume().catch(() => undefined);
}

/** Returns false when the browser still blocks audio. */
export async function playMailTone(): Promise<boolean> {
  const ctx = context();
  if (!ctx) return false;
  if (ctx.state !== "running") {
    try {
      await ctx.resume();
    } catch {
      return false;
    }
  }
  if (ctx.state !== "running") return false;
  const now = ctx.currentTime;
  const gain = ctx.createGain();
  gain.gain.setValueAtTime(0.0001, now);
  gain.gain.exponentialRampToValueAtTime(0.05, now + 0.02);
  gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.22);
  gain.connect(ctx.destination);
  const first = ctx.createOscillator();
  first.type = "sine";
  first.frequency.value = 880;
  first.connect(gain);
  first.start(now);
  first.stop(now + 0.09);
  const second = ctx.createOscillator();
  second.type = "sine";
  second.frequency.value = 660;
  second.connect(gain);
  second.start(now + 0.1);
  second.stop(now + 0.2);
  return true;
}
