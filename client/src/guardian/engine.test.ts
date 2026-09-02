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
