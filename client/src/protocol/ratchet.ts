import { sha256 } from "@noble/hashes/sha2.js";
import { aeadDecrypt, aeadEncrypt, dh, freshNonce, freshX, kdf } from "./keys";
import { b64d, b64e, concat, fromUtf8, utf8 } from "./bytes";

const ZERO32 = new Uint8Array(32);

export type Header = { dh: string; n: number; pn: number };

export type RatchetState = {
  dhsSk: Uint8Array;
  dhsPk: Uint8Array;
  dhr: Uint8Array | null;
  rk: Uint8Array;
  cks: Uint8Array | null;
  ckr: Uint8Array | null;
  ns: number;
  nr: number;
  pn: number;
  skipped: Record<string, string>;
};

function kdfRk(rk: Uint8Array, dhOut: Uint8Array): { rk: Uint8Array; ck: Uint8Array } {
  const out = kdf(dhOut, rk, "bleep-rk-v1", 64);
  return { rk: out.slice(0, 32), ck: out.slice(32) };
}

function kdfCk(ck: Uint8Array): { ck: Uint8Array; mk: Uint8Array } {
  const out = kdf(ck, ZERO32, "bleep-ck-v1", 64);
  return { ck: out.slice(0, 32), mk: out.slice(32) };
}

function skipKey(dhPub: Uint8Array, n: number): string {
  return `${b64e(dhPub)}:${n}`;
}

export function initAlice(sk: Uint8Array, bobDhPk: Uint8Array): RatchetState {
  const dhs = freshX();
  const { rk, ck } = kdfRk(sk, dh(dhs.sk, bobDhPk));
  return {
    dhsSk: dhs.sk,
    dhsPk: dhs.pk,
    dhr: bobDhPk,
    rk,
    cks: ck,
    ckr: null,
    ns: 0,
    nr: 0,
    pn: 0,
    skipped: {},
  };
}

export function initBob(sk: Uint8Array, bobDhSk: Uint8Array, bobDhPk: Uint8Array): RatchetState {
  return {
    dhsSk: bobDhSk,
    dhsPk: bobDhPk,
    dhr: null,
    rk: sk,
    cks: null,
    ckr: null,
    ns: 0,
    nr: 0,
    pn: 0,
    skipped: {},
  };
}

function dhRatchet(state: RatchetState, theirPk: Uint8Array): void {
  state.pn = state.ns;
  state.ns = 0;
  state.nr = 0;
  state.dhr = theirPk;
  const recv = kdfRk(state.rk, dh(state.dhsSk, theirPk));
  state.rk = recv.rk;
  state.ckr = recv.ck;
  const dhs = freshX();
  state.dhsSk = dhs.sk;
  state.dhsPk = dhs.pk;
  const send = kdfRk(state.rk, dh(state.dhsSk, theirPk));
  state.rk = send.rk;
  state.cks = send.ck;
}

export function ratchetEncrypt(state: RatchetState, plaintext: Uint8Array): { header: Header; ciphertext: Uint8Array } {
  if (!state.cks) throw new Error("sending chain not ready");
  const next = kdfCk(state.cks);
  state.cks = next.ck;
  const header: Header = { dh: b64e(state.dhsPk), n: state.ns, pn: state.pn };
  state.ns += 1;
  const nonce = freshNonce();
  const ad = utf8(JSON.stringify(header));
  const ct = aeadEncrypt(next.mk, nonce, plaintext, ad);
  return { header, ciphertext: concat(nonce, ct) };
}

export function ratchetDecrypt(state: RatchetState, header: Header, ciphertext: Uint8Array): Uint8Array {
  const their = b64d(header.dh);
  const keyId = skipKey(their, header.n);
  if (state.skipped[keyId]) {
    const mk = b64d(state.skipped[keyId]);
    delete state.skipped[keyId];
    return open(mk, header, ciphertext);
  }
  if (!state.dhr || !eq(state.dhr, their)) {
    skipUntil(state, header.pn);
    dhRatchet(state, their);
  }
  skipUntil(state, header.n);
  if (!state.ckr) throw new Error("receiving chain not ready");
  const next = kdfCk(state.ckr);
  state.ckr = next.ck;
  state.nr += 1;
  return open(next.mk, header, ciphertext);
}

