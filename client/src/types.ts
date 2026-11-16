import type { Identity } from "./protocol/keys";
import type { SerializedRatchet } from "./protocol/ratchet";
import type { GuardianMode } from "./guardian/engine";

export type Peer = {
  mailboxId: string;
  edPk: string;
  xPk: string;
  displayName: string;
  handle?: string;
  nickname?: string;
  verified: boolean;
  lastSafety?: string;
  safetyChanged?: boolean;
  blocked: boolean;
  accepted: boolean;
  region: string;
  avatarId?: string;
};

export type ThreadKind = "dm" | "group" | "chamber" | "status";

export type Thread = {
  id: string;
  kind: ThreadKind;
  peerMailboxId?: string;
  title: string;
  lastPreview: string;
  lastAt: number;
  unread: number;
  pinned: boolean;
  muted: boolean;
  mutedUntil?: number | null;
  replyTo?: string;
  replyText?: string;
  archived: boolean;
  disappearingSec: number | null;
  receipts: boolean;
  typing: boolean;
  draft: string;
  peerTypingUntil?: number;
  chamberId?: string;
  groupId?: string;
  lockedStub?: boolean;
};

export type MsgKind = "text" | "photo" | "voice" | "file" | "system" | "call";

export type Message = {
  id: string;
  threadId: string;
  fromMe: boolean;
  fromMailbox?: string;
  kind: MsgKind;
  text: string;
  at: number;
  expiresAt?: number;
  attachmentId?: string;
  mime?: string;
  name?: string;
  reacted?: string;
  edited?: boolean;
  deleted?: boolean;
  forwarded?: boolean;
  status?: "queued" | "sent" | "delivered" | "failed" | "read";
  viewOnce?: boolean;
  viewed?: boolean;
  replyTo?: string;
  replyText?: string;
  chamberInvite?: boolean;
  burnAt?: number;
  groupId?: string;
  groupName?: string;
  needsDownload?: boolean;
};

export type SessionRec = {
  peerMailboxId: string;
  ratchet: SerializedRatchet;
  established: boolean;
  pendingEphSk?: string;
  pendingSpkId?: number;
  pendingOpkId?: number;
  theirSpk?: string;
};

export type Settings = {
  pinHash?: never;
  autoLockSec: number;
  guardianMode: GuardianMode;
  lowData: boolean;
  lockScreenPreview: boolean;
  diagnostics: boolean;
  region: string;
  handle?: string;
  shortId?: string;
  recoverySeed?: string;
  deviceId?: string;
  deviceCredential?: string;
  accountEmail?: string;
  historyGeneration?: number;
  emailConfirmed?: boolean;
  confirmNote?: string;
  avatarId?: string;
};

export type ChamberMeta = {
  id: string;
  title: string;
  mailboxId: string;
  peerMailboxId?: string;
  burnAt: number;
  createdAt: number;
  unlocked: boolean;
  unread: number;
  relayToken?: string;
};

export type PrekeyHold = { id: number; sk: string; pk: string };

export type VaultSnapshot = {
  identity: Identity;
  peers: Record<string, Peer>;
  threads: Record<string, Thread>;
  messages: Record<string, Message[]>;
  sessions: Record<string, SessionRec>;
  settings: Settings;
  requests: Message[];
  blocked: string[];
  chambers: Record<string, ChamberMeta>;
  statuses: StatusItem[];
  groups: Record<string, GroupState>;
  prekeys: { spk: PrekeyHold; opks: PrekeyHold[] };
  androidPocket?: unknown;
  androidPocketKnown?: boolean;
};

export type StatusItem = {
  id: string;
  fromMailbox: string;
  fromName: string;
  text?: string;
  attachmentId?: string;
  mime?: string;
  name?: string;
  at: number;
  until: number;
};

export type GroupState = {
  id: string;
  name: string;
  members: { mailboxId: string; edPk: string; role: "admin" | "member" }[];
  version: number;
};

export type OutboxItem = {
  id: string;
  destMailboxId: string;
  blobB64: string;
  ttl: number;
  createdAt: number;
};
