import { describe, expect, it } from "vitest";
import { hasExifGps, looksOtp, scanFile, scanText, sniffMime, validLuhn, validSaId } from "./engine";

describe("SA ID checksum", () => {
  it("warns on a passing 13-digit ID", () => {
    expect(validSaId("9001015800088")).toBe(true);
    const hits = scanText("here is 9001015800088 for FICA");
    expect(hits.some((h) => h.rule === "sa-id")).toBe(true);
  });
  it("stays quiet on a failing checksum (TV-L1-03)", () => {
    expect(validSaId("9001015800080")).toBe(false);
    const hits = scanText("9001015800080");
    expect(hits.some((h) => h.rule === "sa-id")).toBe(false);
  });
});

describe("PAN Luhn", () => {
  it("hits a valid PAN shape", () => {
    expect(validLuhn("4111111111111111")).toBe(true);
    const hits = scanText("card 4111 1111 1111 1111");
    expect(hits.some((h) => h.rule === "pan")).toBe(true);
  });
});

describe("OTP shape", () => {
  it("flags a code next to OTP wording", () => {
    expect(looksOtp("send me the code 482911")).toBe(true);
    expect(looksOtp("see you at 18")).toBe(false);
  });
});

describe("chat patterns", () => {
  it("warns on a money ask and names an unverified safety number", () => {
    const hits = scanText("please send money to my ewallet", false, false);
    expect(hits.some((h) => h.rule === "money")).toBe(true);
    expect(hits.some((h) => h.reason.includes("not marked verified"))).toBe(true);
    expect(hits.some((h) => /scam/i.test(h.reason))).toBe(false);
  });
  it("warns when the chat is asked to move off Bleep", () => {
    expect(scanText("continue this chat on whatsapp").some((h) => h.rule === "move")).toBe(true);
  });
  it("warns on a bank-shaped link and stays quiet on a bare url", () => {
    expect(scanText("open https://fnb.example/login").some((h) => h.rule === "link")).toBe(true);
    expect(scanText("https://example.com/photo").some((h) => h.rule === "link")).toBe(false);
  });
  it("warns on a fee, a prize, a private image, and a local payment rail", () => {
    expect(scanText("pay the processing fee").some((h) => h.rule === "fee")).toBe(true);
    expect(scanText("claim your prize").some((h) => h.rule === "fee" && !/scam/i.test(h.reason))).toBe(true);
    expect(scanText("send me nudes").some((h) => h.rule === "private")).toBe(true);
    expect(scanText("use snapscan").some((h) => h.rule === "money")).toBe(true);
  });
  it("names an unverified safety number once for a group", () => {
    const hits = scanText("please send money", false, false, true);
    expect(hits.some((h) => h.reason.includes("in this chat"))).toBe(true);
    expect(hits.some((h) => h.reason.includes("Their safety number"))).toBe(false);
  });
  it("stays quiet on ordinary chat", () => {
    expect(scanText("see you at 5")).toEqual([]);
    expect(scanText("my bank is closed today")).toEqual([]);
    expect(scanText("send me the picnic photo")).toEqual([]);
  });
  it("adds the unread-pixels line for an image", () => {
    const hits = scanFile({
      name: "shot.jpg",
      mime: "image/jpeg",
      bytes: new Uint8Array([0xff, 0xd8, 0xff, 0xd9]),
    });
    expect(hits.some((h) => h.rule === "frame")).toBe(true);
  });
});

describe("MIME / EXIF", () => {
  it("sniffs jpeg vs zip", () => {
    expect(sniffMime(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]))).toBe("image/jpeg");
    expect(sniffMime(new Uint8Array([0x50, 0x4b, 0x03, 0x04]))).toBe("application/zip");
  });
  it("flags apk-as-image", () => {
    const hits = scanFile({
      name: "photo.jpg",
      mime: "image/jpeg",
      bytes: new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0, 0, 0, 0]),
    });
    expect(hits.some((h) => h.rule === "mime")).toBe(true);
  });
  it("does not claim GPS on a jpeg without EXIF", () => {
    const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
    expect(hasExifGps(jpeg)).toBe(false);
  });
});
