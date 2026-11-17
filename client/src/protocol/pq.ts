import { ml_kem768 } from "@noble/post-quantum/ml-kem.js";
import { kdf } from "./keys";
import { concat } from "./bytes";

/** FIPS 203 ML-KEM-768. The 64-byte seed is d || z. */
export function pqPublic(seed64: Uint8Array): Uint8Array {
  return ml_kem768.keygen(seed64).publicKey;
}

export function pqEncaps(publicKey: Uint8Array): { cipherText: Uint8Array; sharedSecret: Uint8Array } {
  return ml_kem768.encapsulate(publicKey);
}

export function pqDecaps(cipherText: Uint8Array, seed64: Uint8Array): Uint8Array {
  const { secretKey } = ml_kem768.keygen(seed64);
  return ml_kem768.decapsulate(cipherText, secretKey);
}

/** Reviewed hybrid: HKDF(X3DH || ML-KEM). Absent KEM keeps the X3DH root. */
export function hybridRoot(x3dh: Uint8Array, kem: Uint8Array | null): Uint8Array {
  if (!kem) return x3dh;
  return kdf(concat(x3dh, kem), new Uint8Array(32), "bleep-pqxdh-v1", 32);
}
