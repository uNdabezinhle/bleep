import { b64d, b64e, fromUtf8 } from "../protocol/bytes";
import {
  authMessage,
  bleepCode,
  freshX,
  generateIdentity,
  parseBleepCode,
  REGION,
  registrationMessage,
  sign,
  spkMessage,
  type Identity,
} from "../protocol/keys";
import {
  dumpRatchet,
  encodePt,
  initAlice,
  initBob,
  loadRatchet,
  ratchetDecrypt,
  ratchetEncrypt,
  x3dhAlice,
  x3dhBob,
  type RatchetState,
} from "../protocol/ratchet";
import { decodeInner, encodeInner, openOuter, sealOuter, signInner, verifyInner } from "../protocol/seal";
import { safetyNumber } from "../protocol/safety";
import { api } from "../net/api";
import type { Message, Peer, SessionRec, Thread, VaultSnapshot } from "../types";
import { inspectDraft, type Hit } from "../guardian/engine";

export type SpkHold = { id: number; sk: Uint8Array; pk: Uint8Array };
export type OpkHold = { id: number; sk: Uint8Array; pk: Uint8Array };

export async function registerOnRelay(id: Identity, spk: SpkHold, opks: OpkHold[]) {
  const body = {
    mailbox_id: id.mailboxId,
    identity_ed25519_b64: b64e(id.edPk),
    identity_x25519_b64: b64e(id.xPk),
    signed_prekey: {
      key_id: spk.id,
      pub_b64: b64e(spk.pk),
      sig_b64: b64e(sign(id.edSk, spkMessage(spk.id, spk.pk))),
    },
    one_time_prekeys: opks.map((k) => ({ key_id: k.id, pub_b64: b64e(k.pk) })),
    registration_sig_b64: b64e(sign(id.edSk, registrationMessage(id.mailboxId, id.xPk))),
  };
  return api.register(body) as Promise<{ token: string; mailbox_id: string; region: string }>;
}

export async function login(id: Identity): Promise<string> {
  const ch = await api.challenge();
  const sig = b64e(sign(id.edSk, authMessage(id.mailboxId, ch.nonce)));
  const tok = await api.token({
    mailbox_id: id.mailboxId,
    nonce: ch.nonce,
    signature_b64: sig,
  });
  return tok.token as string;
}

export function makePrekeys(n = 20): { spk: SpkHold; opks: OpkHold[] } {
  const s = freshX();
  const spk = { id: 1, sk: s.sk, pk: s.pk };
  const opks = Array.from({ length: n }, (_, i) => {
    const k = freshX();
    return { id: i + 1, sk: k.sk, pk: k.pk };
  });
  return { spk, opks };
}

export function peerFromCode(code: string): Peer {
  const p = parseBleepCode(code);
  return {
    mailboxId: p.mailboxId,
    edPk: b64e(p.edPk),
    xPk: b64e(p.xPk),
    displayName: p.mailboxId.slice(0, 6),
    verified: false,
    blocked: false,
    accepted: false,
    region: p.region,
  };
}

function getRatchet(snap: VaultSnapshot, peerId: string): RatchetState | null {
  const rec = snap.sessions[peerId];
  if (!rec) return null;
  return loadRatchet(rec.ratchet);
}

function putRatchet(snap: VaultSnapshot, peerId: string, st: RatchetState, extra: Partial<SessionRec> = {}) {
  const prev = snap.sessions[peerId];
  snap.sessions[peerId] = {
    ...prev,
    ...extra,
    peerMailboxId: peerId,
    ratchet: dumpRatchet(st),
    established: extra.established ?? prev?.established ?? true,
  };
}

