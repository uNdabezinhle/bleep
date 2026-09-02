import { openDB, type IDBPDatabase } from "idb";
import { asAB, b64d, b64e, fromUtf8, utf8 } from "../protocol/bytes";
import type { Identity } from "../protocol/keys";
import type { VaultSnapshot } from "../types";
import { REGION } from "../protocol/keys";

const PBKDF2_ITERS = 210_000;

export function profileName(): string {
  const q = new URLSearchParams(location.search).get("p");
  return (q || "default").replace(/[^a-z0-9_-]/gi, "").slice(0, 24) || "default";
}

function dbName(profile: string) {
  return `bleep-${profile}`;
}

type Wrap = { salt: string; iv: string; ct: string; iters: number; v: 1 };

async function derive(pin: string, salt: Uint8Array): Promise<CryptoKey> {
  const mat = await crypto.subtle.importKey("raw", asAB(utf8(pin)), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: asAB(salt), iterations: PBKDF2_ITERS, hash: "SHA-256" },
    mat,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

async function encryptJson(key: CryptoKey, obj: unknown): Promise<{ iv: string; ct: string }> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv: asAB(iv) }, key, asAB(utf8(JSON.stringify(obj))));
  return { iv: b64e(iv), ct: b64e(new Uint8Array(ct)) };
}

async function decryptJson<T>(key: CryptoKey, iv: string, ct: string): Promise<T> {
  const pt = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: asAB(b64d(iv)) },
    key,
    asAB(b64d(ct)),
  );
  return JSON.parse(fromUtf8(new Uint8Array(pt))) as T;
}

export type StoredIdentity = {
  mailboxId: string;
  edSk: string;
  edPk: string;
  xSk: string;
  xPk: string;
  displayName: string;
};

export function dumpIdentity(id: Identity): StoredIdentity {
  return {
    mailboxId: id.mailboxId,
    edSk: b64e(id.edSk),
    edPk: b64e(id.edPk),
    xSk: b64e(id.xSk),
    xPk: b64e(id.xPk),
    displayName: id.displayName,
  };
}

export function loadIdentity(s: StoredIdentity): Identity {
  return {
    mailboxId: s.mailboxId,
    edSk: b64d(s.edSk),
    edPk: b64d(s.edPk),
    xSk: b64d(s.xSk),
    xPk: b64d(s.xPk),
    displayName: s.displayName,
  };
}

export class Vault {
  profile: string;
  private db!: IDBPDatabase;
  private key: CryptoKey | null = null;

  constructor(profile = profileName()) {
    this.profile = profile;
  }

  async open(): Promise<void> {
    this.db = await openDB(dbName(this.profile), 1, {
      upgrade(db) {
        db.createObjectStore("meta");
        db.createObjectStore("blob");
        db.createObjectStore("attachments");
      },
    });
  }

  async hasVault(): Promise<boolean> {
    return (await this.db.get("meta", "wrap")) != null;
  }

  async create(pin: string, snapshot: VaultSnapshot): Promise<void> {
    const salt = crypto.getRandomValues(new Uint8Array(16));
    this.key = await derive(pin, salt);
    const { iv, ct } = await encryptJson(this.key, serialize(snapshot));
    const wrap: Wrap = { salt: b64e(salt), iv, ct, iters: PBKDF2_ITERS, v: 1 };
    await this.db.put("meta", wrap, "wrap");
  }

  async unlock(pin: string): Promise<VaultSnapshot> {
    const wrap = (await this.db.get("meta", "wrap")) as Wrap | undefined;
    if (!wrap) throw new Error("no vault");
    const key = await derive(pin, b64d(wrap.salt));
    const data = await decryptJson<SerializedVault>(key, wrap.iv, wrap.ct);
    this.key = key;
    return deserialize(data);
  }

  lock(): void {
    this.key = null;
  }

  get unlocked(): boolean {
    return this.key != null;
  }

  async save(snapshot: VaultSnapshot): Promise<void> {
    if (!this.key) throw new Error("locked");
    const wrap = (await this.db.get("meta", "wrap")) as Wrap;
    const { iv, ct } = await encryptJson(this.key, serialize(snapshot));
    await this.db.put("meta", { ...wrap, iv, ct }, "wrap");
  }

  async putAttachment(id: string, bytes: Uint8Array, mime: string): Promise<void> {
    if (!this.key) throw new Error("locked");
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv: asAB(iv) }, this.key, asAB(bytes));
    await this.db.put("attachments", { iv: b64e(iv), ct: b64e(new Uint8Array(ct)), mime }, id);
  }

  async getAttachment(id: string): Promise<{ bytes: Uint8Array; mime: string } | null> {
    if (!this.key) throw new Error("locked");
    const row = await this.db.get("attachments", id);
    if (!row) return null;
    const pt = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: asAB(b64d(row.iv)) },
      this.key,
      asAB(b64d(row.ct)),
    );
    return { bytes: new Uint8Array(pt), mime: row.mime };
  }

  async exportBlob(passphrase: string, snapshot: VaultSnapshot): Promise<Blob> {
    if (passphrase.length < 8) throw new Error("passphrase must be 8+ characters");
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const key = await derive(passphrase, salt);
    const { iv, ct } = await encryptJson(key, serialize(snapshot));
    const file = {
      v: 1,
      kind: "bleep-export",
      kdf: "pbkdf2-sha256",
      iters: PBKDF2_ITERS,
      salt: b64e(salt),
      iv,
      ct,
      chambers: "omitted unless unlocked and selected",
    };
    return new Blob([JSON.stringify(file, null, 2)], { type: "application/json" });
  }

  async importBlob(fileText: string, passphrase: string): Promise<VaultSnapshot> {
    if (passphrase.length < 8) throw new Error("passphrase must be 8+ characters");
    const file = JSON.parse(fileText) as {
      kind?: string;
      salt: string;
      iv: string;
      ct: string;
    };
    if (file.kind !== "bleep-export") throw new Error("not a Bleep export");
    const key = await derive(passphrase, b64d(file.salt));
    return deserialize(await decryptJson<SerializedVault>(key, file.iv, file.ct));
  }

  async erase(): Promise<void> {
    this.key = null;
    this.db.close();
    await indexedDB.deleteDatabase(dbName(this.profile));
    await this.open();
  }
}

type SerializedVault = Omit<VaultSnapshot, "identity"> & { identity: StoredIdentity };

function serialize(s: VaultSnapshot): SerializedVault {
  return { ...s, identity: dumpIdentity(s.identity) };
}

function deserialize(s: SerializedVault): VaultSnapshot {
  return { ...s, identity: loadIdentity(s.identity) };
}

export function emptySnapshot(identity: Identity): VaultSnapshot {
  return {
    identity,
    peers: {},
    threads: {},
    messages: {},
    sessions: {},
    settings: {
      autoLockSec: 60,
      guardianMode: "warn",
      lowData: true,
      lockScreenPreview: false,
      diagnostics: false,
      region: REGION,
    },
    requests: [],
    blocked: [],
    chambers: {},
    statuses: [],
    groups: {},
    prekeys: { spk: { id: 1, sk: "", pk: "" }, opks: [] },
  };
}
