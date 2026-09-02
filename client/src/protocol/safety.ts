import { sha256 } from "@noble/hashes/sha2.js";
import { hex } from "./bytes";

export function safetyNumber(edA: Uint8Array, edB: Uint8Array): string {
  const [x, y] = [edA, edB].sort((a, b) => {
    for (let i = 0; i < 32; i++) {
      if (a[i] !== b[i]) return a[i] - b[i];
    }
    return 0;
  });
  const h = sha256(new Uint8Array([...x, ...y]));
  const digits = hex(h)
    .replace(/[a-f]/g, (c) => String((c.charCodeAt(0) - 87) % 10))
    .slice(0, 60);
  const groups = digits.match(/.{1,5}/g) ?? [];
  return groups.join(" ");
}

export function safetyShort(n: string): string {
  return n.split(" ").slice(0, 3).join(" ");
}
