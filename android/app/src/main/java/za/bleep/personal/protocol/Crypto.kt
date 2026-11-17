package za.bleep.personal.protocol

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom
import java.util.Base64

const val REGION = "ZA-JHB"

data class Identity(
    val mailboxId: String,
    val edSk: ByteArray,
    val edPk: ByteArray,
    val xSk: ByteArray,
    val xPk: ByteArray,
    val displayName: String,
    val pqSeed: ByteArray = ByteArray(0),
)

data class XPair(val sk: ByteArray, val pk: ByteArray)

object Bytes {
    private val rng = SecureRandom()
    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
    fun fromUtf8(b: ByteArray): String = String(b, Charsets.UTF_8)
    fun concat(vararg parts: ByteArray): ByteArray {
        val n = parts.sumOf { it.size }
        val out = ByteArray(n)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }
    fun b64e(b: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun b64d(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return Base64.getDecoder().decode(t)
    }
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}

object Crypto {
    fun freshRecoverySeed(): ByteArray = Bytes.random(16)

    fun formatRecovery(seed: ByteArray): String = Bytes.hex(seed).chunked(4).joinToString("-")

    fun parseRecovery(code: String): ByteArray {
        val hex = code.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        require(hex.length == 32) { "Recovery code should be 16 bytes." }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun identityFromSeed(displayName: String, seed: ByteArray): Identity {
        val edSk = kdf(seed, Bytes.utf8("bleep-ed"), "ed25519", 32)
        val xSk = kdf(seed, Bytes.utf8("bleep-x"), "x25519", 32)
        val ed = Ed25519PrivateKeyParameters(edSk)
        val x = X25519PrivateKeyParameters(xSk)
        return Identity(
            mailboxId = Bytes.hex(kdf(seed, Bytes.utf8("bleep-box"), "mailbox", 16)),
            edSk = ed.encoded,
            edPk = ed.generatePublicKey().encoded,
            xSk = x.encoded,
            xPk = x.generatePublicKey().encoded,
            displayName = displayName,
            pqSeed = kdf(seed, Bytes.utf8("bleep-pq"), "ml-kem-768", 64),
        )
    }

    fun historyKey(seed: ByteArray): ByteArray = kdf(seed, Bytes.utf8("bleep-hist"), "bleep-history-v1", 32)

    fun pqPublic(seed64: ByteArray): ByteArray =
        MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, seed64).publicKey

    fun pqEncaps(publicKey: ByteArray): Pair<ByteArray, ByteArray> {
        val pub = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, publicKey)
        val secret = MLKEMGenerator(SecureRandom()).generateEncapsulated(pub)
        return secret.encapsulation to secret.secret
    }

