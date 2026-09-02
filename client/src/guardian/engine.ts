/**
 * Guardian — on-device gate. Radio off still works. Hits are not stored.
 * TV-L1-03 / TV-L1-04.
 */

export type GuardianMode = "off" | "warn" | "strict";

export type Hit = {
  rule: string;
  reason: string;
  strip?: "exif" | "file";
  otpTwoTap?: boolean;
};

export type GuardianInput = {
  text: string;
  files: { name: string; mime: string; bytes: Uint8Array }[];
  peerVerified: boolean;
  safetyJustChanged: boolean;
  unknownSender?: boolean;
  inbound?: boolean;
};

export function inspectDraft(input: GuardianInput): Hit[] {
  const hits: Hit[] = [];
  hits.push(...scanText(input.text, input.safetyJustChanged));
  for (const f of input.files) {
    hits.push(...scanFile(f, input.inbound, input.unknownSender));
  }
  return hits;
}

export function scanText(text: string, safetyJustChanged = false): Hit[] {
  const hits: Hit[] = [];
  const digits = text.replace(/\D/g, "");
  // SA ID: warn only on a passing checksum (TV-L1-03 failing checksum stays quiet)
  for (const m of text.match(/\b\d{13}\b/g) ?? []) {
    if (validSaId(m)) {
      hits.push({
        rule: "sa-id",
        reason: "This looks like a South African ID number. Guardian did not look it up anywhere.",
      });
    }
  }
  const pans = findPans(digits.length >= 13 ? text : text);
  for (const pan of pans) {
    hits.push({
      rule: "pan",
      reason: "This looks like a card number (Luhn checksum matched). It never left the phone.",
    });
    void pan;
  }
  if (looksOtp(text)) {
    hits.push({
      rule: "otp",
      reason: safetyJustChanged
        ? "This looks like a one-time code, and their safety number just changed. Check with them before you send."
        : "This looks like a one-time code. Forwarding it can hand someone your login.",
      otpTwoTap: safetyJustChanged,
    });
  }
  return unique(hits);
}

export function validSaId(s: string): boolean {
  if (!/^\d{13}$/.test(s)) return false;
  const yy = Number(s.slice(0, 2));
  const mm = Number(s.slice(2, 4));
  const dd = Number(s.slice(4, 6));
  if (mm < 1 || mm > 12 || dd < 1 || dd > 31) return false;
  void yy;
  const odd = [0, 2, 4, 6, 8, 10].reduce((a, i) => a + Number(s[i]), 0);
  const evenConcat = Number([1, 3, 5, 7, 9, 11].map((i) => s[i]).join("")) * 2;
  const even = String(evenConcat)
    .split("")
    .reduce((a, d) => a + Number(d), 0);
  const check = (10 - ((odd + even) % 10)) % 10;
  return check === Number(s[12]);
}

export function validLuhn(s: string): boolean {
  const d = s.replace(/\D/g, "");
  if (d.length < 13 || d.length > 19) return false;
  let sum = 0;
  let alt = false;
  for (let i = d.length - 1; i >= 0; i--) {
    let n = Number(d[i]);
    if (alt) {
      n *= 2;
      if (n > 9) n -= 9;
    }
    sum += n;
    alt = !alt;
  }
  return sum % 10 === 0;
}

function findPans(text: string): string[] {
  const out: string[] = [];
  const compact = text.replace(/[^\d]/g, " ");
  for (const m of compact.match(/\d{13,19}/g) ?? []) {
    if (validLuhn(m) && !validSaId(m)) out.push(m);
  }
  // spaced PANs
  for (const m of text.match(/\b(?:\d[ -]?){13,19}\b/g) ?? []) {
    const d = m.replace(/\D/g, "");
    if (validLuhn(d) && !validSaId(d)) out.push(d);
  }
  return [...new Set(out)];
}

