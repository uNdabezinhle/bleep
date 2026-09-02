import { asAB, b64d, b64e } from "./protocol/bytes";
import { generateIdentity, type Identity } from "./protocol/keys";
import { api, wakeUrl, RegionMismatch } from "./net/api";
import { inspectDraft, type Hit } from "./guardian/engine";
import {
  appendMsg,
  dmThreadId,
  ensureDmThread,
  login,
  makePrekeys,
  openIncoming,
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
import type { ChamberMeta, Message, Peer, Thread, VaultSnapshot } from "./types";
import { parseBleepCode } from "./protocol/keys";
import { iceServers } from "./calls/ice";


export type UiNotice = { kind: "error" | "info" | "region"; text: string };

function holdsFromSnap(snap: VaultSnapshot): { spk: SpkHold; opks: OpkHold[] } {
  return {
    spk: { id: snap.prekeys.spk.id, sk: b64d(snap.prekeys.spk.sk), pk: b64d(snap.prekeys.spk.pk) },
    opks: snap.prekeys.opks.map((k) => ({ id: k.id, sk: b64d(k.sk), pk: b64d(k.pk) })),
  };
}

function storeHolds(spk: SpkHold, opks: OpkHold[]) {
  return {
    spk: { id: spk.id, sk: b64e(spk.sk), pk: b64e(spk.pk) },
    opks: opks.map((k) => ({ id: k.id, sk: b64e(k.sk), pk: b64e(k.pk) })),
  };
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
  private callTimer: number | null = null;

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
    this.token = await login(this.snap.identity);
    this.regionOk = true;
    await this.poll();
    await this.flushOutbox();
    this.connectWake();
    this.armIdle();
    this.sweepDisappearing();
    this.bump();
  }

  lockNow(): void {
    this.teardownCall();
    this.vault.lock();
    this.snap = null;
    this.token = null;
    this.locked = true;
    this.ws?.close();
    this.ws = null;
    if (this.idleTimer) window.clearTimeout(this.idleTimer);
    this.bump();
  }

  touch(): void {
    this.lastActivity = Date.now();
    this.armIdle();
  }

  private armIdle(): void {
    if (this.idleTimer) window.clearTimeout(this.idleTimer);
    const sec = this.snap?.settings.autoLockSec ?? 60;
    this.idleTimer = window.setTimeout(() => this.lockNow(), sec * 1000);
  }

  persist = async (): Promise<void> => {
    if (this.snap && this.vault.unlocked) await this.vault.save(this.snap);
  };

  bump(): void {
    this.onChange();
    void this.persist();
  }

  private connectWake(): void {
    if (!this.token) return;
    try {
      this.ws?.close();
      const ws = new WebSocket(wakeUrl(this.token));
      ws.onmessage = () => {
        void this.poll();
      };
      ws.onclose = () => {
        window.setTimeout(() => {
          if (!this.locked) this.connectWake();
        }, 4000);
      };
      this.ws = ws;
    } catch {
      /* wake is best-effort */
    }
  }

  async poll(): Promise<void> {
    if (!this.token || !this.snap) return;
    try {
      const items = (await api.fetch(this.token)) as { id: string; blob_b64: string }[];
      for (const it of items) this.ingest(it.blob_b64);
      await this.flushOutbox();
      this.sweepDisappearing();
      this.bump();
    } catch (e) {
      if (e instanceof RegionMismatch) {
        this.notice = { kind: "region", text: e.message };
        this.regionOk = false;
        this.bump();
      }
    }
  }

  ingest(blob: string): void {
    if (!this.snap) return;
    const holds = holdsFromSnap(this.snap);
    const inc = openIncoming(this.snap, blob, holds);
    if (!inc) return;
    const fromEd = b64e(inc.fromEd);
    const mailboxId = inc.mailboxId;
    const prev = this.snap.peers[mailboxId];
    const peer: Peer = {
      mailboxId,
      edPk: fromEd,
      xPk: b64e(inc.fromX),
      displayName: displayOf(inc.payload) || prev?.displayName || mailboxId.slice(0, 6),
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
    if (peer.blocked) return;

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
      return;
    }

    if (inc.payload.kind === "call") {
      void this.handleCallPayload(peer, inc.payload);
      return;
    }

    if (inc.payload.kind === "typing") {
      const th = Object.values(this.snap.threads).find((t) => t.peerMailboxId === mailboxId);
      if (th && th.kind !== "chamber") {
        th.peerTypingUntil = Date.now() + 3000;
        this.bump();
      }
      return;
    }

    if (inc.payload.kind === "receipt") {
      const th = Object.values(this.snap.threads).find((t) => t.peerMailboxId === mailboxId);
      if (!th) return;
      for (const m of this.snap.messages[th.id] ?? []) {
        if (m.fromMe && m.status === "sent") m.status = "read";
      }
      this.bump();
      return;
    }

    if (inc.payload.kind === "react") {
      const list = Object.values(this.snap.messages).flat();
      const react = inc.payload;
      const m = list.find((x) => x.id === react.target);
      if (m) m.reacted = react.emoji;
      return;
    }

    if (inc.payload.kind === "ctrl" && inc.payload.op === "status") {
      const data = (inc.payload.data as { text?: string; until?: number }) || {};
      this.snap.statuses.unshift({
        id: inc.payload.id,
        fromMailbox: mailboxId,
        fromName: peer.displayName,
        text: data.text,
        at: Date.now(),
        until: data.until ?? Date.now() + 86_400_000,
      });
      return;
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
      return;
    }

    if (inc.payload.kind === "ctrl" && inc.payload.op === "delete") {
      const list = Object.values(this.snap.messages).flat();
      const del = inc.payload;
      const m = list.find((x) => x.id === (del.data as { id?: string } | undefined)?.id);
      if (m) {
        m.deleted = true;
        m.text = "";
      }
      return;
    }

    if (inc.payload.kind === "intro" || inc.payload.kind === "text" || inc.payload.kind === "photo" || inc.payload.kind === "voice" || inc.payload.kind === "file") {
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
        return;
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
      if ("bytesB64" in inc.payload && inc.payload.bytesB64) {
        const attId = crypto.randomUUID();
        const hold = !peer.accepted || this.snap.settings.lowData;
        void this.vault.putAttachment(attId, b64d(inc.payload.bytesB64), inc.payload.mime);
        msg.attachmentId = attId;
        msg.needsDownload = hold;
        const inboundHits = inspectDraft({
          text: msg.text,
          files: [
            {
              name: msg.name || "file",
              mime: msg.mime || "",
              bytes: b64d(inc.payload.bytesB64),
            },
          ],
          peerVerified: peer.verified,
          safetyJustChanged: false,
          unknownSender: !peer.accepted,
          inbound: true,
        });
        if (inboundHits.length && this.snap.settings.guardianMode !== "off") {
          appendMsg(this.snap, {
            id: crypto.randomUUID(),
            threadId: th.id,
            fromMe: false,
            kind: "system",
            text: "Guardian: " + inboundHits.map((h) => h.reason).join(" "),
            at: Date.now(),
          });
        }
      }
      if ("viewOnce" in p && p.viewOnce) msg.viewOnce = true;
      appendMsg(this.snap, msg);
      if (th.receipts && !msg.fromMe && this.token) {
        void sendSealed(this.snap, this.token, peer, {
          kind: "receipt",
          id: crypto.randomUUID(),
          upTo: msg.id,
        });
      }
    }
  }

  async addByCode(code: string): Promise<Peer> {
    if (!this.snap || !this.token) throw new Error("locked");
    let peer: Peer;
    if (code.startsWith("@")) {
      const h = await api.resolveHandle(this.token, code.slice(1));
      peer = {
        mailboxId: h.mailbox_id,
        edPk: h.identity_ed25519_b64,
        xPk: h.identity_x25519_b64,
        displayName: code,
        verified: false,
        blocked: false,
        accepted: false,
        region: "ZA-JHB",
      };
    } else {
      peer = peerFromCode(code);
      try {
        const p = parseBleepCode(code);
        if (p.region !== this.snap.settings.region) {
          throw new Error(`Their code is pinned to ${p.region}. Yours is ${this.snap.settings.region}.`);
        }
      } catch (e) {
        if ((e as Error).message.includes("pinned")) throw e;
        peer = peerFromCode(code);
      }
    }
    upsertPeer(this.snap, peer);
    this.bump();
    return peer;
  }

  async sendRequest(peer: Peer, hello: string): Promise<void> {
    if (!this.snap || !this.token) return;
    await sendSealed(this.snap, this.token, peer, {
      kind: "intro",
      id: crypto.randomUUID(),
      text: hello,
      displayName: this.snap.identity.displayName,
    });
    this.bump();
  }

  acceptRequest(fromMailbox: string): void {
    if (!this.snap) return;
    const peer = this.snap.peers[fromMailbox];
    if (!peer) return;
    peer.accepted = true;
    const th = ensureDmThread(this.snap, peer);
    const reqs = this.snap.requests.filter((r) => r.fromMailbox === fromMailbox);
    this.snap.requests = this.snap.requests.filter((r) => r.fromMailbox !== fromMailbox);
    for (const r of reqs) {
      appendMsg(this.snap, { ...r, threadId: th.id });
    }
    this.bump();
  }

  declineRequest(fromMailbox: string): void {
    if (!this.snap) return;
    this.snap.requests = this.snap.requests.filter((r) => r.fromMailbox !== fromMailbox);
    this.bump();
  }

  previewHits(text: string, files: { name: string; mime: string; bytes: Uint8Array }[], peer?: Peer): Hit[] {
    if (!this.snap || this.snap.settings.guardianMode === "off") return [];
    return inspectDraft({
      text,
      files,
      peerVerified: peer?.verified ?? false,
      safetyJustChanged: Boolean(peer && !peer.verified),
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
    if (peers.some((p) => p.safetyChanged)) {
      throw new Error("Safety number changed. Verify it before you send — this is a new key, not a toast.");
    }
    const hits = this.previewHits(text, files, peers[0]);
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
      };
      appendMsg(this.snap, msg);
      await dropAll({
        kind: "text",
        id,
        text,
        expireSec: thread.disappearingSec,
        viewOnce,
      }, msg);
    }
    thread.draft = "";
    this.bump();
  }

  sendTyping(thread: Thread): void {
    if (!this.snap || !this.token || !thread.typing || thread.kind === "chamber") return;
    for (const peer of this.destPeers(thread)) {
      void sendSealed(this.snap, this.token, peer, { kind: "typing", id: crypto.randomUUID() });
    }
  }

  async editMessage(thread: Thread, msg: Message, text: string): Promise<void> {
    if (!msg.fromMe) return;
    msg.text = text;
    msg.edited = true;
    for (const peer of this.destPeers(thread)) {
      if (!this.snap || !this.token) continue;
      await sendSealed(this.snap, this.token, peer, {
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
      void sendSealed(this.snap, this.token, peer, { kind: "react", id: crypto.randomUUID(), target: msg.id, emoji });
    }
    this.bump();
  }

  deleteForMe(msg: Message): void {
    msg.deleted = true;
    msg.text = "";
    this.bump();
  }

  async deleteForEveryone(thread: Thread, msg: Message): Promise<void> {
    this.deleteForMe(msg);
    if (!this.snap || !this.token) return;
    for (const peer of this.destPeers(thread)) {
      await sendSealed(this.snap, this.token, peer, {
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
    await registerOnRelay(id, spk, opks);
    const meta: ChamberMeta = {
      id: id.mailboxId,
      title: `Chamber · ${peer.displayName}`,
      mailboxId: id.mailboxId,
      peerMailboxId: peer.mailboxId,
      burnAt: Date.now() + days * 86400_000,
      createdAt: Date.now(),
      unlocked: true,
      unread: 0,
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
    await sendSealed(this.snap, this.token, peer, {
      kind: "intro",
      id: crypto.randomUUID(),
      text: `${this.snap.identity.displayName} wants a Chamber. Burn in ${days} days. Public Bleep cannot delete their screenshot.`,
      displayName: this.snap.identity.displayName,
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
    void api.unlink(ch.mailboxId, this.token);
    delete this.snap.chambers[id];
    delete this.snap.threads["ch:" + id];
    delete this.snap.messages["ch:" + id];
    this.bump();
  }

  async publishStatus(text: string): Promise<void> {
    if (!this.snap || !this.token) return;
    const item = {
      id: crypto.randomUUID(),
      fromMailbox: this.snap.identity.mailboxId,
      fromName: this.snap.identity.displayName,
      text,
      at: Date.now(),
      until: Date.now() + 86400_000,
    };
    this.snap.statuses.unshift(item);
    const peers = Object.values(this.snap.peers).filter((p) => p.accepted && !p.blocked);
    for (const peer of peers) {
      await sendSealed(
        this.snap,
        this.token,
        peer,
        { kind: "ctrl", id: item.id, op: "status", data: { text, until: item.until } },
        { ttl: 86400 },
      );
    }
    this.bump();
  }

  async createGroup(name: string, memberIds: string[]): Promise<void> {
    if (!this.snap || !this.token) return;
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
      await sendSealed(this.snap, this.token, peer, {
        kind: "intro",
        id: crypto.randomUUID(),
        text: `${this.snap.identity.displayName} invited you to “${name}”. Join is explicit — they cannot silent-add you.`,
        displayName: this.snap.identity.displayName,
        groupId: id,
        groupName: name,
      });
    }
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
    this.snap.requests = this.snap.requests.filter((r) => r.groupId !== groupId);
    this.bump();
  }

  async openHeld(msg: Message): Promise<void> {
    msg.needsDownload = false;
    this.bump();
  }

  async remoteUnlink(): Promise<void> {
    if (!this.snap || !this.token) return;
    await api.unlink(this.snap.identity.mailboxId, this.token);
    this.lockNow();
  }

  async eraseDevice(): Promise<void> {
    if (this.snap && this.token) {
      try {
        await api.unlink(this.snap.identity.mailboxId, this.token);
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
    const ch = this.snap.chambers[id];
    const th = this.snap.threads["ch:" + id];
    if (ch) ch.unlocked = false;
    if (th) {
      th.lockedStub = true;
      th.lastPreview = "Chamber active · locked";
    }
    this.bump();
  }

  unlockChamber(id: string, pinOk: boolean): void {
    if (!this.snap || !pinOk) return;
    const ch = this.snap.chambers[id];
    const th = this.snap.threads["ch:" + id];
    if (ch) ch.unlocked = true;
    if (th) th.lockedStub = false;
    this.bump();
  }

  pinThread(thread: Thread): void {
    thread.pinned = !thread.pinned;
    this.bump();
  }

  muteThread(thread: Thread): void {
    thread.muted = !thread.muted;
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

  async restoreFromExport(fileText: string, passphrase: string, pin: string): Promise<void> {
    const snap = await this.vault.importBlob(fileText, passphrase);
    this.snap = snap;
    await this.vault.create(pin, snap);
    this.locked = false;
    this.token = await login(snap.identity);
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
    await sendSealed(this.snap, this.token, peer, {
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
    await sendSealed(this.snap, this.token, peer, {
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
        await sendSealed(this.snap, this.token, peer, {
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
        await sendSealed(this.snap, this.token, peer, {
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
      void sendSealed(this.snap, this.token, peer, {
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

  private async tryDrop(peer: Peer, payload: Payload, msg: Message): Promise<void> {
    if (!this.snap || !this.token) return;
    try {
      await sendSealed(this.snap, this.token, peer, payload);
      msg.status = "sent";
    } catch {
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
            await sendSealed(this.snap, this.token, peer, {
              kind: "text",
              id: m.id,
              text: m.text,
            });
          } else if (m.attachmentId) {
            const att = await this.vault.getAttachment(m.attachmentId);
            if (!att) continue;
            const kind = m.kind === "photo" || m.kind === "voice" || m.kind === "file" ? m.kind : "file";
            await sendSealed(this.snap, this.token, peer, {
              kind,
              id: m.id,
              text: m.text,
              mime: att.mime,
              name: m.name || "file",
              bytesB64: b64e(att.bytes),
            });
          }
          m.status = "sent";
        } catch {
          /* stay queued */
        }
      }
    }
    this.bump();
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