export async function ensureAliceSession(
  snap: VaultSnapshot,
  token: string,
  peer: Peer,
  spkHold: SpkHold,
): Promise<RatchetState> {
  const existing = getRatchet(snap, peer.mailboxId);
  if (existing && snap.sessions[peer.mailboxId]?.established) return existing;
  const bundle = await api.prekey(peer.mailboxId, token);
  const eph = freshX();
  const bobSpk = b64d(bundle.signed_prekey.pub_b64);
  const bobOpk = bundle.one_time_prekey ? b64d(bundle.one_time_prekey.pub_b64) : null;
  const sk = x3dhAlice(snap.identity.xSk, eph.sk, b64d(peer.xPk), bobSpk, bobOpk);
  const st = initAlice(sk, bobSpk);
  putRatchet(snap, peer.mailboxId, st, {
    established: true,
    pendingEphSk: b64e(eph.sk),
    pendingSpkId: bundle.signed_prekey.key_id,
    pendingOpkId: bundle.one_time_prekey?.key_id,
    theirSpk: bundle.signed_prekey.pub_b64,
  });
  // stash eph pub on the state via a side field on session
  (snap.sessions[peer.mailboxId] as SessionRec & { ephPk?: string }).ephPk = b64e(eph.pk);
  void spkHold;
  return st;
}

export type Payload =
  | { kind: "text"; text: string; id: string; expireSec?: number | null; forwarded?: boolean; viewOnce?: boolean }
  | { kind: "photo" | "voice" | "file"; text: string; id: string; mime: string; name: string; bytesB64: string; expireSec?: number | null; viewOnce?: boolean }
  | {
      kind: "intro";
      text: string;
      displayName: string;
      id: string;
      chamber?: boolean;
      burnAt?: number;
      ephCode?: string;
      groupId?: string;
      groupName?: string;
    }
  | { kind: "react"; id: string; target: string; emoji: string }
  | { kind: "receipt"; id: string; upTo: string }
  | { kind: "typing"; id: string }
  | { kind: "ctrl"; id: string; op: string; data?: unknown }
  | { kind: "call"; id: string; phase: "offer" | "answer" | "ice" | "hangup" | "missed"; sdp?: string; candidate?: string; media?: "audio" | "video" };

export async function sendSealed(
  snap: VaultSnapshot,
  token: string,
  peer: Peer,
  payload: Payload,
  opts: { ttl?: number; spk?: SpkHold; opkMap?: Map<number, OpkHold> } = {},
): Promise<void> {
  const st = await ensureAliceSession(snap, token, peer, opts.spk ?? { id: 1, sk: snap.identity.xSk, pk: snap.identity.xPk });
  const rec = snap.sessions[peer.mailboxId] as SessionRec & { ephPk?: string };
  const { header, ciphertext } = ratchetEncrypt(st, encodePt(payload));
  putRatchet(snap, peer.mailboxId, st, rec);
  const isPk = Boolean(rec.pendingEphSk);
  const inner = signInner(snap.identity, {
    v: 1,
    t: payload.kind === "intro" ? "intro" : isPk ? "pkmsg" : "msg",
    sid: b64e(snap.identity.edPk),
    spk: b64e(snap.identity.xPk),
    mid: snap.identity.mailboxId,
    ek: rec.ephPk,
    used_opk: rec.pendingOpkId,
    used_spk: rec.pendingSpkId,
    h: header,
    ct: b64e(ciphertext),
    ts: Date.now(),
  });
  if (isPk) {
    rec.pendingEphSk = undefined;
    rec.ephPk = undefined;
  }
  const blob = sealOuter(b64d(peer.xPk), encodeInner(inner));
  await api.drop(token, {
    dest_mailbox_id: peer.mailboxId,
    blob_b64: b64e(blob),
    ttl_seconds: opts.ttl ?? 36 * 3600,
  });
}

export type Incoming = {
  payload: Payload;
  fromEd: Uint8Array;
  fromX: Uint8Array;
  innerType: string;
  mailboxId: string;
};