function skipUntil(state: RatchetState, until: number): void {
  if (!state.ckr || !state.dhr) return;
  if (until - state.nr > 200) throw new Error("too many skipped");
  while (state.nr < until) {
    const next = kdfCk(state.ckr);
    state.ckr = next.ck;
    state.skipped[skipKey(state.dhr, state.nr)] = b64e(next.mk);
    state.nr += 1;
  }
}

function open(mk: Uint8Array, header: Header, ciphertext: Uint8Array): Uint8Array {
  const nonce = ciphertext.slice(0, 12);
  const ct = ciphertext.slice(12);
  const ad = utf8(JSON.stringify(header));
  return aeadDecrypt(mk, nonce, ct, ad);
}

function eq(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a[i] ^ b[i];
  return d === 0;
}

export function x3dhAlice(
  aliceXSk: Uint8Array,
  ephSk: Uint8Array,
  bobIdX: Uint8Array,
  bobSpk: Uint8Array,
  bobOpk?: Uint8Array | null,
): Uint8Array {
  const dh1 = dh(aliceXSk, bobSpk);
  const dh2 = dh(ephSk, bobIdX);
  const dh3 = dh(ephSk, bobSpk);
  const parts = bobOpk ? concat(dh1, dh2, dh3, dh(ephSk, bobOpk)) : concat(dh1, dh2, dh3);
  return kdf(parts, ZERO32, "bleep-x3dh-v1", 32);
}

export function x3dhBob(
  bobXSk: Uint8Array,
  bobSpkSk: Uint8Array,
  aliceIdX: Uint8Array,
  aliceEph: Uint8Array,
  bobOpkSk?: Uint8Array | null,
): Uint8Array {
  const dh1 = dh(bobSpkSk, aliceIdX);
  const dh2 = dh(bobXSk, aliceEph);
  const dh3 = dh(bobSpkSk, aliceEph);
  const parts = bobOpkSk ? concat(dh1, dh2, dh3, dh(bobOpkSk, aliceEph)) : concat(dh1, dh2, dh3);
  return kdf(parts, ZERO32, "bleep-x3dh-v1", 32);
}

export function fingerprintSession(rk: Uint8Array): string {
  return b64e(sha256(rk)).slice(0, 12);
}

export function encodePt(obj: unknown): Uint8Array {
  return utf8(JSON.stringify(obj));
}

export function decodePt(buf: Uint8Array): unknown {
  return JSON.parse(fromUtf8(buf));
}

export type SerializedRatchet = {
  dhsSk: string;
  dhsPk: string;
  dhr: string | null;
  rk: string;
  cks: string | null;
  ckr: string | null;
  ns: number;
  nr: number;
  pn: number;
  skipped: Record<string, string>;
};

export function dumpRatchet(s: RatchetState): SerializedRatchet {
  return {
    dhsSk: b64e(s.dhsSk),
    dhsPk: b64e(s.dhsPk),
    dhr: s.dhr ? b64e(s.dhr) : null,
    rk: b64e(s.rk),
    cks: s.cks ? b64e(s.cks) : null,
    ckr: s.ckr ? b64e(s.ckr) : null,
    ns: s.ns,
    nr: s.nr,
    pn: s.pn,
    skipped: s.skipped,
  };
}

export function loadRatchet(s: SerializedRatchet): RatchetState {
  return {
    dhsSk: b64d(s.dhsSk),
    dhsPk: b64d(s.dhsPk),
    dhr: s.dhr ? b64d(s.dhr) : null,
    rk: b64d(s.rk),
    cks: s.cks ? b64d(s.cks) : null,
    ckr: s.ckr ? b64d(s.ckr) : null,
    ns: s.ns,
    nr: s.nr,
    pn: s.pn,
    skipped: s.skipped,
  };
}
