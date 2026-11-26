import { asAB, b64d, b64e, fromUtf8, utf8 } from "./protocol/bytes";
import {
  aeadDecrypt,
  aeadEncrypt,
  authMessage,
  freshNonce,
  generateIdentity,
  chamberKey,
  historyKey,
  identityFromSeed,
  parseRecovery,
  sign,
  type Identity,
} from "./protocol/keys";
import { api, wakeUrl, RegionMismatch } from "./net/api";
import { inspectDraft, type Hit } from "./guardian/engine";
import {
  appendMsg,
  dmThreadId,
  ensureDmThread,
  login,
  makePrekeys,
  openForAck,
  pairSafety,
  peerFromCode,
  registerOnRelay,
  sendSealed,
  upsertPeer,
  type OpkHold,
  type Payload,
  type SpkHold,
} from "./session/session";
import { emptySnapshot, Vault, profileName } from "./store/vault";
import type { ChamberMeta, Message, Peer, StatusItem, Thread, VaultSnapshot } from "./types";
import { cycleMute, nextDisappear } from "./chat/prefs";
import { handleFromInput, peerLabel, publishedHandle, shortIdFromInput } from "./protocol/keys";
import { iceServers } from "./calls/ice";
import { applyLeave } from "./history/groups";
import { foldSharedHistory, keptKey, peerKeyReady, type LooseMsg } from "./history/share";
import { freshRecoverySeed } from "./protocol/keys";
import { OTHER_DEVICE, conflictStep, isRatchetDump, mergeSharedDumps, x3dhChoice } from "./history/sessionLock";
import type { SerializedRatchet } from "./protocol/ratchet";
import { applyVote, closePoll, enqueueLater, inQuietHours, shouldAlert, takeDue, type PollState } from "./special/features";
import { playMailTone } from "./special/alertSound";


export type UiNotice = { kind: "error" | "info" | "region"; text: string };

function holdsFromSnap(snap: VaultSnapshot): { spk: SpkHold; opks: OpkHold[] } {
  return {
    spk: { id: snap.prekeys.spk.id, sk: b64d(snap.prekeys.spk.sk), pk: b64d(snap.prekeys.spk.pk) },
    opks: snap.prekeys.opks.map((k) => ({ id: k.id, sk: b64d(k.sk), pk: b64d(k.pk) })),
  };
}

type SharedHist = {
  kind?: string;
  displayName?: string;
  peers?: Record<string, Partial<Peer> & { mailboxId?: string; edPk?: string; xPk?: string }>;
  threads?: Record<string, Thread | LooseMsg[]>;
  messages?: Record<string, LooseMsg[]>;
  requests?: LooseMsg[];
  blocked?: string[];
  sessions?: VaultSnapshot["sessions"];
  groups?: VaultSnapshot["groups"];
  statuses?: VaultSnapshot["statuses"];
  prekeys?: VaultSnapshot["prekeys"];
  settings?: Partial<VaultSnapshot["settings"]>;
  web?: {
    sessions?: VaultSnapshot["sessions"];
    prekeys?: VaultSnapshot["prekeys"];
    settings?: Partial<VaultSnapshot["settings"]>;
    groups?: VaultSnapshot["groups"];
    statuses?: VaultSnapshot["statuses"];
  };
  android?: unknown;
};

function storeHolds(spk: SpkHold, opks: OpkHold[]) {
  return {
    spk: { id: spk.id, sk: b64e(spk.sk), pk: b64e(spk.pk) },
    opks: opks.map((k) => ({ id: k.id, sk: b64e(k.sk), pk: b64e(k.pk) })),
  };
}

function b64urlToBytes(s: string): BufferSource {
  const pad = "=".repeat((4 - (s.length % 4)) % 4);
  const raw = atob((s + pad).replace(/-/g, "+").replace(/_/g, "/"));
  const buf = new ArrayBuffer(raw.length);
  const out = new Uint8Array(buf);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return buf;
}

export class Runtime {
  vault = new Vault(profileName());
  snap: VaultSnapshot | null = null;
  token: string | null = null;
  notice: UiNotice | null = null;
  regionOk = false;
  private ws: WebSocket | null = null;
  private idleTimer: number | null = null;
  onChange: () => void = () => {};
  locked = true;
  lastActivity = Date.now();
  call: {
    phase: "idle" | "ringing-out" | "ringing-in" | "active";
    peer: Peer | null;
    media: "audio" | "video";
    remoteStream: MediaStream | null;
    muted: boolean;
  } = { phase: "idle", peer: null, media: "audio", remoteStream: null, muted: false };
  private pc: RTCPeerConnection | null = null;
  private localStream: MediaStream | null = null;
  private pendingOffer: RTCSessionDescriptionInit | null = null;
  presence: Record<string, { online: boolean; lastSeen: string | null }> = {};
  private callTimer: number | null = null;
  private labelsReady = false;
  private alertPending = false;
  /** True when the browser blocked the mail tone. */
  alertBlocked = false;

  async boot(): Promise<"setup" | "lock" | "app"> {
    await this.vault.open();
    if (!(await this.vault.hasVault())) return "setup";
    return "lock";
  }

  async createAccount(displayName: string, pin: string): Promise<void> {
    const id = generateIdentity(displayName.trim() || "me");
    const { spk, opks } = makePrekeys(20);
    const snap = emptySnapshot(id);
    snap.prekeys = storeHolds(spk, opks);
    const reg = await registerOnRelay(id, spk, opks);
    if (reg.short_id) snap.settings.shortId = reg.short_id;
    this.token = reg.token;
    this.snap = snap;
    await this.vault.create(pin, snap);
    this.locked = false;
    this.regionOk = true;
    this.connectWake();
    this.armIdle();
    this.bump();
  }

  async unlock(pin: string): Promise<void> {
    this.snap = await this.vault.unlock(pin);
    this.locked = false;
    try {
      const session = await login(this.snap.identity, this.device());
      this.token = session.token;
      if (session.shortId) this.snap.settings.shortId = session.shortId;
      this.snap.settings.handle = session.handle || undefined;
    } catch (e) {
      const msg = (e as Error).message || "";
      this.token = null;
      this.vault.lock();
      if (msg.includes("erase") || msg.includes("403")) {
        await this.vault.erase();
        this.snap = null;
        this.locked = true;
        throw new Error("This device was erased.");
      }
      this.snap = null;
      throw e;
    }
    this.regionOk = true;
    await this.pullHistory();
    await this.poll();
    await this.flushOutbox();
    this.connectWake();
    this.armIdle();
    this.sweepDisappearing();
    this.bump();
  }

  async signUp(email: string, password: string, displayName: string, pin: string, seed: Uint8Array): Promise<string> {
    const id = identityFromSeed(displayName.trim() || "me", seed);
    const account = (await api.createLogin(email, password)) as {
      account_token: string;
      email_confirmed: boolean;
      detail: string;
    };
    if (!account.email_confirmed) return account.detail;
    await this.enroll(id, account.account_token, email, seed, pin, true);
    if (this.snap) {
      this.snap.settings.emailConfirmed = true;
      if (account.detail) this.snap.settings.confirmNote = account.detail;
    }
    return account.detail;
  }

  async signIn(email: string, password: string, recovery: string, pin: string): Promise<void> {
    const account = (await api.loginAccount(email, password)) as { account_token: string; mailbox_id: string | null; email_confirmed: boolean; setup_pending?: boolean };
    if (!account.email_confirmed) throw new Error("Confirm your email before opening the vault.");
    if (!account.mailbox_id) {
      if (!account.setup_pending) throw new Error("This account has no active mailbox.");
      const seed = recovery.trim() ? parseRecovery(recovery) : freshRecoverySeed();
      await this.enroll(identityFromSeed("me", seed), account.account_token, email, seed, pin, true);
      return;
    }
    if (!recovery.trim()) throw new Error("Enter your recovery code to open this account on a new device.");
    const seed = parseRecovery(recovery);
    const id = identityFromSeed("me", seed);
    if (id.mailboxId !== account.mailbox_id) throw new Error("Recovery code does not match this account.");
    await this.enroll(id, account.account_token, email, seed, pin, false);
  }

  private async enroll(
    id: Identity,
    accountToken: string,
    email: string,
    seed: Uint8Array,
    pin: string,
    fresh: boolean,
  ): Promise<void> {
    const { spk, opks } = makePrekeys(20);
    const snap = emptySnapshot(id);
    if (fresh) snap.prekeys = storeHolds(spk, opks);
    if (fresh) {
      const reg = await registerOnRelay(id, spk, opks);
      if (reg.short_id) snap.settings.shortId = reg.short_id;
    }
    const ch = (await api.challenge()) as { nonce: string };
    const sig = b64e(sign(id.edSk, authMessage(id.mailboxId, ch.nonce)));
    const dev = (await api.addDevice(accountToken, {
      label: "browser",
      mailbox_id: id.mailboxId,
      nonce: ch.nonce,
      signature_b64: sig,
    })) as { device_id: string; device_credential: string; token: string; short_id?: string; handle?: string | null };
    if (dev.short_id) snap.settings.shortId = dev.short_id;
    if (dev.handle) snap.settings.handle = dev.handle;
    snap.settings.recoverySeed = b64e(seed);
    snap.settings.deviceId = dev.device_id;
    snap.settings.deviceCredential = dev.device_credential;
    snap.settings.accountEmail = email.trim().toLowerCase();
    snap.settings.historyGeneration = 0;
    this.token = dev.token;
    this.snap = snap;
    this.locked = false;
    this.regionOk = true;
    if (!fresh) await this.pullHistory();
    await this.vault.create(pin, this.snap);
    this.connectWake();
    this.armIdle();
    this.bump();
  }

  private device(): { id: string; credential: string } | undefined {
    const s = this.snap?.settings;
    if (!s?.deviceId || !s.deviceCredential) return undefined;
    return { id: s.deviceId, credential: s.deviceCredential };
  }

  async listDevices(): Promise<{ device_id: string; label: string; created_band: string; revoked: boolean; erase_requested: boolean; this_device: boolean }[]> {
    if (!this.token) return [];
    return (await api.devices(this.token)) as [];
  }

  async eraseRemote(deviceId: string): Promise<void> {
    if (!this.token) return;
    await api.eraseDeviceRemote(this.token, deviceId);
  }

  async revokeRemote(deviceId: string): Promise<void> {
    if (!this.token) return;
    await api.revokeDevice(this.token, deviceId);
  }

  async changeAccountPassword(current: string, next: string): Promise<void> {
    const email = this.snap?.settings.accountEmail;
    if (!email) throw new Error("This vault has no email login.");
    const account = (await api.loginAccount(email, current)) as { account_token: string };
    await api.changePassword(account.account_token, current, next);
  }

  async searchDirectory(q: string): Promise<{ handle: string; mailbox_id: string }[]> {
    if (!this.token || q.trim().length < 2) return [];
    const rows = (await api.directory(this.token, q.trim().toLowerCase())) as { handle: string; mailbox_id: string; identity_ed25519_b64: string; identity_x25519_b64: string }[];
    const blocked = new Set(this.snap?.blocked ?? []);
    return rows.filter((r) => !blocked.has(r.mailbox_id));
  }