    fun pqDecaps(cipherText: ByteArray, seed64: ByteArray): ByteArray {
        val priv = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, seed64)
        return MLKEMExtractor(priv).extractSecret(cipherText)
    }

    fun generateIdentity(displayName: String): Identity {
        val ed = Ed25519PrivateKeyParameters(Bytes.random(32))
        val x = X25519PrivateKeyParameters(Bytes.random(32))
        return Identity(
            mailboxId = Bytes.hex(Bytes.random(16)),
            edSk = ed.encoded,
            edPk = ed.generatePublicKey().encoded,
            xSk = x.encoded,
            xPk = x.generatePublicKey().encoded,
            displayName = displayName,
            pqSeed = Bytes.random(64),
        )
    }

    fun freshX(): XPair {
        val x = X25519PrivateKeyParameters(Bytes.random(32))
        return XPair(x.encoded, x.generatePublicKey().encoded)
    }

    fun sign(edSk: ByteArray, msg: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(edSk))
        s.update(msg, 0, msg.size)
        return s.generateSignature()
    }

    fun verify(edPk: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = try {
        val s = Ed25519Signer()
        s.init(false, Ed25519PublicKeyParameters(edPk))
        s.update(msg, 0, msg.size)
        s.verifySignature(sig)
    } catch (_: Exception) {
        false
    }

    fun dh(sk: ByteArray, pk: ByteArray): ByteArray {
        val a = X25519Agreement()
        a.init(X25519PrivateKeyParameters(sk))
        val out = ByteArray(32)
        a.calculateAgreement(X25519PublicKeyParameters(pk), out, 0)
        return out
    }

    fun kdf(ikm: ByteArray, salt: ByteArray, info: String, len: Int = 32): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, Bytes.utf8(info)))
        return ByteArray(len).also { gen.generateBytes(it, 0, len) }
    }

    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, pt: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(pt.size))
        val n = c.processBytes(pt, 0, pt.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(ct.size))
        val n = c.processBytes(ct, 0, ct.size, out, 0)
        val m = c.doFinal(out, n)
        return out.copyOf(n + m)
    }

    fun sha256(data: ByteArray): ByteArray {
        val d = SHA256Digest()
        d.update(data, 0, data.size)
        return ByteArray(32).also { d.doFinal(it, 0) }
    }

    fun spkMessage(keyId: Int, pub: ByteArray): ByteArray {
        val id = ByteArray(4)
        id[0] = (keyId ushr 24).toByte()
        id[1] = (keyId ushr 16).toByte()
        id[2] = (keyId ushr 8).toByte()
        id[3] = keyId.toByte()
        return Bytes.concat(Bytes.utf8("BLEEP-SPK-v1|"), id, pub)
    }

    fun registrationMessage(mailboxId: String, xPk: ByteArray): ByteArray =
        Bytes.concat(Bytes.utf8("BLEEP-REG-v1|"), Bytes.utf8(mailboxId), Bytes.utf8("|"), xPk)

    fun authMessage(mailboxId: String, nonce: String): ByteArray =
        Bytes.utf8("BLEEP-AUTH-v1|$mailboxId|$nonce")

    fun bleepCode(id: Identity): String =
        "BLEEP1:$REGION:${id.mailboxId}:${Bytes.b64e(id.edPk)}:${Bytes.b64e(id.xPk)}"

    private const val SHORT_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    fun shortIdFromInput(raw: String): String? {
        val s = raw.trim().lowercase().replace(" ", "")
        if (s.startsWith("bleep1") || s.startsWith("@")) return null
        val compact = s.replace("-", "")
        val shaped = s.length == 8 || Regex("^[a-z0-9]{4}-[a-z0-9]{4}$").matches(s)
        if (!shaped || compact.length != 8 || compact.any { it !in SHORT_ALPHABET }) return null
        return compact
    }

    fun formatShortId(id: String): String {
        val compact = id.replace("-", "").lowercase()
        return compact.take(4) + "-" + compact.drop(4).take(4)
    }

    fun handleFromInput(raw: String): String? {
        val s = raw.trim()
        if (s.uppercase().startsWith("BLEEP1") || shortIdFromInput(s) != null) return null
        val h = (if (s.startsWith("@")) s.drop(1) else s).lowercase()
        return if (Regex("^[a-z][a-z0-9_]{2,23}$").matches(h)) h else null
    }

    fun parseCode(raw: String): Triple<String, ByteArray, ByteArray> {
        val p = raw.trim().split(":")
        require(p.size == 5 && p[0] == "BLEEP1") { "Not a Bleep code" }
        return Triple(p[2], Bytes.b64d(p[3]), Bytes.b64d(p[4]))
    }

    fun safetyNumber(edA: ByteArray, edB: ByteArray): String {
        val cmp = edA.zip(edB).map { (a, b) -> (a.toInt() and 0xff) - (b.toInt() and 0xff) }.firstOrNull { it != 0 } ?: 0
        val x = if (cmp <= 0) edA else edB
        val y = if (cmp <= 0) edB else edA
        val h = sha256(Bytes.concat(x, y))
        val digits = Bytes.hex(h).map { c ->
            if (c in 'a'..'f') ((c.code - 87) % 10).digitToChar() else c
        }.joinToString("").take(60)
        return digits.chunked(5).joinToString(" ")
    }
}

object Seal {
    fun outer(destXPk: ByteArray, inner: ByteArray): ByteArray {
        val eph = Crypto.freshX()
        val key = Crypto.kdf(Crypto.dh(eph.sk, destXPk), ByteArray(32), "bleep-outer-v1", 32)
        val nonce = Bytes.random(12)
        val ct = Crypto.aeadEncrypt(key, nonce, inner)
        return Bytes.concat(byteArrayOf(1), eph.pk, nonce, ct)
    }

    fun openOuter(destXSk: ByteArray, blob: ByteArray): ByteArray {
        require(blob[0] == 1.toByte()) { "unknown envelope" }
        val ephPk = blob.copyOfRange(1, 33)
        val nonce = blob.copyOfRange(33, 45)
        val ct = blob.copyOfRange(45, blob.size)
        val key = Crypto.kdf(Crypto.dh(destXSk, ephPk), ByteArray(32), "bleep-outer-v1", 32)
        return Crypto.aeadDecrypt(key, nonce, ct)
    }
}
