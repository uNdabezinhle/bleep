import { b64d } from "../protocol/bytes";
import { dmThreadId } from "../session/session";
import { mergePoll, mergeStar } from "../special/features";
import type { GroupState, Message, Peer, Thread, VaultSnapshot } from "../types";

export type LooseMsg = Message & { peerId?: string; bytesB64?: string; attachmentId?: string };

type RemoteThread = Partial<Thread> | LooseMsg[] | undefined;

const emptyThread = (id: string, title: string, peerMailboxId?: string, groupId?: string): Thread => ({
  id,
  kind: groupId ? "group" : "dm",
  peerMailboxId,
  groupId,
  title,
  lastPreview: "",
  lastAt: 0,
  unread: 0,
  pinned: false,
  muted: false,
  archived: false,
  disappearingSec: null,
  receipts: true,
  typing: !groupId,
  draft: "",
});

export function dmPeer(threadId: string, selfId: string): string | null {
  if (!threadId.startsWith("dm:")) return null;
  const parts = threadId.slice(3).split(":");
  if (parts.length !== 2 || !parts[0] || !parts[1]) return null;
  if (parts[0] === selfId) return parts[1];
  if (parts[1] === selfId) return parts[0];
  return null;
}

function usableKey(raw: string | undefined): boolean {
  if (!raw) return false;
  try {
    const bytes = b64d(raw);
    return bytes.length === 32 && bytes.some((b) => b !== 0);
  } catch {
    return false;
  }
}

export function keptKey(remote: string | undefined, local: string | undefined): string {
  if (usableKey(remote)) return remote || "";
  return local || "";
}

export function peerKeyReady(peer: Peer): boolean {
  return usableKey(peer.edPk) && usableKey(peer.xPk);
}

export function androidMessage(raw: LooseMsg, threadId: string): Message {
  const fromMailbox = raw.fromMailbox || (!raw.fromMe ? raw.peerId : undefined);
  const msg: Message = {
    ...raw,
    threadId: raw.threadId || threadId,
    fromMailbox,
    kind: raw.kind || "text",
    text: raw.text || "",
    at: raw.at || 0,
    fromMe: Boolean(raw.fromMe),
    id: raw.id,
  };
  if (raw.bytesB64 && !msg.attachmentId && /^[A-Za-z0-9_-]{8,80}$/.test(msg.id)) msg.attachmentId = msg.id;
  delete (msg as { bytesB64?: string }).bytesB64;
  delete (msg as { peerId?: string }).peerId;
  return msg;
}

function bucketKey(selfId: string, rawId: string, meta: Partial<Thread> | undefined): string | null {
  if (rawId.startsWith("ch:") || meta?.kind === "chamber") return null;
  if (rawId.startsWith("g:") || meta?.kind === "group") {
    const gid = (meta?.groupId || rawId.replace(/^g:/, "")).trim();
    return gid ? `g:${gid}` : null;
  }
  if (rawId.startsWith("dm:")) {
    const peer = meta?.peerMailboxId || dmPeer(rawId, selfId);
    return peer ? dmThreadId(selfId, peer) : rawId;
  }
  const peer = meta?.peerMailboxId || rawId;
  if (!peer || peer === selfId) return null;
  return dmThreadId(selfId, peer);
}

function defined(meta: Partial<Thread> | undefined): Partial<Thread> {
  const out: Partial<Thread> = {};
  if (!meta) return out;
  for (const [k, v] of Object.entries(meta)) {
    if (v !== undefined && v !== null) (out as Record<string, unknown>)[k] = v;
  }
  return out;
}

function previewOf(msg: Message): string {
  if (msg.deleted) return "Deleted";
  if (msg.kind === "voice") return "Voice note";
  if (msg.kind === "photo") return "Photo";
  if (msg.kind === "file") return msg.name || "File";
  return (msg.text || "").slice(0, 80);
}

function mergeLists(local: Message[] | undefined, incoming: LooseMsg[], threadId: string): Message[] {
  const byId = new Map((local ?? []).map((m) => [m.id, m]));
  for (const raw of incoming) {
    if (!raw?.id) continue;
    const msg = androidMessage(raw, threadId);
    if (msg.deleted) msg.starred = false;
    const prev = byId.get(msg.id);
    byId.set(
      msg.id,
      prev
        ? {
            ...prev,
            ...msg,
            attachmentId: msg.attachmentId || prev.attachmentId,
            fromMailbox: msg.fromMailbox || prev.fromMailbox,
            needsDownload: prev.needsDownload,
            text: msg.deleted ? "" : msg.text || prev.text,
            deleted: Boolean(msg.deleted || prev.deleted),
            starred: mergeStar(prev.starred, msg.starred, Boolean(msg.deleted || prev.deleted)),
            poll: mergePoll(prev.poll, msg.poll),
          }
        : msg,
    );
  }
  return [...byId.values()].sort((a, b) => a.at - b.at || a.id.localeCompare(b.id));
}