  lockNow(): void {
    this.chamberPins.clear();
    if (this.snap) {
      for (const ch of Object.values(this.snap.chambers)) ch.unlocked = false;
      for (const th of Object.values(this.snap.threads)) {
        if (th.kind === "chamber") {
          th.lockedStub = true;
          th.lastPreview = "Chamber active · locked";
        }
      }
    }
    const snap = this.snap;
    this.teardownCall();
    this.token = null;
    this.locked = true;
    this.ws?.close();
    this.ws = null;
    if (this.wakeTimer) window.clearInterval(this.wakeTimer);
    this.wakeTimer = 0;
    if (this.idleTimer) window.clearTimeout(this.idleTimer);
    void this.pollChain.then(async () => {
      if (snap && this.vault.unlocked) {
        try { await this.vault.save(snap); } catch { /* already locked */ }
      }
      this.vault.lock();
      this.snap = null;
      this.onChange();
    });
  }

  touch(): void {
    this.lastActivity = Date.now();
    this.armIdle();
  }

  private armIdle(): void {
    if (this.idleTimer) window.clearTimeout(this.idleTimer);
    const sec = this.snap?.settings.autoLockSec ?? 300;
    if (!sec) return;
    this.idleTimer = window.setTimeout(() => this.lockNow(), sec * 1000);
  }

  persist = async (): Promise<void> => {
    if (this.snap && this.vault.unlocked) await this.vault.save(this.snap);
  };

  private historyTimer = 0;
  private pollChain: Promise<void> = Promise.resolve();
  private wakeTimer = 0;
  private chamberPins = new Map<string, string>();
  private extraGen = new Map<string, number>();
  private uploadedFiles = new Set<string>();
  private heldLocal = new Set<string>();
  private openedEnvelopes = new Set<string>();
  private historyChain: Promise<void> = Promise.resolve();
  private sessionDirty = false;

  bump(): void {
    this.onChange();
    void this.persist();
    this.scheduleHistory();
  }

  private scheduleHistory(): void {
    if (!this.snap?.settings.recoverySeed || !this.token || this.locked) return;
    if (this.historyTimer) window.clearTimeout(this.historyTimer);
    this.historyTimer = window.setTimeout(() => void this.pushHistory(), 1200);
  }

  private historyView() {
    const s = this.snap!;
    foldSharedHistory(s);
    const {
      recoverySeed: _seed,
      deviceCredential: _cred,
      deviceId: _id,
      accountEmail: _email,
      historyGeneration: _gen,
      emailConfirmed: _confirmed,
      confirmNote: _note,
      ...rest
    } = s.settings;
    const threads: VaultSnapshot["threads"] = {};
    for (const [id, thread] of Object.entries(s.threads)) {
      if (id.startsWith("ch:") || thread.kind === "chamber") continue;
      threads[id] = thread;
    }
    const messages: VaultSnapshot["messages"] = {};
    for (const [id, msgs] of Object.entries(s.messages)) {
      if (id.startsWith("ch:")) continue;
      messages[id] = msgs.map((m) => {
        const copy = { ...m };
        delete (copy as { bytesB64?: string }).bytesB64;
        return copy;
      });
    }
    const peers: VaultSnapshot["peers"] = {};
    for (const [id, peer] of Object.entries(s.peers)) {
      if (peerKeyReady(peer)) peers[id] = peer;
    }
    const body: Record<string, unknown> = {
      kind: "shared-v1",
      displayName: s.identity.displayName,
      peers,
      threads,
      messages,
      requests: s.requests.filter((r) => !r.chamberInvite),
      blocked: s.blocked,
      sessions: s.sharedSessions ?? {},
      web: {
        sessions: s.sessions,
        prekeys: s.prekeys,
        settings: rest,
        groups: s.groups,
        statuses: s.statuses.map((st) => {
          const copy = { ...st } as StatusItem & { bytesB64?: string };
          delete copy.bytesB64;
          return copy;
        }),
      },
    };
    if (s.androidPocketKnown) body.android = s.androidPocket ?? null;
    return body;
  }