export function looksOtp(text: string): boolean {
  const t = text.toLowerCase();
  const code = /\b\d{4,8}\b/.test(t);
  const cue = /otp|one[ -]?time|verification code|send me the code|2fa|password|pin|bank/.test(t);
  if (/^\s*\d{4,8}\s*$/.test(text)) return true;
  return code && cue;
}

export function scanFile(
  file: { name: string; mime: string; bytes: Uint8Array },
  inbound = false,
  unknownSender = false,
): Hit[] {
  const hits: Hit[] = [];
  const name = file.name.toLowerCase();
  if (/\.(apk|xapk|html?|xhtml|vcf|ics)$/.test(name) || /whatsapp chat|chat\.txt|export/.test(name)) {
    hits.push({
      rule: "filename",
      reason: inbound
        ? `“${file.name}” looks like an app, page, or export — not a picture. Public Bleep did not sign it.`
        : `“${file.name}” looks like an app, page, or export.`,
      strip: "file",
    });
  }
  if (/id[_ -]?scan|passport|statement|fica|selfie/.test(name) || /\.pdf$/i.test(name)) {
    hits.push({
      rule: "docname",
      reason: "This looks like an identity or financial document.",
      strip: "file",
    });
  }
  const magic = sniffMime(file.bytes);
  const claimed = (file.mime || "").toLowerCase();
  if (claimed.startsWith("image/") && magic && !magic.startsWith("image/")) {
    hits.push({
      rule: "mime",
      reason: `Declared as ${claimed || "image"}, but the bytes look like ${magic}.`,
      strip: "file",
    });
  }
  if (hasExifGps(file.bytes)) {
    hits.push({
      rule: "exif-gps",
      reason: "This photo still has a location in the file. You can strip it on this device.",
      strip: "exif",
    });
  }
  if (inbound && unknownSender && (magic === "application/zip" || name.endsWith(".apk"))) {
    hits.push({
      rule: "unknown-apk",
      reason: "Unknown sender sent an app-shaped file. Download stays off.",
      strip: "file",
    });
  }
  return unique(hits);
}

export function sniffMime(bytes: Uint8Array): string | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47)
    return "image/png";
  if (bytes.length >= 4 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46) return "image/gif";
  if (bytes.length >= 4 && bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46)
    return "application/pdf";
  if (bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b) return "application/zip";
  if (bytes.length >= 4 && bytes[0] === 0x7f && bytes[1] === 0x45 && bytes[2] === 0x4c && bytes[3] === 0x46)
    return "application/octet-stream";
  return null;
}

export function hasExifGps(bytes: Uint8Array): boolean {
  if (bytes.length < 20 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return false;
  let i = 2;
  while (i + 4 < bytes.length) {
    if (bytes[i] !== 0xff) break;
    const marker = bytes[i + 1];
    if (marker === 0xda) break;
    const len = (bytes[i + 2] << 8) | bytes[i + 3];
    if (marker === 0xe1) {
      const start = i + 4;
      const head = String.fromCharCode(...bytes.slice(start, start + 4));
      if (head === "Exif") {
        return parseGps(bytes.subarray(start + 6, i + 2 + len));
      }
    }
    i += 2 + len;
  }
  return false;
}

function parseGps(tiff: Uint8Array): boolean {
  if (tiff.length < 8) return false;
  const le = tiff[0] === 0x49;
  const view = new DataView(tiff.buffer, tiff.byteOffset, tiff.byteLength);
  const read16 = (o: number) => (le ? view.getUint16(o, true) : view.getUint16(o, false));
  const read32 = (o: number) => (le ? view.getUint32(o, true) : view.getUint32(o, false));
  const ifd0 = read32(4);
  if (ifd0 + 2 > tiff.length) return false;
  const n = read16(ifd0);
  for (let k = 0; k < n; k++) {
    const off = ifd0 + 2 + k * 12;
    if (off + 12 > tiff.length) break;
    const tag = read16(off);
    if (tag === 0x8825) return true;
  }
  return false;
}

function unique(hits: Hit[]): Hit[] {
  const seen = new Set<string>();
  return hits.filter((h) => {
    const k = h.rule + h.reason;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}
