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
  fetch: (token: string) =>
    req("/v1/mail", { headers: { ...(headers(token) as Record<string, string>), "X-Bleep-Retain": "1" } }),
  ackMail: (token: string, ids: string[]) =>
    req("/v1/mail/ack", { method: "POST", headers: headers(token), body: JSON.stringify({ ids: ids.slice(0, 50) }) }),
  presence: (token: string, ids: string[]) =>
    req("/v1/presence?" + ids.slice(0, 30).map((id) => "mailbox_id=" + encodeURIComponent(id)).join("&"), { headers: headers(token) }),
  pushKey: (token: string) => req("/v1/push/vapid", { headers: headers(token) }),
  putPush: (token: string, body: unknown) =>
    req("/v1/push", { method: "PUT", headers: headers(token), body: JSON.stringify(body) }),
  channels: (token: string) => req("/v1/channels", { headers: headers(token) }),
  createChannel: (token: string, name: string) =>
    req("/v1/channels", { method: "POST", headers: headers(token), body: JSON.stringify({ name }) }),
  channelPosts: (token: string, id: string) => req(`/v1/channels/${id}/posts`, { headers: headers(token) }),
  postChannel: (token: string, id: string, text: string, name: string) =>
    req(`/v1/channels/${id}/posts`, { method: "POST", headers: headers(token), body: JSON.stringify({ text, name }) }),
  unlink: (mailboxId: string, token: string) =>
    req(`/v1/mailboxes/${mailboxId}/unlink`, { method: "POST", headers: headers(token) }),
  publishHandle: (token: string, handle: string) =>
    req("/v1/handles", { method: "PUT", headers: headers(token), body: JSON.stringify({ handle }) }),
  resolveHandle: (token: string, handle: string) =>
    req(`/v1/handles/${encodeURIComponent(handle)}`, { headers: headers(token) }),
  resolveShort: (token: string, shortId: string) =>
    req(`/v1/ids/${encodeURIComponent(shortId)}`, { headers: headers(token) }),
  createLogin: (email: string, password: string) =>
    req("/v1/accounts", { method: "POST", headers: headers(), body: JSON.stringify({ email, password }) }),
  loginAccount: (email: string, password: string) =>
    req("/v1/accounts/login", { method: "POST", headers: headers(), body: JSON.stringify({ email, password }) }),
  changePassword: (accountToken: string, current_password: string, new_password: string) =>
    req("/v1/accounts/password", {
      method: "POST",
      headers: headers(accountToken),
      body: JSON.stringify({ current_password, new_password }),
    }),
  addDevice: (accountToken: string, body: unknown) =>
    req("/v1/accounts/devices", { method: "POST", headers: headers(accountToken), body: JSON.stringify(body) }),
  devices: (token: string) => req("/v1/accounts/devices", { headers: headers(token) }),
  revokeDevice: (token: string, deviceId: string) =>
    req(`/v1/accounts/devices/${deviceId}/revoke`, { method: "POST", headers: headers(token), body: "{}" }),
  eraseDeviceRemote: (token: string, deviceId: string) =>
    req(`/v1/accounts/devices/${deviceId}/erase`, { method: "POST", headers: headers(token), body: "{}" }),
  recover: (email: string) =>
    req("/v1/accounts/recover", { method: "POST", headers: headers(), body: JSON.stringify({ email }) }),
  recoverComplete: (token: string, password: string) =>
    req("/v1/accounts/recover/complete", { method: "POST", headers: headers(), body: JSON.stringify({ token, password }) }),
  history: (token: string) => req("/v1/history", { headers: headers(token) }),
  putHistory: (token: string, body: unknown) =>
    req("/v1/history", { method: "PUT", headers: headers(token), body: JSON.stringify(body) }),
  directory: (token: string, q: string) =>
    req(`/v1/directory?q=${encodeURIComponent(q)}`, { headers: headers(token) }),
  dropHandle: (token: string) => req("/v1/handles", { method: "DELETE", headers: headers(token) }),
  closeAccount: (accountToken: string, password: string) =>
    req("/v1/accounts/close", { method: "POST", headers: headers(accountToken), body: JSON.stringify({ password }) }),
  confirmAccount: (token: string) =>
    req("/v1/accounts/confirm", { method: "POST", headers: headers(), body: JSON.stringify({ token }) }),
  putHistoryFile: (token: string, fileId: string, body: unknown) =>
    req(`/v1/history/files/${encodeURIComponent(fileId)}`, { method: "PUT", headers: headers(token), body: JSON.stringify(body) }),
  getHistoryFile: (token: string, fileId: string) =>
    req(`/v1/history/files/${encodeURIComponent(fileId)}`, { headers: headers(token) }),
  listHistoryExtras: (token: string) => req("/v1/history/extras", { headers: headers(token) }),
  getHistoryExtra: (token: string, extraId: string) =>
    req(`/v1/history/extras/${encodeURIComponent(extraId)}`, { headers: headers(token) }),
  putHistoryExtra: (token: string, extraId: string, body: unknown) =>
    req(`/v1/history/extras/${encodeURIComponent(extraId)}`, {
      method: "PUT",
      headers: headers(token),
      body: JSON.stringify(body),
    }),
};

export function wakeUrl(token: string): string {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${location.host}/v1/wake?token=${encodeURIComponent(token)}`;
}