export function openIncoming(
  snap: VaultSnapshot,
  blobB64: string,
  holds: { spk: SpkHold; opks: OpkHold[] },
): Incoming | null {
  let innerBytes: Uint8Array;
  try {
    innerBytes = openOuter(snap.identity.xSk, b64d(blobB64));
  } catch {
    return null;
  }
  const inner = decodeInner(innerBytes);
  if (!verifyInner(inner)) return null;
  const fromEd = b64d(inner.sid);
  const fromX = b64d(inner.spk);
  const peerId = inner.mid || findPeerId(snap, fromEd) || "pending:" + b64e(fromEd).slice(0, 12);

  let st = getRatchet(snap, peerId);
  if (!st) {
    if (inner.t !== "pkmsg" && inner.t !== "intro") return null;
    if (!inner.ek) return null;
    const aliceEph = b64d(inner.ek);
    const opk = inner.used_opk != null ? holds.opks.find((k) => k.id === inner.used_opk) : undefined;
    const sk = x3dhBob(snap.identity.xSk, holds.spk.sk, fromX, aliceEph, opk?.sk ?? null);
    st = initBob(sk, holds.spk.sk, holds.spk.pk);
    snap.sessions[peerId] = {
      peerMailboxId: peerId,
      ratchet: dumpRatchet(st),
      established: true,
    };
  }
  const pt = ratchetDecrypt(st, inner.h, b64d(inner.ct));
  putRatchet(snap, peerId, st);
  const payload = JSON.parse(fromUtf8(pt)) as Payload;
  return { payload, fromEd, fromX, innerType: inner.t, mailboxId: peerId };
}

function findPeerId(snap: VaultSnapshot, edPk: Uint8Array): string | null {
  const want = b64e(edPk);
  for (const p of Object.values(snap.peers)) {
    if (p.edPk === want) return p.mailboxId;
  }
  return null;
}

export function dmThreadId(a: string, b: string): string {
  return ["dm", ...[a, b].sort()].join(":");
}

export function upsertPeer(snap: VaultSnapshot, peer: Peer): void {
  const prev = snap.peers[peer.mailboxId];
  snap.peers[peer.mailboxId] = { ...peer, ...prev, ...peer };
}

export function appendMsg(snap: VaultSnapshot, msg: Message): void {
  const list = snap.messages[msg.threadId] ?? [];
  if (list.some((m) => m.id === msg.id)) return;
  list.push(msg);
  snap.messages[msg.threadId] = list;
  const th = snap.threads[msg.threadId];
  if (th) {
    th.lastAt = msg.at;
    th.lastPreview = msg.deleted ? "Deleted" : msg.kind === "voice" ? "Voice note" : msg.kind === "photo" ? "Photo" : msg.text.slice(0, 80);
    if (!msg.fromMe && !th.muted) th.unread += 1;
  }
}

export function ensureDmThread(snap: VaultSnapshot, peer: Peer): Thread {
  const id = dmThreadId(snap.identity.mailboxId, peer.mailboxId);
  if (!snap.threads[id]) {
    snap.threads[id] = {
      id,
      kind: "dm",
      peerMailboxId: peer.mailboxId,
      title: peer.displayName,
      lastPreview: "",
      lastAt: Date.now(),
      unread: 0,
      pinned: false,
      muted: false,
      archived: false,
      disappearingSec: null,
      receipts: true,
      typing: true,
      draft: "",
    };
  }
  return snap.threads[id];
}

export function guardianForSend(
  snap: VaultSnapshot,
  text: string,
  files: { name: string; mime: string; bytes: Uint8Array }[],
  peer?: Peer,
): Hit[] {
  if (snap.settings.guardianMode === "off") return [];
  return inspectDraft({
    text,
    files,
    peerVerified: peer?.verified ?? false,
    safetyJustChanged: Boolean(peer?.lastSafety && !peer.verified),
  });
}

export function myCode(id: Identity): string {
  return bleepCode(id);
}

export function pairSafety(me: Identity, peer: Peer): string {
  return safetyNumber(me.edPk, b64d(peer.edPk));
}

export { generateIdentity, REGION };
