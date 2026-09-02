import { REGION } from "../protocol/keys";

function base(): string {
  return (import.meta.env.VITE_RELAY_BASE as string | undefined) || "";
}

const headers = (token?: string): HeadersInit => {
  const h: Record<string, string> = {
    "content-type": "application/json",
    "X-Bleep-Region": REGION,
  };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
};

export class RegionMismatch extends Error {
  pinned: string;
  constructor(pinned: string, got: string) {
    super(`Pinned to ${pinned}, relay said ${got}. Fail closed.`);
    this.pinned = pinned;
  }
}

async function req(path: string, init: RequestInit = {}) {
  const res = await fetch(base() + path, init);
  if (res.status === 421) {
    const body = await res.json().catch(() => ({}));
    throw new RegionMismatch(body?.detail?.pinned ?? REGION, body?.detail?.got ?? "?");
  }
  if (!res.ok) {
    const t = await res.text();
    throw new Error(`${res.status} ${t}`);
  }
  if (res.status === 204) return null;
  return res.json();
}

export const api = {
  health: () => req("/v1/health", { headers: headers() }),
  challenge: () => req("/v1/auth/challenge", { headers: headers() }),
  token: (body: unknown) =>
    req("/v1/auth/token", { method: "POST", headers: headers(), body: JSON.stringify(body) }),
  register: (body: unknown) =>
    req("/v1/mailboxes", { method: "POST", headers: headers(), body: JSON.stringify(body) }),
  prekey: (mailboxId: string, token: string) =>
    req(`/v1/mailboxes/${mailboxId}/prekey`, { headers: headers(token) }),
  drop: (token: string, body: unknown) =>
    req("/v1/mail", { method: "POST", headers: headers(token), body: JSON.stringify(body) }),
  fetch: (token: string) => req("/v1/mail", { headers: headers(token) }),
  unlink: (mailboxId: string, token: string) =>
    req(`/v1/mailboxes/${mailboxId}/unlink`, { method: "POST", headers: headers(token) }),
  publishHandle: (token: string, handle: string) =>
    req("/v1/handles", { method: "PUT", headers: headers(token), body: JSON.stringify({ handle }) }),
  resolveHandle: (token: string, handle: string) =>
    req(`/v1/handles/${encodeURIComponent(handle)}`, { headers: headers(token) }),
};

export function wakeUrl(token: string): string {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${location.host}/v1/wake?token=${encodeURIComponent(token)}`;
}
