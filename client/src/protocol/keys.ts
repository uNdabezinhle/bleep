import { ed25519, x25519 } from "@noble/curves/ed25519.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { chacha20poly1305 } from "@noble/ciphers/chacha.js";
import { randomBytes } from "@noble/hashes/utils.js";
import { b64d, b64e, concat, utf8 } from "./bytes";

export const REGION = "ZA-JHB";

export type Identity = {
  mailboxId: string;
  edSk: Uint8Array;
  edPk: Uint8Array;
  xSk: Uint8Array;
  xPk: Uint8Array;
  displayName: string;
};

export function randomMailboxId(): string {
  return hexN(randomBytes(16));
}

function hexN(bytes: Uint8Array): string {
  return [...bytes].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export function generateIdentity(displayName: string, mailboxId = randomMailboxId()): Identity {
  const edSk = ed25519.utils.randomPrivateKey();
  const edPk = ed25519.getPublicKey(edSk);
  const xSk = x25519.utils.randomPrivateKey();
  const xPk = x25519.getPublicKey(xSk);
  return { mailboxId, edSk, edPk, xSk, xPk, displayName };
}

export function freshRecoverySeed(): Uint8Array {
  return randomBytes(16);
}

export function formatRecovery(seed: Uint8Array): string {
  return hexN(seed).match(/.{4}/g)!.join("-");
}

export function parseRecovery(code: string): Uint8Array {
  const hex = code.trim().toLowerCase().replace(/[^a-f0-9]/g, "");
  if (hex.length !== 32) throw new Error("Recovery code should be 16 bytes.");
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

export function identityFromSeed(displayName: string, seed: Uint8Array): Identity {
  const edSk = kdf(seed, utf8("bleep-ed"), "ed25519", 32);
  const xSk = kdf(seed, utf8("bleep-x"), "x25519", 32);
  const mailboxId = hexN(kdf(seed, utf8("bleep-box"), "mailbox", 16));
  return {
    mailboxId,
    edSk,
    edPk: ed25519.getPublicKey(edSk),
    xSk,
    xPk: x25519.getPublicKey(xSk),
    displayName,
  };
}

export function historyKey(seed: Uint8Array): Uint8Array {
  return kdf(seed, utf8("bleep-hist"), "bleep-history-v1", 32);
}

export function chamberKey(pin: string, chamberId: string): Uint8Array {
  return kdf(utf8(pin), utf8(chamberId), "bleep-chamber-v1", 32);
}

export function sign(edSk: Uint8Array, msg: Uint8Array): Uint8Array {
  return ed25519.sign(msg, edSk);
}

export function verify(edPk: Uint8Array, msg: Uint8Array, sig: Uint8Array): boolean {
  try {
    return ed25519.verify(sig, msg, edPk);
  } catch {
    return false;
  }
}

export function dh(sk: Uint8Array, pk: Uint8Array): Uint8Array {
  return x25519.getSharedSecret(sk, pk);
}

export function kdf(ikm: Uint8Array, salt: Uint8Array, info: string, len = 32): Uint8Array {
  return hkdf(sha256, ikm, salt, utf8(info), len);
}

export function aeadEncrypt(key: Uint8Array, nonce: Uint8Array, pt: Uint8Array, aad?: Uint8Array): Uint8Array {
  const c = chacha20poly1305(key, nonce, aad);
  return c.encrypt(pt);
}

export function aeadDecrypt(key: Uint8Array, nonce: Uint8Array, ct: Uint8Array, aad?: Uint8Array): Uint8Array {
  const c = chacha20poly1305(key, nonce, aad);
  return c.decrypt(ct);
}

export function freshNonce(): Uint8Array {
  return randomBytes(12);
}

export function freshX(): { sk: Uint8Array; pk: Uint8Array } {
  const sk = x25519.utils.randomPrivateKey();
  return { sk, pk: x25519.getPublicKey(sk) };
}

export function spkMessage(keyId: number, pub: Uint8Array): Uint8Array {
  const id = new Uint8Array(4);
  new DataView(id.buffer).setUint32(0, keyId);
  return concat(utf8("BLEEP-SPK-v1|"), id, pub);
}

export function registrationMessage(mailboxId: string, xPk: Uint8Array): Uint8Array {
  return concat(utf8("BLEEP-REG-v1|"), utf8(mailboxId), utf8("|"), xPk);
}

export function authMessage(mailboxId: string, nonce: string): Uint8Array {
  return utf8(`BLEEP-AUTH-v1|${mailboxId}|${nonce}`);
}

export function bleepCode(id: Identity): string {
  return `BLEEP1:${REGION}:${id.mailboxId}:${b64e(id.edPk)}:${b64e(id.xPk)}`;
}

/** Exact published handle, or null when the text is a Bleep ID or something else. */
export function handleFromInput(raw: string): string | null {
  const s = raw.trim();
  if (s.toUpperCase().startsWith("BLEEP1")) return null;
  if (shortIdFromInput(s)) return null;
  const h = (s.startsWith("@") ? s.slice(1) : s).toLowerCase();
  return /^[a-z][a-z0-9_]{2,23}$/.test(h) ? h : null;
}

const SHORT_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

/** Eight-character Bleep ID, or null. A hyphen is optional. */
export function shortIdFromInput(raw: string): string | null {
  const s = raw.trim().toLowerCase().replace(/\s+/g, "");
  if (s.startsWith("bleep1") || s.startsWith("@")) return null;
  const compact = s.replace(/-/g, "");
  const shaped = s.length === 8 || /^[a-z0-9]{4}-[a-z0-9]{4}$/.test(s);
  if (!shaped || compact.length !== 8 || [...compact].some((c) => !SHORT_ALPHABET.includes(c))) return null;
  return compact;
}

export function formatShortId(id: string): string {
  const compact = id.replace(/-/g, "").toLowerCase();
  return `${compact.slice(0, 4)}-${compact.slice(4, 8)}`;
}

/** Published handle, without @. Null when the text is not a handle. */
export function publishedHandle(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const h = raw.trim().replace(/^@/, "").toLowerCase();
  return /^[a-z][a-z0-9_]{2,23}$/.test(h) ? h : null;
}

/** Chat title. A published handle wins. A short Bleep ID is not a name. */
export function peerLabel(handle: string | null | undefined, displayName: string): string {
  const h = publishedHandle(handle);
  if (h) return `@${h}`;
  if (!displayName || shortIdFromInput(displayName)) return "No handle";
  return displayName;
}

export type ParsedCode = {
  region: string;
  mailboxId: string;
  edPk: Uint8Array;
  xPk: Uint8Array;
};

export function parseBleepCode(raw: string): ParsedCode {
  const s = raw.trim().replace(/\s+/g, "");
  const parts = s.split(":");
  if (parts.length !== 5 || parts[0] !== "BLEEP1") {
    throw new Error("Not a Bleep code");
  }
  const [, region, mailboxId, ed, x] = parts;
  if (!/^[a-z0-9]{16,64}$/.test(mailboxId)) throw new Error("Bad mailbox");
  const edPk = b64d(ed);
  const xPk = b64d(x);
  if (edPk.length !== 32 || xPk.length !== 32) throw new Error("Bad key");
  return { region, mailboxId, edPk, xPk };
}
