import { aeadDecrypt, aeadEncrypt, dh, freshNonce, freshX, kdf, sign, verify, type Identity } from "./keys";
import { b64d, b64e, concat, fromUtf8, utf8 } from "./bytes";
import type { Header } from "./ratchet";

export type InnerMessage = {
  v: 1;
  t:
    | "pkmsg"
    | "msg"
    | "intro"
    | "receipt"
    | "react"
    | "typing"
    | "ctrl"
    | "call";
  sid: string;
  spk: string;
  mid: string;
  ek?: string;
  used_opk?: number;
  used_spk?: number;
  h: Header;
  ct: string;
  ts: number;
  sig: string;
};

export function sealOuter(destXPk: Uint8Array, inner: Uint8Array): Uint8Array {
  const eph = freshX();
  const key = kdf(dh(eph.sk, destXPk), new Uint8Array(32), "bleep-outer-v1", 32);
  const nonce = freshNonce();
  const ct = aeadEncrypt(key, nonce, inner);
  return concat(new Uint8Array([1]), eph.pk, nonce, ct);
}

export function openOuter(destXSk: Uint8Array, blob: Uint8Array): Uint8Array {
  if (blob[0] !== 1) throw new Error("unknown envelope version");
  const ephPk = blob.slice(1, 33);
  const nonce = blob.slice(33, 45);
  const ct = blob.slice(45);
  const key = kdf(dh(destXSk, ephPk), new Uint8Array(32), "bleep-outer-v1", 32);
  return aeadDecrypt(key, nonce, ct);
}

export function signInner(id: Identity, unsigned: Omit<InnerMessage, "sig">): InnerMessage {
  const body = utf8(canonical(unsigned));
  const sig = sign(id.edSk, body);
  return { ...unsigned, sig: b64e(sig) };
}

export function verifyInner(msg: InnerMessage): boolean {
  const { sig, ...rest } = msg;
  const body = utf8(canonical(rest));
  return verify(b64d(msg.sid), body, b64d(sig));
}

function canonical(obj: unknown): string {
  return JSON.stringify(sortKeys(obj));
}

function sortKeys(obj: unknown): unknown {
  if (Array.isArray(obj)) return obj.map(sortKeys);
  if (obj && typeof obj === "object") {
    const out: Record<string, unknown> = {};
    for (const k of Object.keys(obj as object).sort()) {
      out[k] = sortKeys((obj as Record<string, unknown>)[k]);
    }
    return out;
  }
  return obj;
}

export function encodeInner(msg: InnerMessage): Uint8Array {
  return utf8(JSON.stringify(msg));
}

export function decodeInner(buf: Uint8Array): InnerMessage {
  return JSON.parse(fromUtf8(buf)) as InnerMessage;
}
