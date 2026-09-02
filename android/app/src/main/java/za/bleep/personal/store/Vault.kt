package za.bleep.personal.store

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class Vault(ctx: Context) {
    private val file = File(ctx.filesDir, "vault.enc")
    fun exists(): Boolean = file.exists()

    fun save(pin: String, json: JSONObject) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = derive(pin, salt)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = c.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val wrap = JSONObject()
            .put("v", 1)
            .put("salt", b64(salt))
            .put("iv", b64(iv))
            .put("ct", b64(ct))
        file.writeText(wrap.toString())
    }

    fun load(pin: String): JSONObject {
        val wrap = JSONObject(file.readText())
        val key = derive(pin, b64d(wrap.getString("salt")))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b64d(wrap.getString("iv"))))
        val pt = c.doFinal(b64d(wrap.getString("ct")))
        return JSONObject(String(pt, Charsets.UTF_8))
    }

    fun erase() {
        file.delete()
    }

    /** Forced-passphrase export (T18). Same shape as the invite client. */
    fun exportBytes(passphrase: String, json: JSONObject): ByteArray {
        require(passphrase.length >= 8) { "passphrase must be 8+ characters" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = derive(passphrase, salt)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = c.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val wrap = JSONObject()
            .put("v", 1)
            .put("kind", "bleep-export")
            .put("kdf", "pbkdf2-sha256")
            .put("iters", 210_000)
            .put("salt", b64(salt))
            .put("iv", b64(iv))
            .put("ct", b64(ct))
        return wrap.toString(2).toByteArray(Charsets.UTF_8)
    }

    fun importBytes(passphrase: String, fileText: String): JSONObject {
        require(passphrase.length >= 8) { "passphrase must be 8+ characters" }
        val wrap = JSONObject(fileText)
        require(wrap.optString("kind") == "bleep-export") { "not a Bleep export" }
        val key = derive(passphrase, b64d(wrap.getString("salt")))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b64d(wrap.getString("iv"))))
        val pt = c.doFinal(b64d(wrap.getString("ct")))
        return JSONObject(String(pt, Charsets.UTF_8))
    }

    private fun derive(pin: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 210_000, 256)
        val sk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return SecretKeySpec(sk.encoded, "AES")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String) = Base64.decode(s, Base64.DEFAULT)
}