/**
 * Put phone and browser history on the same DM and group ids.
 * Chamber threads stay where they are. A direct thread with messages marks that person accepted.
 */
export function foldSharedHistory(
  snap: VaultSnapshot,
  remoteThreads?: Record<string, RemoteThread>,
  remoteMessages?: Record<string, LooseMsg[] | undefined>,
): void {
  const self = snap.identity.mailboxId;
  type Bucket = { meta?: Partial<Thread>; msgs: LooseMsg[]; sources: string[] };
  const buckets = new Map<string, Bucket>();
  const add = (rawId: string, meta: Partial<Thread> | undefined, msgs: LooseMsg[] | undefined) => {
    const key = bucketKey(self, rawId, meta);
    if (!key) return;
    const row = buckets.get(key) ?? { msgs: [], sources: [] };
    if (meta) row.meta = { ...row.meta, ...defined(meta) };
    if (msgs?.length) row.msgs.push(...msgs);
    row.sources.push(rawId);
    buckets.set(key, row);
  };

  for (const [id, thread] of Object.entries(snap.threads)) {
    if (id.startsWith("ch:") || thread.kind === "chamber") continue;
    add(id, thread, snap.messages[id]);
  }
  for (const [id, msgs] of Object.entries(snap.messages)) {
    if (id.startsWith("ch:") || snap.threads[id]) continue;
    add(id, undefined, msgs);
  }
  for (const [id, remote] of Object.entries(remoteThreads ?? {})) {
    if (!remote) continue;
    if (Array.isArray(remote)) add(id, undefined, remote);
    else add(id, remote, undefined);
  }
  for (const [id, msgs] of Object.entries(remoteMessages ?? {})) {
    if (Array.isArray(msgs)) add(id, undefined, msgs);
  }

  const consumed = new Set<string>();
  for (const [key, row] of buckets) {
    for (const src of row.sources) if (src !== key) consumed.add(src);
    const known = new Set<string>();
    for (const src of new Set([key, ...row.sources])) {
      for (const m of snap.messages[src] ?? []) known.add(m.id);
    }
    const prev = snap.threads[key] || row.sources.map((src) => snap.threads[src]).find((t) => t && t.kind !== "chamber");
    const merged = mergeLists(snap.messages[key], row.msgs, key);
    const added = merged.filter((m) => !known.has(m.id) && !m.fromMe).length;
    const last = [...merged].reverse().find((m) => !m.deleted) || merged[merged.length - 1];
    const peerId = key.startsWith("dm:") ? dmPeer(key, self) || row.meta?.peerMailboxId : undefined;
    const groupId = key.startsWith("g:") ? key.slice(2) : undefined;
    const title = row.meta?.title || prev?.title || snap.peers[peerId || ""]?.displayName || (groupId ? "Group" : (peerId || "Chat").slice(0, 6));
    const base = prev ? { ...prev, ...defined(row.meta) } : { ...emptyThread(key, title, peerId, groupId), ...defined(row.meta) };
    snap.threads[key] = {
      ...base,
      id: key,
      kind: groupId ? "group" : "dm",
      title,
      peerMailboxId: peerId || base.peerMailboxId,
      groupId: groupId || base.groupId,
      unread: (prev?.unread ?? 0) + added,
      lastAt: last ? Math.max(last.at || 0, 0) : base.lastAt || 0,
      lastPreview: last ? previewOf(last) : base.lastPreview || "",
      pinned: base.pinned ?? false,
      muted: base.muted ?? false,
      archived: base.archived ?? false,
      receipts: base.receipts ?? true,
      typing: base.typing ?? !groupId,
      draft: base.draft ?? "",
      disappearingSec: base.disappearingSec ?? null,
    };
    snap.messages[key] = merged;
    if (peerId) {
      const peer = snap.peers[peerId];
      if (!peer) {
        snap.peers[peerId] = {
          mailboxId: peerId,
          edPk: "",
          xPk: "",
          displayName: title,
          verified: false,
          blocked: false,
          accepted: true,
          region: "ZA-JHB",
        };
      } else if (!peer.blocked) peer.accepted = true;
    }
    if (groupId && !snap.groups[groupId]) {
      const group: GroupState = { id: groupId, name: title || "Group", members: [], version: 1 };
      snap.groups[groupId] = group;
    }
  }
  for (const src of consumed) {
    if (src.startsWith("ch:")) continue;
    delete snap.threads[src];
    delete snap.messages[src];
  }
}