  private enqueueHistory(job: () => Promise<void>): Promise<void> {
    const run = this.historyChain.then(job, job);
    this.historyChain = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  private async pushHistory(opts?: { rethrowConflict?: boolean }): Promise<void> {
    try {
      await this.enqueueHistory(async () => {
        if (!this.snap?.settings.recoverySeed || !this.token || this.locked) return;
        if (!this.snap.androidPocketKnown) await this.pullHistory();
        if (!this.snap || this.locked || !this.token) return;
        const generation = (this.snap.settings.historyGeneration ?? 0) + 1;
        const seed = b64d(this.snap.settings.recoverySeed);
        const nonce = freshNonce();
        const ct = aeadEncrypt(historyKey(seed), nonce, utf8(JSON.stringify(this.historyView())));
        await api.putHistory(this.token, { generation, nonce_b64: b64e(nonce), blob_b64: b64e(ct) });
        this.snap.settings.historyGeneration = generation;
        await this.persist();
        await this.syncFiles();
        await this.pushChambers();
      });
    } catch (e) {
      const msg = (e as Error).message || "";
      if (msg.includes("409")) {
        if (opts?.rethrowConflict) throw e;
        await this.pullHistory();
        return;
      }
      if (msg.includes("erase")) {
        await this.vault.erase();
        this.lockNow();
        return;
      }
    }
  }

  private async pullHistory(): Promise<void> {
    if (!this.snap?.settings.recoverySeed || !this.token) return;
    const remote = (await api.history(this.token)) as { generation: number; nonce_b64?: string; blob_b64?: string };
    if (!remote.generation || !remote.blob_b64 || !remote.nonce_b64) {
      this.snap.androidPocketKnown = true;
      return;
    }
    const seed = b64d(this.snap.settings.recoverySeed);
    const pt = aeadDecrypt(historyKey(seed), b64d(remote.nonce_b64), b64d(remote.blob_b64));
    const data = JSON.parse(fromUtf8(pt)) as SharedHist;
    const newer = remote.generation > (this.snap.settings.historyGeneration ?? 0);
    if (data.kind === "shared-v1") {
      this.snap.androidPocketKnown = true;
      this.snap.androidPocket = data.android ?? null;
      if (newer) this.applyShared(data, remote.generation);
    } else if (data.kind === "android") {
      this.snap.androidPocketKnown = true;
      this.snap.androidPocket = data;
      if (newer) this.applyAndroidBlob(data, remote.generation);
    } else if (newer) {
      this.applyWebBlob(data, remote.generation);
    }
    if (this.vault.unlocked) await this.persist();
    if (newer) await this.hydrateFiles();
  }

  private applyShared(data: SharedHist, generation: number): void {
    const s = this.snap!;
    if (data.displayName) s.identity.displayName = data.displayName;
    this.mergePeers(data.peers);
    foldSharedHistory(s, data.threads, data.messages);
    this.mergeRequests(data.requests);
    s.blocked = [...new Set([...(data.blocked ?? []), ...s.blocked])];
    const web = data.web;
    const android = data.android as { ratchets?: Record<string, unknown> } | null | undefined;
    s.sharedSessions = mergeSharedDumps(s.sharedSessions, {
      top: data.sessions as unknown as Record<string, unknown> | undefined,
      webSessions: web?.sessions as unknown as Record<string, { ratchet?: unknown } | undefined> | undefined,
      androidRatchets: android?.ratchets,
    });
    if (web?.prekeys) s.prekeys = web.prekeys;
    if (web?.groups) s.groups = { ...s.groups, ...web.groups };
    if (web?.statuses) s.statuses = web.statuses;
    this.keepSettings(web?.settings, generation);
  }

  private applyWebBlob(data: SharedHist, generation: number): void {
    const s = this.snap!;
    if (data.displayName) s.identity.displayName = data.displayName;
    this.mergePeers(data.peers);
    foldSharedHistory(s, data.threads, data.messages);
    this.mergeRequests(data.requests);
    s.blocked = [...new Set([...(data.blocked ?? []), ...s.blocked])];
    if (data.sessions) s.sessions = { ...data.sessions, ...s.sessions };
    s.sharedSessions = mergeSharedDumps(s.sharedSessions, {
      webSessions: data.sessions as unknown as Record<string, { ratchet?: unknown } | undefined>,
    });
    if (data.groups) s.groups = { ...data.groups, ...s.groups };
    if (data.prekeys) s.prekeys = data.prekeys;
    this.keepSettings(data.settings, generation);
  }

  private applyAndroidBlob(data: SharedHist, generation: number): void {
    const s = this.snap!;
    if (data.displayName) s.identity.displayName = data.displayName;
    this.mergePeers(data.peers);
    foldSharedHistory(s, data.threads, data.messages);
    this.mergeRequests(data.requests);
    s.blocked = [...new Set([...(data.blocked ?? []), ...s.blocked])];
    s.sharedSessions = mergeSharedDumps(s.sharedSessions, {
      androidRatchets: (data as { ratchets?: Record<string, unknown> }).ratchets,
    });
    this.keepSettings(undefined, generation);
  }

  private mergePeers(peers: SharedHist["peers"]): void {
    const s = this.snap!;
    for (const [id, remote] of Object.entries(peers ?? {})) {
      if (!remote?.mailboxId && !remote?.edPk) continue;
      const local = s.peers[id];
      const next: Peer = {
        mailboxId: remote.mailboxId || id,
        edPk: keptKey(remote.edPk, local?.edPk),
        xPk: keptKey(remote.xPk, local?.xPk),
        handle: publishedHandle(remote.handle) || local?.handle,
        nickname: remote.nickname || local?.nickname,
        displayName: peerLabel(
          publishedHandle(remote.handle) || local?.handle,
          remote.displayName || local?.displayName || "",
        ),
        verified: Boolean(local?.verified || remote.verified),
        blocked: Boolean(local?.blocked || remote.blocked),
        accepted: Boolean(local?.accepted || remote.accepted),
        region: remote.region || local?.region || "ZA-JHB",
        safetyChanged: local?.safetyChanged,
        lastSafety: local?.lastSafety,
        avatarId: "avatarId" in remote ? remote.avatarId || undefined : local?.avatarId,
      };
      s.peers[id] = next;
    }
  }

  private mergeRequests(incoming: SharedHist["requests"]): void {
    const s = this.snap!;
    const byId = new Map(s.requests.map((r) => [r.id, r]));
    for (const raw of incoming ?? []) {
      if (!raw || raw.chamberInvite) continue;
      const msg = {
        ...raw,
        threadId: "requests",
        fromMailbox: raw.fromMailbox || raw.peerId,
        kind: raw.kind || "text",
        text: raw.text || "",
        at: raw.at || Date.now(),
        fromMe: Boolean(raw.fromMe),
        id: raw.id,
      };
      if (!byId.has(msg.id)) byId.set(msg.id, msg);
    }
    s.requests = [...byId.values()];
  }

  private keepSettings(remote: Partial<VaultSnapshot["settings"]> | undefined, generation: number): void {
    const s = this.snap!;
    const keepSeed = s.settings.recoverySeed;
    const keepDev = s.settings.deviceId;
    const keepCred = s.settings.deviceCredential;
    const keepEmail = s.settings.accountEmail;
    const keepNote = s.settings.confirmNote;
    const keepConfirmed = s.settings.emailConfirmed;
    s.settings = {
      ...s.settings,
      ...remote,
      recoverySeed: keepSeed,
      deviceId: keepDev,
      deviceCredential: keepCred,
      accountEmail: keepEmail,
      confirmNote: keepNote,
      emailConfirmed: keepConfirmed,
      historyGeneration: generation,
    };
  }

  private deferBytes(): boolean {
    return Boolean(this.snap?.settings.lowData && window.innerWidth < 840);
  }

  private async hydrateFiles(): Promise<void> {
    if (!this.snap || !this.token) return;
    const defer = this.deferBytes();
    for (const msgs of Object.values(this.snap.messages)) {
      for (const m of msgs) {
        if (!m.attachmentId) continue;
        const have = await this.vault.getAttachment(m.attachmentId).catch(() => null);
        if (have) {
          this.uploadedFiles.add(m.attachmentId);
          continue;
        }
        if (defer) {
          m.needsDownload = true;
          continue;
        }
        try {
          await this.fetchSealedFile(m);
        } catch {
          m.needsDownload = true;
        }
      }
    }
    for (const row of this.sealedFileRefs()) {
      const have = await this.vault.getAttachment(row.id).catch(() => null);
      if (have) {
        this.uploadedFiles.add(row.id);
        continue;
      }
      try {
        await this.fetchAttachment(row.id, row.mime);
      } catch {
        /* photo can arrive with the next history pull */
      }
    }
  }

  private async fetchAttachment(id: string, fallbackMime = "application/octet-stream"): Promise<{ mime: string; name: string }> {
    if (!this.snap?.settings.recoverySeed || !this.token) return { mime: fallbackMime, name: "file" };
    const row = (await api.getHistoryFile(this.token, id)) as {
      nonce_b64: string;
      blob_b64: string;
      mime: string;
      name: string;
    };
    const seed = b64d(this.snap.settings.recoverySeed);
    const bytes = aeadDecrypt(historyKey(seed), b64d(row.nonce_b64), b64d(row.blob_b64));
    await this.vault.putAttachment(id, bytes, row.mime || fallbackMime);
    this.uploadedFiles.add(id);
    return { mime: row.mime || fallbackMime, name: row.name || "file" };
  }

  private async fetchSealedFile(msg: Message): Promise<void> {
    if (!msg.attachmentId) return;
    const row = await this.fetchAttachment(msg.attachmentId, msg.mime || "application/octet-stream");
    msg.mime = msg.mime || row.mime;
    msg.name = msg.name || row.name;
    msg.needsDownload = false;
  }

  private sealedFileRefs(): { id: string; mime?: string; name?: string }[] {
    if (!this.snap) return [];
    const rows: { id: string; mime?: string; name?: string }[] = [];
    const push = (id?: string, mime?: string, name?: string) => {
      if (!id || rows.some((r) => r.id === id) || !/^[A-Za-z0-9_-]{8,80}$/.test(id)) return;
      rows.push({ id, mime, name });
    };
    push(this.snap.settings.avatarId, "image/jpeg", "profile.jpg");
    for (const peer of Object.values(this.snap.peers)) push(peer.avatarId, "image/jpeg", "profile.jpg");
    for (const st of this.snap.statuses) push(st.attachmentId, st.mime, st.name || "status");
    return rows;
  }

  private async syncFiles(): Promise<void> {
    if (!this.snap?.settings.recoverySeed || !this.token) return;
    const seed = b64d(this.snap.settings.recoverySeed);
    const key = historyKey(seed);
    let pushed = false;
    for (const [threadId, msgs] of Object.entries(this.snap.messages)) {
      if (threadId.startsWith("ch:")) continue;
      for (const m of msgs) {
        if (!m.attachmentId || this.uploadedFiles.has(m.attachmentId)) continue;
        if (!/^[A-Za-z0-9_-]{8,80}$/.test(m.attachmentId)) continue;
        const att = await this.vault.getAttachment(m.attachmentId).catch(() => null);
        if (!att) continue;
        const nonce = freshNonce();
        const ct = aeadEncrypt(key, nonce, att.bytes);
        await api.putHistoryFile(this.token, m.attachmentId, {
          nonce_b64: b64e(nonce),
          blob_b64: b64e(ct),
          mime: m.mime || att.mime,
          name: m.name || "file",
        });
        this.uploadedFiles.add(m.attachmentId);
        this.heldLocal.delete(m.attachmentId);
        pushed = true;
      }
    }
    for (const row of this.sealedFileRefs()) {
      if (this.uploadedFiles.has(row.id)) continue;
      const att = await this.vault.getAttachment(row.id).catch(() => null);
      if (!att) continue;
      const nonce = freshNonce();
      const ct = aeadEncrypt(key, nonce, att.bytes);
      await api.putHistoryFile(this.token, row.id, {
        nonce_b64: b64e(nonce),
        blob_b64: b64e(ct),
        mime: row.mime || att.mime,
        name: row.name || "file",
      });
      this.uploadedFiles.add(row.id);
      pushed = true;
    }
    if (pushed) this.onChange();
  }

  pinHeld(chamberId: string): boolean {
    return (this.chamberPins.get(chamberId)?.length ?? 0) >= 4;
  }

  filesWaitingLocally(): boolean {
    return this.heldLocal.size > 0;
  }

  private async pushChambers(): Promise<void> {
    if (!this.snap || !this.token) return;
    for (const [id, pin] of this.chamberPins) {
      if (pin.length < 4 || !this.snap.chambers[id]) continue;
      const { relayToken: _token, ...meta } = this.snap.chambers[id];
      void _token;
      const payload = {
        meta,
        thread: this.snap.threads["ch:" + id],
        messages: (this.snap.messages["ch:" + id] ?? []).map((m) => {
          const copy = { ...m };
          delete (copy as { bytesB64?: string }).bytesB64;
          return copy;
        }),
      };
      const nonce = freshNonce();
      const ct = aeadEncrypt(chamberKey(pin, id), nonce, utf8(JSON.stringify(payload)));
      const generation = (this.extraGen.get(id) ?? 0) + 1;
      try {
        const res = (await api.putHistoryExtra(this.token, id, {
          generation,
          nonce_b64: b64e(nonce),
          blob_b64: b64e(ct),
        })) as { generation: number };
        this.extraGen.set(id, res.generation);
      } catch (e) {
        const msg = (e as Error).message || "";
        if (!msg.includes("409")) continue;
        try {
          const cur = (await api.getHistoryExtra(this.token, id)) as { generation: number };
          this.extraGen.set(id, cur.generation);
        } catch {
          /* next unlock retries */
        }
      }
    }
  }

  private connectWake(): void {
    if (!this.token) return;
    try {
      this.ws?.close();
      if (this.wakeTimer) window.clearInterval(this.wakeTimer);
      const ws = new WebSocket(wakeUrl(this.token));
      const poke = () => {
        if (ws.readyState === WebSocket.OPEN) ws.send("1");
      };
      ws.onopen = () => poke();
      ws.onmessage = () => {
        void this.poll();
      };
      ws.onclose = () => {
        window.setTimeout(() => {
          if (!this.locked) this.connectWake();
        }, 4000);
      };
      this.ws = ws;
      this.wakeTimer = window.setInterval(() => {
        if (this.locked || this.ws !== ws) return;
        poke();
      }, 20000);
    } catch {
      /* wake is best-effort */
    }
  }

  async poll(): Promise<void> {
    const run = this.pollChain.then(() => this.pollBody());
    this.pollChain = run.catch(() => undefined);
    return run;
  }

  private async refreshPresence(): Promise<void> {
    if (!this.token || !this.snap) return;
    const ids = Object.values(this.snap.peers).filter((p) => p.accepted).map((p) => p.mailboxId).slice(0, 30);
    if (!ids.length) return;
    const body = (await api.presence(this.token, ids)) as { items: { mailbox_id: string; online: boolean; last_seen: string | null }[] };
    const next: Runtime["presence"] = {};
    for (const row of body.items ?? []) next[row.mailbox_id] = { online: row.online, lastSeen: row.last_seen };
    this.presence = next;
  }

  async enablePush(): Promise<string> {
    if (!this.token) return "Open Bleep first.";
    if (!("serviceWorker" in navigator) || !("PushManager" in window)) {
      return "This browser cannot take a background notice.";
    }
    const key = (await api.pushKey(this.token)) as { public_key: string | null };
    if (!key.public_key) return "This box is not sending background notices yet.";
    const perm = await Notification.requestPermission();
    if (perm !== "granted") return "Notification permission was not granted.";
    const reg = await navigator.serviceWorker.register("/sw.js");
    const sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: b64urlToBytes(key.public_key),
    });
    const json = sub.toJSON();
    await api.putPush(this.token, {
      endpoint: sub.endpoint,
      p256dh: json.keys?.p256dh,
      auth: json.keys?.auth,
    });
    return "Notices are on. They say Bleep. They do not include the message or the sender.";
  }

  async sendLocation(thread: Thread, lat: number, lon: number, until: number): Promise<void> {
    if (!this.snap || !this.token || thread.kind !== "dm") return;
    const peers = this.destPeers(thread);
    const peer = peers[0];
    if (!peer || peer.safetyChanged || !peerKeyReady(peer)) return;
    const text = `Live location ${lat.toFixed(5)}, ${lon.toFixed(5)} until ${new Date(until).toLocaleTimeString()}. https://www.openstreetmap.org/?mlat=${lat.toFixed(5)}&mlon=${lon.toFixed(5)}`;
    const id = crypto.randomUUID();
    const payload: Payload = { kind: "location", id, text, lat, lon, until };
    const th = this.snap.threads[thread.id];
    const msg: Message = {
      id,
      threadId: thread.id,
      fromMe: true,
      kind: "location",
      text,
      at: Date.now(),
      lat,
      lon,
      until,
    };
    this.snap.messages[thread.id] = [...(this.snap.messages[thread.id] ?? []), msg];
    if (th) {
      th.lastAt = msg.at;
      th.lastPreview = "Live location";
    }
    await this.tryDrop(peer, payload, msg);
    this.bump();
  }

  private async pollBody(): Promise<void> {
    if (!this.token || !this.snap || this.locked) return;
    try {
      await this.flushLater();
      this.alertPending = false;
      const items = (await api.fetch(this.token)) as { id: string; blob_b64: string }[];
      const ack: string[] = [];
      for (const it of items) {
        if (!it.id) continue;
        if (this.openedEnvelopes.has(it.id)) {
          ack.push(it.id);
          continue;
        }
        try {
          if (this.ingest(it.blob_b64)) {
            this.openedEnvelopes.add(it.id);
            ack.push(it.id);
          }
        } catch {
          /* leave the envelope for a device that can open it */
        }
      }
      if (ack.length) {
        try {
          await api.ackMail(this.token, ack);
          for (const id of ack) this.openedEnvelopes.delete(id);
        } catch (err) {
          if (err instanceof RegionMismatch) throw err;
        }
      }
      if (this.sessionDirty) {
        this.sessionDirty = false;
        await this.pushHistory();
      }
      await this.refreshPresence();
      await this.liftShortIds();
      await this.flushOutbox();
      this.sweepDisappearing();
      this.playAlert();
      this.bump();
    } catch (e) {
      if (e instanceof RegionMismatch) {
        this.notice = { kind: "region", text: e.message };
        this.regionOk = false;
        this.bump();
      }
    }
  }

  ingest(blob: string): boolean {
    if (!this.snap) return false;
    const holds = holdsFromSnap(this.snap);
    const opened = openForAck(this.snap, blob, holds);
    const inc = opened.incoming;
    if (!inc) return opened.ack;
    this.captureShared(inc.mailboxId);
    this.sessionDirty = true;
    const fromEd = b64e(inc.fromEd);
    const mailboxId = inc.mailboxId;
    const prev = this.snap.peers[mailboxId];
    const incomingHandle = inc.payload.kind === "intro" ? publishedHandle(inc.payload.handle) : null;
    const handle = incomingHandle || prev?.handle;
    const named = displayOf(inc.payload);
    const peer: Peer = {
      mailboxId,
      edPk: fromEd,
      xPk: b64e(inc.fromX),
      handle,
      displayName: peerLabel(handle, (named && !shortIdFromInput(named) ? named : prev?.displayName) || ""),
      verified: prev?.verified ?? false,
      blocked: this.snap.blocked.includes(mailboxId),
      accepted: prev?.accepted ?? false,
      region: prev?.region ?? "ZA-JHB",
    };
    if (prev && prev.edPk && prev.edPk !== fromEd) {
      peer.safetyChanged = true;
      peer.verified = false;
    }
    upsertPeer(this.snap, peer);
    this.retitle(peer);
    if (inc.payload.id && this.alreadyHave(inc.payload.id)) return true;
    if (peer.blocked) return true;

    if (inc.payload.kind === "poll" || inc.payload.kind === "vote" || inc.payload.kind === "poll-close") {
      if (this.ingestPoll(mailboxId, inc.payload)) this.noteAlert(inc.payload.kind);
      return true;
    }

    if (inc.payload.kind === "intro" && (!peer.accepted || inc.payload.chamber || inc.payload.groupId)) {
      const msg: Message = {
        id: inc.payload.id,
        threadId: "requests",
        fromMe: false,
        fromMailbox: mailboxId,
        kind: "text",
        text: inc.payload.text,
        at: Date.now(),
        status: "delivered",
        chamberInvite: Boolean(inc.payload.chamber),
        burnAt: inc.payload.burnAt,
        groupId: inc.payload.groupId,
        groupName: inc.payload.groupName,
      };
      this.snap.requests.push(msg);
      this.noteAlert("intro");
      return true;
    }

    if (inc.payload.kind === "call") {
      void this.handleCallPayload(peer, inc.payload);
      return true;
    }

    if (inc.payload.kind === "typing") {
      const th = Object.values(this.snap.threads).find((t) => t.peerMailboxId === mailboxId);
      if (th && th.kind !== "chamber") {
        th.peerTypingUntil = Date.now() + 3000;
        this.bump();
      }
      return true;
    }

    if (inc.payload.kind === "receipt") {
      const th = Object.values(this.snap.threads).find((t) => t.peerMailboxId === mailboxId);
      if (!th) return true;
      for (const m of this.snap.messages[th.id] ?? []) {
        if (m.fromMe && m.status === "sent") m.status = "read";
      }
      this.bump();
      return true;
    }

    if (inc.payload.kind === "react") {
      const list = Object.values(this.snap.messages).flat();
      const react = inc.payload;
      const m = list.find((x) => x.id === react.target);
      if (m) m.reacted = react.emoji;
      return true;
    }

    if (inc.payload.kind === "ctrl" && inc.payload.op === "screen-capture") {
      const th = ensureDmThread(this.snap, peer);
      appendMsg(this.snap, {
        id: inc.payload.id || crypto.randomUUID(),
        threadId: th.id,
        fromMe: false,
        kind: "system",
        text: "Their phone reported a screen capture. A photo of the screen is not attached, and a different app can stay silent.",
        at: Date.now(),
      });
      this.noteAlert("ctrl", { op: "screen-capture" });
      this.bump();
      return true;
    }

    if (inc.payload.kind === "ctrl" && inc.payload.op === "status") {
      const data = (inc.payload.data as { text?: string; until?: number; mime?: string; name?: string; bytesB64?: string }) || {};
      let attachmentId: string | undefined;
      if (data.bytesB64) {
        attachmentId = crypto.randomUUID();
        void this.vault.putAttachment(attachmentId, b64d(data.bytesB64), data.mime || "application/octet-stream");
      }
      this.snap.statuses.unshift({
        id: inc.payload.id,
        fromMailbox: mailboxId,
        fromName: peer.displayName,
        text: data.text,
        attachmentId,
        mime: data.mime,
        name: data.name,
        at: Date.now(),
        until: data.until ?? Date.now() + 86_400_000,
      });
      this.noteAlert("ctrl", { op: "status" });
      this.bump();
      return true;
    }

    if (inc.payload.kind === "ctrl" && inc.payload.op === "profile") {
      const data = (inc.payload.data as { clear?: boolean; mime?: string; bytesB64?: string }) || {};
      const stored = this.snap.peers[mailboxId];
      if (!stored) return true;
      if (data.clear || !data.bytesB64) {
        stored.avatarId = "";
      } else {
        const id = crypto.randomUUID();
        void this.vault.putAttachment(id, b64d(data.bytesB64), data.mime || "image/jpeg");
        stored.avatarId = id;
      }
      this.noteAlert("ctrl", { op: "profile" });
      this.bump();
      return true;
    }

    if (inc.payload.kind === "ctrl" && inc.payload.op === "leave-group") {
      const gid = (inc.payload.data as { groupId?: string } | undefined)?.groupId || "";
      const next = applyLeave(this.snap.groups[gid], mailboxId);
      if (next?.changed) {
        this.snap.groups[gid] = next.group;
        const thId = "g:" + gid;
        if (!this.snap.threads[thId]) {
          this.snap.threads[thId] = {
            id: thId,
            kind: "group",
            title: next.group.name,
            lastPreview: "",
            lastAt: Date.now(),
            unread: 0,
            pinned: false,
            muted: false,
            archived: false,
            disappearingSec: null,
            receipts: true,
            typing: false,
            draft: "",
            groupId: gid,
          };
        }
        appendMsg(this.snap, {
          id: inc.payload.id || crypto.randomUUID(),
          threadId: thId,
          fromMe: false,
          kind: "system",
          text: "They left.",
          at: Date.now(),
        });
        this.noteAlert("ctrl", { op: "leave-group" });
      }
      return true;
    }

    if (inc.payload.kind === "ctrl" && inc.payload.op === "edit") {
      const id = (inc.payload.data as { id?: string; text?: string } | undefined)?.id;
      const text = (inc.payload.data as { text?: string } | undefined)?.text;
      const list = Object.values(this.snap.messages).flat();
      const m = list.find((x) => x.id === id);
      if (m && text != null) {
        m.text = text;
        m.edited = true;
      }
      this.bump();
      return true;
    }

    if (inc.payload.kind === "ctrl" && inc.payload.op === "delete") {
      const list = Object.values(this.snap.messages).flat();
      const del = inc.payload;
      const m = list.find((x) => x.id === (del.data as { id?: string } | undefined)?.id);
      if (m) {
        m.deleted = true;
        m.text = "";
        m.starred = false;
      }
      return true;
    }

    if (inc.payload.kind === "intro" || inc.payload.kind === "text" || inc.payload.kind === "photo" || inc.payload.kind === "voice" || inc.payload.kind === "file" || inc.payload.kind === "location") {
      const p = inc.payload;
      if (!peer.accepted && p.kind !== "intro") {
        this.snap.requests.push({
          id: p.id,
          threadId: "requests",
          fromMe: false,
          fromMailbox: mailboxId,
          kind: p.kind === "text" ? "text" : p.kind,
          text: p.text,
          at: Date.now(),
        });
        this.noteAlert(p.kind);
        return true;
      }
      const th = ensureDmThread(this.snap, peer);
      const kind = inc.payload.kind === "intro" ? "text" : inc.payload.kind;
      const msg: Message = {
        id: inc.payload.id,
        threadId: th.id,
        fromMe: false,
        fromMailbox: mailboxId,
        kind: kind as Message["kind"],
        text: "text" in inc.payload ? inc.payload.text : "",
        at: Date.now(),
        name: "name" in inc.payload ? inc.payload.name : undefined,
        mime: "mime" in inc.payload ? inc.payload.mime : undefined,
      };
      const inboundFiles =
        "bytesB64" in inc.payload && inc.payload.bytesB64
          ? [{ name: msg.name || "file", mime: msg.mime || "", bytes: b64d(inc.payload.bytesB64) }]
          : [];
      if (inboundFiles.length) {
        const attId = crypto.randomUUID();
        const hold = !peer.accepted || this.snap.settings.lowData;
        const mime = "mime" in inc.payload ? inc.payload.mime : "";
        void this.vault.putAttachment(attId, inboundFiles[0].bytes, mime);
        msg.attachmentId = attId;
        msg.needsDownload = hold;
      }
      const inboundHits = inspectDraft({
        text: msg.text,
        files: inboundFiles,
        peerVerified: peer.verified,
        safetyJustChanged: false,
        unknownSender: !peer.accepted,
        inbound: true,
      });
      if ("viewOnce" in p && p.viewOnce) msg.viewOnce = true;
      if ("replyTo" in p && p.replyTo) {
        msg.replyTo = p.replyTo;
        msg.replyText = p.replyText;
      }
      const inboundGroup = "groupId" in p && p.groupId ? p.groupId : "";
      const inboundChamber = "chamber" in p && p.chamber === true;
      if (inboundGroup && !this.snap.groups[inboundGroup]) {
        this.snap.requests.push({ ...msg, threadId: "requests", groupId: inboundGroup, fromMailbox: mailboxId });
        this.noteAlert(p.kind);
        return true;
      }
      if (inboundChamber) {
        const chamberThread = Object.values(this.snap.threads).find((t) => t.kind === "chamber" && t.peerMailboxId === mailboxId);
        if (!chamberThread) {
          this.snap.requests.push({ ...msg, threadId: "requests", chamberInvite: true, fromMailbox: mailboxId });
          this.noteAlert(p.kind);
          return true;
        }
        msg.threadId = chamberThread.id;
      } else if (inboundGroup) {
        msg.threadId = "g:" + inboundGroup;
        msg.groupId = inboundGroup;
      }
      if (inboundHits.length && this.snap.settings.guardianMode !== "off") {
        appendMsg(this.snap, {
          id: crypto.randomUUID(),
          threadId: msg.threadId,
          fromMe: false,
          kind: "system",
          text: "Guardian: " + inboundHits.map((h) => h.reason).join(" "),
          at: Date.now(),
        });
      }
      appendMsg(this.snap, msg);
      this.noteAlert(p.kind);
      const home = this.snap.threads[msg.threadId];
      if (home && home.kind !== "chamber" && home.receipts && !msg.fromMe && this.token) {
        void this.seal(peer, {
          kind: "receipt",
          id: crypto.randomUUID(),
          upTo: msg.id,
        });
      }
    }
    return true;
  }

  private alreadyHave(id: string): boolean {
    if (!this.snap || !id) return false;
    if (this.snap.requests.some((r) => r.id === id)) return true;
    return Object.values(this.snap.messages).some((list) => list.some((m) => m.id === id));
  }

  async addByCode(code: string): Promise<Peer> {
    if (!this.snap || !this.token) throw new Error("locked");
    let peer: Peer;
    const short = shortIdFromInput(code);
    const named = short ? null : handleFromInput(code);
    if (short) {
      let h: { mailbox_id: string; identity_ed25519_b64: string; identity_x25519_b64: string; short_id: string; handle?: string | null };
      try {
        h = (await api.resolveShort(this.token, short)) as typeof h;
      } catch (e) {
        const msg = (e as Error).message || "";
        const bare = code.trim().replace(/^@/, "").toLowerCase();
        if (msg.includes("404") && !code.includes("-") && /^[a-z][a-z0-9_]{2,23}$/.test(bare)) {
          return this.addByCode("@" + bare);
        }
        if (msg.includes("404")) throw new Error("No Bleep ID with that code.");
        throw e;
      }
      const handle = publishedHandle(h.handle);
      peer = {
        mailboxId: h.mailbox_id,
        edPk: h.identity_ed25519_b64,
        xPk: h.identity_x25519_b64,
        handle: handle || undefined,
        displayName: peerLabel(handle, ""),
        verified: false,
        blocked: false,
        accepted: false,
        region: "ZA-JHB",
      };
    } else if (named) {
      let h: { mailbox_id: string; identity_ed25519_b64: string; identity_x25519_b64: string; handle: string };
      try {
        h = (await api.resolveHandle(this.token, named)) as typeof h;
      } catch (e) {
        const msg = (e as Error).message || "";
        if (msg.includes("404")) throw new Error("No published handle with that name.");
        throw e;
      }
      const handle = publishedHandle(h.handle || named) || named;
      peer = {
        mailboxId: h.mailbox_id,
        edPk: h.identity_ed25519_b64,
        xPk: h.identity_x25519_b64,
        handle,
        displayName: peerLabel(handle, ""),
        verified: false,
        blocked: false,
        accepted: false,
        region: "ZA-JHB",
      };
    } else {
      try {
        peer = peerFromCode(code);
      } catch {
        throw new Error("Paste a Bleep ID such as k7m2-pq9x, or a published handle.");
      }
      if (peer.region !== this.snap.settings.region) {
        throw new Error(`Their code is pinned to ${peer.region}. Yours is ${this.snap.settings.region}.`);
      }
    }
    if (peer.mailboxId === this.snap.identity.mailboxId) throw new Error("That is this account.");
    if (this.snap.blocked.includes(peer.mailboxId)) throw new Error("That person is blocked.");
    upsertPeer(this.snap, peer);
    this.retitle(peer);
    this.bump();
    return peer;
  }

  async sendRequest(peer: Peer, hello: string): Promise<string> {
    if (!this.snap || !this.token) throw new Error("locked");
    const id = crypto.randomUUID();
    await this.seal(peer, {
      kind: "intro",
      id,
      text: hello,
      displayName: this.snap.identity.displayName,
      handle: publishedHandle(this.snap.settings.handle) || undefined,
    }, { freshIntro: true });
    const stored = this.snap.peers[peer.mailboxId] ?? peer;
    stored.accepted = true;
    const th = ensureDmThread(this.snap, stored);
    appendMsg(this.snap, {
      id,
      threadId: th.id,
      fromMe: true,
      kind: "text",
      text: hello,
      at: Date.now(),
      status: "sent",
    });
    void this.shareProfileWith(stored).catch(() => undefined);
    this.bump();
    return th.id;
  }

  acceptRequest(fromMailbox: string): string | null {
    if (!this.snap) return null;
    const peer = this.snap.peers[fromMailbox];
    if (!peer) return null;
    peer.accepted = true;
    const th = ensureDmThread(this.snap, peer);
    const reqs = this.snap.requests.filter((r) => r.fromMailbox === fromMailbox && !r.groupId && !r.chamberInvite);
    this.snap.requests = this.snap.requests.filter((r) => r.fromMailbox !== fromMailbox || r.groupId || r.chamberInvite);
    for (const r of reqs) {
      appendMsg(this.snap, { ...r, threadId: th.id });
    }
    void this.shareProfileWith(peer).catch(() => undefined);
    this.bump();
    return th.id;
  }

  declineRequest(fromMailbox: string): void {
    if (!this.snap) return;
    this.snap.requests = this.snap.requests.filter((r) => r.fromMailbox !== fromMailbox);
    this.bump();
  }

  previewHits(text: string, files: { name: string; mime: string; bytes: Uint8Array }[], peers: Peer[] = []): Hit[] {
    if (!this.snap || this.snap.settings.guardianMode === "off") return [];
    return inspectDraft({
      text,
      files,
      peerVerified: peers.length === 0 || peers.every((p) => p.verified),
      safetyJustChanged: peers.some((p) => p.safetyChanged),
      several: peers.length > 1,
    });
  }

  destPeers(thread: Thread): Peer[] {
    if (!this.snap) return [];
    if (thread.kind === "group" && thread.groupId) {
      const g = this.snap.groups[thread.groupId];
      if (!g) return [];
      return g.members
        .filter((m) => m.mailboxId !== this.snap!.identity.mailboxId)
        .map((m) => this.snap!.peers[m.mailboxId])
        .filter((p): p is Peer => Boolean(p));
    }
    const p = thread.peerMailboxId ? this.snap.peers[thread.peerMailboxId] : undefined;
    return p ? [p] : [];
  }

  async sendText(
    thread: Thread,
    text: string,
    files: { name: string; mime: string; bytes: Uint8Array }[] = [],
    viewOnce = false,
  ): Promise<Hit[] | void> {
    if (!this.snap || !this.token) return;
    const peers = this.destPeers(thread);
    if (!peers.length) throw new Error("no peer");
    if (peers.some((p) => !peerKeyReady(p))) {
      throw new Error("This chat is on this device, but the key has not arrived yet.");
    }
    if (peers.some((p) => p.safetyChanged)) {
      throw new Error("Safety number changed. Verify it before you send — this is a new key, not a toast.");
    }
    const hits = this.previewHits(text, files, peers);
    if (hits.length && this.snap.settings.guardianMode === "strict") return hits;
    if (hits.length && this.snap.settings.guardianMode === "warn") return hits;
    await this.commitSend(thread, text, files, false, viewOnce);
  }

  async commitSend(
    thread: Thread,
    text: string,
    files: { name: string; mime: string; bytes: Uint8Array }[],
    stripExif = false,
    viewOnce = false,
  ): Promise<void> {
    if (!this.snap || !this.token) return;
    const peers = this.destPeers(thread);
    if (!peers.length) return;
    if (peers.some((p) => !peerKeyReady(p))) {
      throw new Error("This chat is on this device, but the key has not arrived yet.");
    }
    const dropAll = async (payload: Payload, msg: Message) => {
      for (const peer of peers) await this.tryDrop(peer, payload, msg);
    };
    for (const f of files) {
      let bytes = f.bytes;
      let mime = f.mime;
      let name = f.name;
      if (stripExif && mime.startsWith("image/")) {
        bytes = await stripJpeg(bytes);
      }
      const id = crypto.randomUUID();
      await this.vault.putAttachment(id, bytes, mime);
      this.heldLocal.add(id);
      const kind = mime.startsWith("audio/") ? "voice" : mime.startsWith("image/") ? "photo" : "file";
      const msg: Message = {
        id,
        threadId: thread.id,
        fromMe: true,
        kind,
        text,
        at: Date.now(),
        attachmentId: id,
        mime,
        name,
        status: "queued",
        expiresAt: thread.disappearingSec ? Date.now() + thread.disappearingSec * 1000 : undefined,
        viewOnce,
        replyTo: thread.replyTo,
        replyText: thread.replyText,
        groupId: thread.kind === "group" ? thread.groupId : undefined,
      };
      appendMsg(this.snap, msg);
      await dropAll({
        kind,
        id,
        text,
        mime,
        name,
        bytesB64: b64e(bytes),
        expireSec: thread.disappearingSec,
        viewOnce,
        replyTo: thread.replyTo,
        replyText: thread.replyText,
        groupId: thread.kind === "group" ? thread.groupId : undefined,
        chamber: thread.kind === "chamber" ? true : undefined,
      }, msg);
    }
    if (text.trim() && files.length === 0) {
      const id = crypto.randomUUID();
      const msg: Message = {
        id,
        threadId: thread.id,
        fromMe: true,
        kind: "text",
        text,
        at: Date.now(),
        status: "queued",
        expiresAt: thread.disappearingSec ? Date.now() + thread.disappearingSec * 1000 : undefined,
        viewOnce,
        replyTo: thread.replyTo,
        replyText: thread.replyText,
        groupId: thread.kind === "group" ? thread.groupId : undefined,
      };
      appendMsg(this.snap, msg);
      await dropAll({
        kind: "text",
        id,
        text,
        expireSec: thread.disappearingSec,
        viewOnce,
        replyTo: thread.replyTo,
        replyText: thread.replyText,
        groupId: thread.kind === "group" ? thread.groupId : undefined,
        chamber: thread.kind === "chamber" ? true : undefined,
      }, msg);
    }
    thread.draft = "";
    thread.replyTo = undefined;
    thread.replyText = undefined;
    this.bump();
  }

  sendTyping(thread: Thread): void {
    if (!this.snap || !this.token || !thread.typing || thread.kind === "chamber") return;
    for (const peer of this.destPeers(thread)) {
      void this.seal(peer, { kind: "typing", id: crypto.randomUUID() });
    }
  }

  async editMessage(thread: Thread, msg: Message, text: string): Promise<void> {
    if (!msg.fromMe) return;
    msg.text = text;
    msg.edited = true;
    for (const peer of this.destPeers(thread)) {
      if (!this.snap || !this.token) continue;
      await this.seal(peer, {
        kind: "ctrl",
        id: crypto.randomUUID(),
        op: "edit",
        data: { id: msg.id, text },
      });
    }
    this.bump();
  }

  async consumeViewOnce(msg: Message): Promise<void> {
    if (!msg.viewOnce || msg.viewed) return;
    msg.viewed = true;
    msg.needsDownload = false;
    this.bump();
    window.setTimeout(() => {
      msg.deleted = true;
      msg.text = "";
      msg.attachmentId = undefined;
      this.bump();
    }, 8000);
  }

  react(thread: Thread, msg: Message, emoji: string): void {
    if (!this.snap || !this.token) return;
    msg.reacted = emoji;
    for (const peer of this.destPeers(thread)) {
      void this.seal(peer, { kind: "react", id: crypto.randomUUID(), target: msg.id, emoji });
    }
    this.bump();
  }

  deleteForMe(msg: Message): void {
    msg.deleted = true;
    msg.text = "";
    msg.starred = false;
    this.bump();
  }

  toggleStar(msg: Message): void {
    if (!this.snap || msg.deleted) return;
    msg.starred = !msg.starred;
    this.bump();
  }

  scheduleText(thread: Thread, text: string, sendAt: number): string {
    if (!this.snap) return "Open Bleep first.";
    if (thread.kind !== "dm" && thread.kind !== "group") return "Send later stays on a direct chat or a group.";
    const next = enqueueLater(this.snap.later ?? [], {
      id: crypto.randomUUID(),
      threadId: thread.id,
      text,
      sendAt,
    });
    if (!next) return "Twenty scheduled messages is the limit on this device.";
    this.snap.later = next;
    this.bump();
    return "Scheduled on this device. It sends while Bleep is open. If Bleep is closed, it sends the next time you open it.";
  }

  async sendPoll(thread: Thread, question: string, options: string[]): Promise<void> {
    if (!this.snap || !this.token || thread.kind !== "group" || !thread.groupId) return;
    const clean = options.map((s) => s.trim()).filter(Boolean).slice(0, 4);
    if (!question.trim() || clean.length < 2) throw new Error("A poll needs a question and at least two choices.");
    const peers = this.destPeers(thread);
    if (!peers.length) throw new Error("no peer");
    const id = crypto.randomUUID();
    const poll: PollState = { question: question.trim().slice(0, 120), options: clean.map((s) => s.slice(0, 40)), votes: {} };
    const msg: Message = {
      id,
      threadId: thread.id,
      fromMe: true,
      fromMailbox: this.snap.identity.mailboxId,
      kind: "poll",
      text: poll.question,
      at: Date.now(),
      status: "queued",
      poll,
      groupId: thread.groupId,
    };
    appendMsg(this.snap, msg);
    for (const peer of peers) {
      await this.tryDrop(peer, { kind: "poll", id, question: poll.question, options: poll.options, groupId: thread.groupId }, msg);
    }
    this.bump();
  }

  async votePoll(thread: Thread, pollId: string, option: number): Promise<void> {
    if (!this.snap || !this.token || !thread.groupId) return;
    const msg = (this.snap.messages[thread.id] ?? []).find((m) => m.id === pollId);
    if (!msg?.poll) return;
    const members = this.snap.groups[thread.groupId]?.members.map((m) => m.mailboxId) ?? [];
    const next = applyVote(msg.poll, this.snap.identity.mailboxId, option, members);
    if (!next) return;
    msg.poll = next;
    const id = crypto.randomUUID();
    for (const peer of this.destPeers(thread)) {
      await this.seal(peer, { kind: "vote", id, pollId, option, groupId: thread.groupId });
    }
    this.bump();
  }

  async closePollMessage(thread: Thread, pollId: string): Promise<void> {
    if (!this.snap || !this.token || !thread.groupId) return;
    const msg = (this.snap.messages[thread.id] ?? []).find((m) => m.id === pollId);
    if (!msg?.poll) return;
    const author = msg.fromMailbox || this.snap.identity.mailboxId;
    const next = closePoll(msg.poll, this.snap.identity.mailboxId, author);
    if (!next) return;
    msg.poll = next;
    const id = crypto.randomUUID();
    for (const peer of this.destPeers(thread)) {
      await this.seal(peer, { kind: "poll-close", id, pollId, groupId: thread.groupId });
    }
    this.bump();
  }

  private async flushLater(): Promise<void> {
    if (!this.snap || !this.token || this.locked) return;
    const split = takeDue(this.snap.later ?? [], Date.now());
    this.snap.later = split.waiting;
    for (const item of split.due) {
      const thread = this.snap.threads[item.threadId];
      if (!thread || thread.kind === "chamber" || thread.kind === "status") continue;
      try {
        await this.commitSend(thread, item.text, []);
      } catch {
        this.snap.later = [...(this.snap.later ?? []), item];
      }
    }
  }

  private noteAlert(kind: string, extra?: { phase?: string; op?: string }): void {
    if (shouldAlert(kind, extra)) this.alertPending = true;
  }

  private playAlert(): void {
    if (!this.alertPending || !this.snap || this.locked) return;
    const now = new Date();
    const quiet = inQuietHours(this.snap.quietStartMin, this.snap.quietEndMin, now.getHours() * 60 + now.getMinutes());
    if (!shouldAlert("text", { quiet, enabled: this.snap.alertSound !== false })) return;
    void playMailTone().then((ok) => {
      if (this.alertBlocked === ok) {
        this.alertBlocked = !ok;
        this.bump();
      }
    });
  }

  private ingestPoll(mailboxId: string, payload: Extract<Payload, { kind: "poll" | "vote" | "poll-close" }>): boolean {
    if (!this.snap) return false;
    const groupId = payload.groupId;
    const group = this.snap.groups[groupId];
    if (!group) return false;
    const members = group.members.map((m) => m.mailboxId);
    if (!members.includes(mailboxId)) return false;
    const threadId = "g:" + groupId;
    if (payload.kind === "poll") {
      const poll: PollState = { question: payload.question, options: payload.options, votes: {} };
      appendMsg(this.snap, {
        id: payload.id,
        threadId,
        fromMe: false,
        fromMailbox: mailboxId,
        kind: "poll",
        text: payload.question,
        at: Date.now(),
        poll,
        groupId,
      });
      return true;
    }
    const msg = (this.snap.messages[threadId] ?? []).find((m) => m.id === payload.pollId);
    if (!msg?.poll) return false;
    if (payload.kind === "vote") {
      const next = applyVote(msg.poll, mailboxId, payload.option, members);
      if (next) msg.poll = next;
      return false;
    }
    const author = msg.fromMailbox || (msg.fromMe ? this.snap.identity.mailboxId : "");
    const next = closePoll(msg.poll, mailboxId, author);
    if (!next) return false;
    msg.poll = next;
    return true;
  }

  async deleteForEveryone(thread: Thread, msg: Message): Promise<void> {
    this.deleteForMe(msg);
    if (!this.snap || !this.token) return;
    for (const peer of this.destPeers(thread)) {
      await this.seal(peer, {
        kind: "ctrl",
        id: crypto.randomUUID(),
        op: "delete",
        data: { id: msg.id },
      });
    }
  }

  verifyPeer(peer: Peer): void {
    if (!this.snap) return;
    peer.verified = true;
    peer.safetyChanged = false;
    peer.lastSafety = pairSafety(this.snap.identity, peer);
    this.bump();
  }

  block(peer: Peer): void {
    if (!this.snap) return;
    peer.blocked = true;
    this.snap.blocked.push(peer.mailboxId);
    this.bump();
  }

  async startChamber(peer: Peer, days = 7): Promise<ChamberMeta> {
    if (!this.snap || !this.token) throw new Error("locked");
    const id = generateIdentity("chamber");
    const { spk, opks } = makePrekeys(8);
    const registered = await registerOnRelay(id, spk, opks);
    const meta: ChamberMeta = {
      id: id.mailboxId,
      title: `Chamber · ${peer.displayName}`,
      mailboxId: id.mailboxId,
      peerMailboxId: peer.mailboxId,
      burnAt: Date.now() + days * 86400_000,
      createdAt: Date.now(),
      unlocked: true,
      unread: 0,
      relayToken: registered.token,
    };
    this.snap.chambers[meta.id] = meta;
    this.snap.threads["ch:" + meta.id] = {
      id: "ch:" + meta.id,
      kind: "chamber",
      peerMailboxId: peer.mailboxId,
      title: meta.title,
      lastPreview: "Waiting for them to accept Chamber",
      lastAt: Date.now(),
      unread: 0,
      pinned: false,
      muted: false,
      archived: false,
      disappearingSec: 86400,
      receipts: false,
      typing: false,
      draft: "",
      chamberId: meta.id,
    };
    await this.seal(peer, {
      kind: "intro",
      id: crypto.randomUUID(),
      text: `${this.snap.identity.displayName} wants a Chamber. Burn in ${days} days. Public Bleep cannot delete their screenshot.`,
      displayName: this.snap.identity.displayName,
      handle: publishedHandle(this.snap.settings.handle) || undefined,
      chamber: true,
      burnAt: meta.burnAt,
      ephCode: `${id.mailboxId}`,
    });
    this.bump();
    return meta;
  }

  burnChamber(id: string): void {
    if (!this.snap || !this.token) return;
    const ch = this.snap.chambers[id];
    if (!ch) return;
    this.chamberPins.delete(id);
    if (ch.relayToken && ch.mailboxId !== this.snap.identity.mailboxId) {
      void api.unlink(ch.mailboxId, ch.relayToken);
    }
    delete this.snap.chambers[id];
    delete this.snap.threads["ch:" + id];
    delete this.snap.messages["ch:" + id];
    this.bump();
  }

  async publishStatus(text: string, file?: { name: string; mime: string; bytes: Uint8Array }): Promise<void> {
    if (!this.snap || !this.token) return;
    const body = text.trim();
    if (!body && !file) return;
    if (file && file.bytes.byteLength > 3_000_000) throw new Error("Keep status files under 3 MB.");
    let attachmentId: string | undefined;
    if (file) {
      attachmentId = crypto.randomUUID();
      await this.vault.putAttachment(attachmentId, file.bytes, file.mime);
    }
    const item: StatusItem = {
      id: crypto.randomUUID(),
      fromMailbox: this.snap.identity.mailboxId,
      fromName: this.snap.identity.displayName,
      text: body || undefined,
      attachmentId,
      mime: file?.mime,
      name: file?.name,
      at: Date.now(),
      until: Date.now() + 86400_000,
    };
    this.snap.statuses.unshift(item);
    const peers = Object.values(this.snap.peers).filter((p) => p.accepted && !p.blocked);
    const data: { text?: string; until: number; mime?: string; name?: string; bytesB64?: string } = {
      text: body || undefined,
      until: item.until,
    };
    if (file) {
      data.mime = file.mime;
      data.name = file.name;
      data.bytesB64 = b64e(file.bytes);
    }
    for (const peer of peers) {
      await this.seal(peer, { kind: "ctrl", id: item.id, op: "status", data }, { ttl: 86400 });
    }
    this.bump();
  }

  async setProfilePhoto(bytes: Uint8Array, mime: string): Promise<void> {
    if (!this.snap || !this.token) return;
    if (bytes.byteLength > 1_500_000) throw new Error("Profile photo is too large.");
    const id = crypto.randomUUID();
    await this.vault.putAttachment(id, bytes, mime);
    this.snap.settings.avatarId = id;
    const peers = Object.values(this.snap.peers).filter((p) => p.accepted && !p.blocked);
    for (const peer of peers) await this.shareProfileWith(peer);
    this.bump();
  }

  async clearProfilePhoto(): Promise<void> {
    if (!this.snap || !this.token) return;
    this.snap.settings.avatarId = "";
    const peers = Object.values(this.snap.peers).filter((p) => p.accepted && !p.blocked);
    for (const peer of peers) {
      await this.seal(peer, {
        kind: "ctrl",
        id: crypto.randomUUID(),
        op: "profile",
        data: { clear: true },
      });
    }
    this.bump();
  }

  private async shareProfileWith(peer: Peer): Promise<void> {
    if (!this.snap || !this.token || peer.blocked) return;
    const id = this.snap.settings.avatarId;
    if (!id) return;
    const att = await this.vault.getAttachment(id).catch(() => null);
    if (!att) return;
    await this.seal(peer, {
      kind: "ctrl",
      id: crypto.randomUUID(),
      op: "profile",
      data: { mime: att.mime || "image/jpeg", bytesB64: b64e(att.bytes) },
    });
  }

  async createGroup(name: string, memberIds: string[]): Promise<string> {
    if (!this.snap || !this.token) throw new Error("locked");
    if (memberIds.length > 15) throw new Error("v1 groups stay small");
    const id = crypto.randomUUID().replace(/-/g, "").slice(0, 16);
    const members = [
      {
        mailboxId: this.snap.identity.mailboxId,
        edPk: b64e(this.snap.identity.edPk),
        role: "admin" as const,
      },
      ...memberIds.map((mid) => ({
        mailboxId: mid,
        edPk: this.snap!.peers[mid].edPk,
        role: "member" as const,
      })),
    ];
    this.snap.groups[id] = { id, name, members, version: 1 };
    this.snap.threads["g:" + id] = {
      id: "g:" + id,
      kind: "group",
      title: name,
      lastPreview: "Group created — join is explicit",
      lastAt: Date.now(),
      unread: 0,
      pinned: false,
      muted: false,
      archived: false,
      disappearingSec: null,
      receipts: true,
      typing: false,
      draft: "",
      groupId: id,
    };
    for (const mid of memberIds) {
      const peer = this.snap.peers[mid];
      await this.seal(peer, {
        kind: "intro",
        id: crypto.randomUUID(),
        text: `${this.snap.identity.displayName} invited you to “${name}”. Join is explicit — they cannot silent-add you.`,
        displayName: this.snap.identity.displayName,
        handle: publishedHandle(this.snap.settings.handle) || undefined,
        groupId: id,
        groupName: name,
      });
    }
    this.bump();
    return "g:" + id;
  }

  async leaveGroup(groupId: string): Promise<void> {
    if (!this.snap || !this.token) return;
    const group = this.snap.groups[groupId];
    if (!group) return;
    const mine = this.snap.identity.mailboxId;
    for (const member of group.members) {
      if (member.mailboxId === mine) continue;
      const peer = this.snap.peers[member.mailboxId];
      if (!peer || !peerKeyReady(peer)) continue;
      await this.seal(peer, {
        kind: "ctrl",
        id: crypto.randomUUID(),
        op: "leave-group",
        data: { groupId },
      });
    }
    delete this.snap.groups[groupId];
    delete this.snap.threads["g:" + groupId];
    delete this.snap.messages["g:" + groupId];
    this.bump();
  }

  acceptGroup(fromMailbox: string, groupId: string, groupName: string): void {
    if (!this.snap) return;
    const peer = this.snap.peers[fromMailbox];
    if (!peer) return;
    peer.accepted = true;
    const members = [
      { mailboxId: this.snap.identity.mailboxId, edPk: b64e(this.snap.identity.edPk), role: "member" as const },
      { mailboxId: peer.mailboxId, edPk: peer.edPk, role: "admin" as const },
    ];
    this.snap.groups[groupId] = { id: groupId, name: groupName, members, version: 1 };
    this.snap.threads["g:" + groupId] = {
      id: "g:" + groupId,
      kind: "group",
      title: groupName,
      lastPreview: "You joined. Member-add is notified; silent add is impossible.",
      lastAt: Date.now(),
      unread: 0,
      pinned: false,
      muted: false,
      archived: false,
      disappearingSec: null,
      receipts: true,
      typing: false,
      draft: "",
      groupId,
    };
    const held = this.snap.requests.filter((r) => r.groupId === groupId);
    this.snap.requests = this.snap.requests.filter((r) => r.groupId !== groupId);
    for (const row of held) appendMsg(this.snap, { ...row, threadId: "g:" + groupId });
    this.bump();
  }

  async openHeld(msg: Message): Promise<void> {
    if (msg.attachmentId) {
      const have = await this.vault.getAttachment(msg.attachmentId).catch(() => null);
      if (!have) await this.fetchSealedFile(msg);
    }
    msg.needsDownload = false;
    this.bump();
  }

  async closeAccount(password: string): Promise<void> {
    const email = this.snap?.settings.accountEmail;
    if (!email) throw new Error("This vault has no email login.");
    const account = (await api.loginAccount(email, password)) as { account_token: string };
    await api.closeAccount(account.account_token, password);
    await this.vault.erase();
    this.lockNow();
  }

  async confirmEmail(token: string): Promise<void> {
    await api.confirmAccount(token);
    if (this.snap) {
      this.snap.settings.emailConfirmed = true;
      this.snap.settings.confirmNote = undefined;
      this.bump();
    }
  }

  async remoteUnlink(): Promise<void> {
    if (!this.snap || !this.token) return;
    await api.unlink(this.snap.identity.mailboxId, this.token);
    this.lockNow();
  }

  async eraseDevice(): Promise<void> {
    if (this.snap && this.token) {
      const deviceId = this.snap.settings.deviceId;
      try {
        if (deviceId) await api.eraseDeviceRemote(this.token, deviceId);
        else if (!this.snap.settings.accountEmail) await api.unlink(this.snap.identity.mailboxId, this.token);
      } catch {
        /* still wipe local */
      }
    }
    await this.vault.erase();
    this.lockNow();
  }

  identity(): Identity | null {
    return this.snap?.identity ?? null;
  }

  acceptChamber(fromMailbox: string, burnAt?: number): void {
    if (!this.snap) return;
    const peer = this.snap.peers[fromMailbox];
    if (!peer) return;
    peer.accepted = true;
    const id = "ch:" + fromMailbox;
    this.snap.chambers[fromMailbox] = {
      id: fromMailbox,
      title: `Chamber · ${peer.displayName}`,
      mailboxId: this.snap.identity.mailboxId,
      peerMailboxId: fromMailbox,
      burnAt: burnAt || Date.now() + 7 * 86400_000,
      createdAt: Date.now(),
      unlocked: true,
      unread: 0,
    };
    this.snap.threads[id] = {
      id,
      kind: "chamber",
      peerMailboxId: fromMailbox,
      title: `Chamber · ${peer.displayName}`,
      lastPreview: "Both of you accepted Chamber. Screenshots are not ours to delete.",
      lastAt: Date.now(),
      unread: 0,
      pinned: false,
      muted: false,
      archived: false,
      disappearingSec: 86400,
      receipts: false,
      typing: false,
      draft: "",
      chamberId: fromMailbox,
    };
    const reqs = this.snap.requests.filter((r) => r.fromMailbox === fromMailbox && r.chamberInvite);
    this.snap.requests = this.snap.requests.filter((r) => !(r.fromMailbox === fromMailbox && r.chamberInvite));
    for (const r of reqs) appendMsg(this.snap, { ...r, threadId: id, chamberInvite: false });
    this.bump();
  }

  lockChamber(id: string): void {
    if (!this.snap) return;
    this.chamberPins.delete(id);
    const ch = this.snap.chambers[id];
    const th = this.snap.threads["ch:" + id];
    if (ch) ch.unlocked = false;
    if (th) {
      th.lockedStub = true;
      th.lastPreview = "Chamber active · locked";
    }
    this.bump();
  }

  async unlockChamber(id: string, pin: string): Promise<boolean> {
    if (!this.snap || pin.length < 4) return false;
    if (this.token) {
      try {
        const row = (await api.getHistoryExtra(this.token, id)) as {
          generation: number;
          nonce_b64: string;
          blob_b64: string;
        };
        const pt = aeadDecrypt(chamberKey(pin, id), b64d(row.nonce_b64), b64d(row.blob_b64));
        const data = JSON.parse(fromUtf8(pt)) as { meta?: ChamberMeta; thread?: Thread; messages?: Message[] };
        this.extraGen.set(id, row.generation);
        if (data.meta) this.snap.chambers[id] = { ...data.meta, unlocked: true };
        if (data.thread) this.snap.threads["ch:" + id] = { ...data.thread, lockedStub: false };
        if (data.messages) this.snap.messages["ch:" + id] = data.messages;
      } catch (e) {
        const msg = (e as Error).message || "";
        if (!msg.includes("404")) return false;
      }
    }
    this.chamberPins.set(id, pin);
    const ch = this.snap.chambers[id];
    const th = this.snap.threads["ch:" + id];
    if (ch) ch.unlocked = true;
    if (th) th.lockedStub = false;
    this.bump();
    return true;
  }

  pinThread(thread: Thread): void {
    thread.pinned = !thread.pinned;
    this.bump();
  }

  muteThread(thread: Thread): void {
    cycleMute(thread);
    this.bump();
  }

  cycleDisappear(thread: Thread): void {
    thread.disappearingSec = nextDisappear(thread.disappearingSec);
    this.bump();
  }

  setNickname(peer: Peer, raw: string): void {
    const name = raw.trim();
    peer.nickname = name || undefined;
    this.retitle(peer);
    this.bump();
  }

  archiveThread(thread: Thread): void {
    thread.archived = !thread.archived;
    this.bump();
  }

  async publishHandle(handle: string): Promise<void> {
    if (!this.snap || !this.token) return;
    await api.publishHandle(this.token, handle);
    this.snap.settings.handle = handle;
    this.bump();
  }

  async unpublishHandle(): Promise<void> {
    if (!this.snap || !this.token) return;
    await api.dropHandle(this.token);
    this.snap.settings.handle = undefined;
    this.bump();
  }

  async restoreFromExport(fileText: string, passphrase: string, pin: string): Promise<void> {
    const snap = await this.vault.importBlob(fileText, passphrase);
    this.snap = snap;
    await this.vault.create(pin, snap);
    this.locked = false;
    const session = await login(snap.identity);
    this.token = session.token;
    if (session.shortId) snap.settings.shortId = session.shortId;
    snap.settings.handle = session.handle || undefined;
    this.regionOk = true;
    await this.poll();
    this.connectWake();
    this.armIdle();
    this.bump();
  }

  async startCall(peer: Peer, media: "audio" | "video" = "audio"): Promise<void> {
    if (!this.snap || !this.token) return;
    await this.preparePc(peer, media, true);
    const offer = await this.pc!.createOffer();
    await this.pc!.setLocalDescription(offer);
    this.call = { phase: "ringing-out", peer, media, remoteStream: this.call.remoteStream, muted: false };
    this.callTimer = window.setTimeout(() => void this.missedCall(peer), 30_000);
    await this.seal(peer, {
      kind: "call",
      id: crypto.randomUUID(),
      phase: "offer",
      sdp: offer.sdp,
      media,
    });
    this.bump();
  }

  async acceptCall(): Promise<void> {
    const peer = this.call.peer;
    if (!peer || !this.pendingOffer || !this.snap || !this.token) return;
    await this.preparePc(peer, this.call.media, true);
    await this.pc!.setRemoteDescription(this.pendingOffer);
    const answer = await this.pc!.createAnswer();
    await this.pc!.setLocalDescription(answer);
    this.call.phase = "active";
    await this.seal(peer, {
      kind: "call",
      id: crypto.randomUUID(),
      phase: "answer",
      sdp: answer.sdp,
      media: this.call.media,
    });
    this.bump();
  }

  async hangup(): Promise<void> {
    const peer = this.call.peer;
    if (this.snap && this.token && peer) {
      try {
        await this.seal(peer, {
          kind: "call",
          id: crypto.randomUUID(),
          phase: "hangup",
        });
      } catch {
        /* still tear down */
      }
    }
    this.teardownCall();
    this.bump();
  }

  private async missedCall(peer: Peer): Promise<void> {
    if (this.call.phase !== "ringing-out") return;
    if (this.snap && this.token) {
      try {
        await this.seal(peer, {
          kind: "call",
          id: crypto.randomUUID(),
          phase: "missed",
        });
      } catch {
        /* local row still */
      }
    }
    this.addCallRow(peer, "Missed call", true);
    this.teardownCall();
    this.bump();
  }

  private async handleCallPayload(peer: Peer, p: Extract<Payload, { kind: "call" }>): Promise<void> {
    if (p.phase === "offer" && p.sdp) {
      this.pendingOffer = { type: "offer", sdp: p.sdp };
      this.call = { phase: "ringing-in", peer, media: p.media ?? "audio", remoteStream: null, muted: false };
      this.bump();
      return;
    }
    if (p.phase === "answer" && p.sdp && this.pc) {
      if (this.callTimer) window.clearTimeout(this.callTimer);
      await this.pc.setRemoteDescription({ type: "answer", sdp: p.sdp });
      this.call.phase = "active";
      this.bump();
      return;
    }
    if (p.phase === "ice" && p.candidate && this.pc) {
      try {
        const init = JSON.parse(p.candidate) as RTCIceCandidateInit;
        await this.pc.addIceCandidate(init);
      } catch {
        /* trickle race */
      }
      return;
    }
    if (p.phase === "hangup") {
      if (this.call.phase === "ringing-in") this.addCallRow(peer, "Missed call", false);
      this.teardownCall();
      this.bump();
      return;
    }
    if (p.phase === "missed") {
      this.addCallRow(peer, "Missed call", false);
      this.teardownCall();
      this.bump();
    }
  }

  private addCallRow(peer: Peer, text: string, fromMe: boolean): void {
    if (!this.snap) return;
    const th = ensureDmThread(this.snap, peer);
    appendMsg(this.snap, {
      id: crypto.randomUUID(),
      threadId: th.id,
      fromMe,
      kind: "call",
      text,
      at: Date.now(),
    });
  }

  private async preparePc(peer: Peer, media: "audio" | "video", withLocal: boolean): Promise<void> {
    this.pc?.close();
    this.localStream?.getTracks().forEach((t) => t.stop());
    this.pc = new RTCPeerConnection({ iceServers: iceServers() });
    this.pc.ontrack = (ev) => {
      this.call.remoteStream = ev.streams[0] ?? new MediaStream([ev.track]);
      this.bump();
    };
    this.pc.onicecandidate = (ev) => {
      if (!ev.candidate || !this.snap || !this.token) return;
      void this.seal(peer, {
        kind: "call",
        id: crypto.randomUUID(),
        phase: "ice",
        candidate: JSON.stringify(ev.candidate.toJSON()),
      });
    };
    if (withLocal) {
      this.localStream = await navigator.mediaDevices.getUserMedia({
        audio: true,
        video: media === "video",
      });
      for (const t of this.localStream.getTracks()) this.pc.addTrack(t, this.localStream);
    }
  }

  private teardownCall(): void {
    if (this.callTimer) window.clearTimeout(this.callTimer);
    this.callTimer = null;
    this.pc?.close();
    this.pc = null;
    this.localStream?.getTracks().forEach((t) => t.stop());
    this.localStream = null;
    this.pendingOffer = null;
    this.call = { phase: "idle", peer: null, media: "audio", remoteStream: null, muted: false };
  }

  private installShared(peerId: string, dumped: SerializedRatchet): void {
    if (!this.snap) return;
    this.snap.sessions[peerId] = {
      peerMailboxId: peerId,
      ratchet: dumped,
      established: true,
    };
  }

  private captureShared(peerId: string): void {
    const rec = this.snap?.sessions[peerId];
    if (!rec?.established || !isRatchetDump(rec.ratchet)) return;
    this.snap!.sharedSessions = { ...(this.snap!.sharedSessions ?? {}), [peerId]: rec.ratchet };
  }

  private async seal(peer: Peer, payload: Payload, opts?: { ttl?: number; freshIntro?: boolean }): Promise<void> {
    if (!this.snap || !this.token) return;
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        await this.pullHistory();
      } catch (err) {
        const msg = (err as Error).message || "";
        if (msg.includes("erase")) throw err;
      }
      if (!this.snap || !this.token) return;
      if (opts?.freshIntro && attempt === 0) {
        const threadId = dmThreadId(this.snap.identity.mailboxId, peer.mailboxId);
        if ((this.snap.messages[threadId] ?? []).length === 0) {
          delete this.snap.sessions[peer.mailboxId];
          if (this.snap.sharedSessions) delete this.snap.sharedSessions[peer.mailboxId];
        }
      }
      const shared = this.snap.sharedSessions?.[peer.mailboxId];
      const live = Boolean(this.snap.sessions[peer.mailboxId]?.established);
      const choice = x3dhChoice(Boolean(shared && isRatchetDump(shared)), live);
      if (choice === "shared" && shared && isRatchetDump(shared)) this.installShared(peer.mailboxId, shared);
      try {
        await this.pushHistory({ rethrowConflict: true });
      } catch (err) {
        const msg = (err as Error).message || "";
        if (msg.includes("409")) {
          if (conflictStep(attempt) === "stop") throw new Error(OTHER_DEVICE);
          continue;
        }
        throw err;
      }
      await sendSealed(this.snap, this.token, peer, payload, opts);
      this.captureShared(peer.mailboxId);
      try {
        await this.pushHistory({ rethrowConflict: true });
      } catch (err) {
        const msg = (err as Error).message || "";
        if (msg.includes("409")) await this.pullHistory();
        else throw err;
      }
      return;
    }
    throw new Error(OTHER_DEVICE);
  }

  private async tryDrop(peer: Peer, payload: Payload, msg: Message): Promise<void> {
    if (!this.snap || !this.token) return;
    try {
      await this.seal(peer, payload);
      msg.status = "sent";
    } catch (err) {
      if ((err as Error).message === OTHER_DEVICE) {
        msg.status = "failed";
        throw err;
      }
      msg.status = "queued";
    }
  }

  async flushOutbox(): Promise<void> {
    if (!this.snap || !this.token) return;
    for (const th of Object.values(this.snap.threads)) {
      const peer = th.peerMailboxId ? this.snap.peers[th.peerMailboxId] : undefined;
      if (!peer) continue;
      for (const m of this.snap.messages[th.id] ?? []) {
        if (!m.fromMe || m.status !== "queued" || m.kind === "system" || m.kind === "call") continue;
        try {
          if (m.kind === "text") {
            await this.seal(peer, {
              kind: "text",
              id: m.id,
              text: m.text,
            });
          } else if (m.kind === "location" && m.lat != null && m.lon != null && m.until != null) {
            await this.seal(peer, {
              kind: "location",
              id: m.id,
              text: m.text,
              lat: m.lat,
              lon: m.lon,
              until: m.until,
            });
          } else if (m.attachmentId) {
            const att = await this.vault.getAttachment(m.attachmentId);
            if (!att) continue;
            const kind = m.kind === "photo" || m.kind === "voice" || m.kind === "file" ? m.kind : "file";
            await this.seal(peer, {
              kind,
              id: m.id,
              text: m.text,
              mime: att.mime,
              name: m.name || "file",
              bytesB64: b64e(att.bytes),
            });
          }
          m.status = "sent";
        } catch (err) {
          if ((err as Error).message === OTHER_DEVICE) m.status = "failed";
        }
      }
    }
    this.bump();
  }

  private retitle(peer: Peer): void {
    if (!this.snap) return;
    for (const th of Object.values(this.snap.threads)) {
      if (th.peerMailboxId !== peer.mailboxId) continue;
      if (th.kind === "dm") th.title = peer.nickname?.trim() || peer.displayName;
      if (th.kind === "chamber") th.title = `Chamber · ${peer.displayName}`;
    }
  }

  private async liftShortIds(): Promise<void> {
    if (this.labelsReady || !this.snap || !this.token) return;
    const pending = Object.values(this.snap.peers).filter((p) => !p.handle && shortIdFromInput(p.displayName));
    this.labelsReady = true;
    for (const peer of pending) {
      const short = shortIdFromInput(peer.displayName);
      if (!short) continue;
      try {
        const row = (await api.resolveShort(this.token, short)) as { handle?: string | null };
        const handle = publishedHandle(row.handle);
        if (handle) peer.handle = handle;
      } catch (e) {
        const msg = (e as Error).message || "";
        if (!msg.includes("404")) {
          this.labelsReady = false;
          continue;
        }
      }
      peer.displayName = peerLabel(peer.handle, peer.displayName);
      this.retitle(peer);
    }
    for (const peer of Object.values(this.snap.peers)) {
      if (!peer.handle) continue;
      const label = peerLabel(peer.handle, peer.displayName);
      if (peer.displayName !== label) {
        peer.displayName = label;
        this.retitle(peer);
      }
    }
  }

  sweepDisappearing(): void {
    if (!this.snap) return;
    const now = Date.now();
    for (const list of Object.values(this.snap.messages)) {
      for (const m of list) {
        if (m.expiresAt && m.expiresAt < now && !m.deleted) {
          m.deleted = true;
          m.text = "";
        }
      }
    }
  }
}

function displayOf(p: Payload): string | undefined {
  if (p.kind === "intro") return p.displayName;
  return undefined;
}

async function stripJpeg(bytes: Uint8Array): Promise<Uint8Array> {
  return new Promise((resolve) => {
    const blob = new Blob([asAB(bytes)], { type: "image/jpeg" });
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
      const c = document.createElement("canvas");
      c.width = img.width;
      c.height = img.height;
      const ctx = c.getContext("2d");
      ctx?.drawImage(img, 0, 0);
      c.toBlob(
        async (b) => {
          URL.revokeObjectURL(url);
          if (!b) return resolve(bytes);
          resolve(new Uint8Array(await b.arrayBuffer()));
        },
        "image/jpeg",
        0.88,
      );
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      resolve(bytes);
    };
    img.src = url;
  });
}
