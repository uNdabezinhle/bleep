package za.bleep.personal.protocol

import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import za.bleep.personal.net.AccountRequests

class ProtocolTest {
    @Test
    fun saIdPassingChecksumWarns() {
        assertTrue(Guardian.validSaId("9001015800088"))
        assertTrue(Guardian.scan("here is 9001015800088 for FICA").any { it.rule == "sa-id" })
    }

    @Test
    fun saIdFailingChecksumStaysQuiet() {
        assertFalse(Guardian.validSaId("9001015800080"))
        assertFalse(Guardian.scan("9001015800080").any { it.rule == "sa-id" })
    }

    @Test
    fun handleOrBleepId() {
        assertEquals("k7m2pq9x", Crypto.shortIdFromInput("K7M2-PQ9X"))
        assertEquals("k7m2-pq9x", Crypto.formatShortId("k7m2pq9x"))
        assertEquals(null, Crypto.shortIdFromInput("oooooooo"))
        assertEquals(null, Crypto.handleFromInput("k7m2-pq9x"))
        assertEquals("lebo", Crypto.handleFromInput("@Lebo"))
        assertEquals("lebo", Crypto.handleFromInput("lebo"))
        assertEquals(null, Crypto.handleFromInput("BLEEP1:ZA-JHB:abc:ed:x"))
        assertEquals(null, Crypto.handleFromInput("ab"))
        assertEquals(null, Crypto.handleFromInput("1lebo"))
    }

    @Test
    fun chatPatternsStaySpecific() {
        val money = Guardian.scan("please send money to my ewallet", peerVerified = false)
        assertTrue(money.any { it.rule == "money" && it.reason.contains("not marked verified") })
        assertFalse(money.any { it.reason.contains("scam", ignoreCase = true) })
        assertTrue(Guardian.scan("continue this chat on whatsapp").any { it.rule == "move" })
        assertTrue(Guardian.scan("open https://fnb.example/login").any { it.rule == "link" })
        assertFalse(Guardian.scan("https://example.com/photo").any { it.rule == "link" })
        assertTrue(Guardian.scan("pay the processing fee").any { it.rule == "fee" })
        assertTrue(Guardian.scan("send me nudes").any { it.rule == "private" })
        assertTrue(Guardian.scan("please send money", peerVerified = false, several = true).any { it.reason.contains("in this chat") })
        assertTrue(Guardian.scan("see you at 5").isEmpty())
        assertTrue(Guardian.scan("my bank is closed today").isEmpty())
        assertTrue(Guardian.scan("send me the picnic photo").isEmpty())
        val frame = Guardian.scanFile("shot.jpg", "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        assertTrue(frame.any { it.rule == "frame" })
    }

    @Test
    fun luhnPan() {
        assertTrue(Guardian.validLuhn("4111111111111111"))
        assertTrue(Guardian.scan("card 4111 1111 1111 1111").any { it.rule == "pan" })
    }

    @Test
    fun x3dhAndRatchetRoundTrip() {
        val alice = Crypto.freshX()
        val bob = Crypto.freshX()
        val bobSpk = Crypto.freshX()
        val eph = Crypto.freshX()
        val skA = Ratchet.x3dhAlice(alice.sk, eph.sk, bob.pk, bobSpk.pk, null)
        val skB = Ratchet.x3dhBob(bob.sk, bobSpk.sk, alice.pk, eph.pk, null)
        assertArrayEquals(skA, skB)
        val stA = Ratchet.initAlice(skA, bobSpk.pk)
        val stB = Ratchet.initBob(skB, bobSpk.sk, bobSpk.pk)
        val (h, ct) = Ratchet.encrypt(stA, Bytes.utf8("sawubona"))
        val pt = Ratchet.decrypt(stB, h, ct)
        assertEquals("sawubona", Bytes.fromUtf8(pt))
        val (h2, ct2) = Ratchet.encrypt(stB, Bytes.utf8("yebo"))
        assertEquals("yebo", Bytes.fromUtf8(Ratchet.decrypt(stA, h2, ct2)))
    }

    @Test
    fun recoverySeedMatchesWebVector() {
        val seed = "00112233445566778899aabbccddeeff".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val id = Crypto.identityFromSeed("me", seed)
        assertEquals("7352fe893acbaa569db4c7ffcf2a97c5", id.mailboxId)
        assertEquals("d3889fc3b7051858bf3194b638f163331748f796d5b48cc2df62eb1303914add", Bytes.hex(id.edPk))
        assertEquals("afb8f9711258dc0c20eb1f7a3d03fdf7ca631e0fb6bf78162874782d19195769", Bytes.hex(id.xPk))
        assertEquals("26b7b5269426b14f8254ef1da81f82ed294a1f8d176a90c227b099cb681ff99b", Bytes.hex(Crypto.historyKey(seed)))
        assertEquals("0011-2233-4455-6677-8899-aabb-ccdd-eeff", Crypto.formatRecovery(seed))
    }

    @Test
    fun accountRequestBuilders() {
        assertEquals("""{"email":"a@b.com","password":"password1"}""", AccountRequests.signup(" A@B.com ", "password1"))
        assertEquals(
            """{"label":"android","mailbox_id":"box","nonce":"nonce","signature_b64":"sig"}""",
            AccountRequests.device("android", "box", "nonce", "sig"),
        )
        assertEquals("""{"generation":2,"nonce_b64":"n","blob_b64":"b"}""", AccountRequests.history(2, "n", "b"))
        assertEquals("/v1/directory?q=ad", AccountRequests.directoryPath("ad"))
        assertEquals("""{"token":"tok","password":"password1"}""", AccountRequests.recoverComplete(" tok ", "password1"))
        assertEquals("""{"password":"password1"}""", AccountRequests.close("password1"))
        assertEquals("""{"token":"tok"}""", AccountRequests.confirm(" tok "))
        assertEquals(
            """{"current_password":"old-pass","new_password":"new-pass"}""",
            AccountRequests.password("old-pass", "new-pass"),
        )
        val file = AccountRequests.file("nonce", "cipher", "image/jpeg", "shot.jpg")
        assertTrue(file.contains(""""name":"shot.jpg""""))
        assertFalse(file.contains("bytesB64"))
    }

    @Test
    fun sharedHistoryMergeKeepsBothSides() {
        val merged = mergeSharedMessages(
            listOf(SharedMsg("a", "local", 1, true, "file-aaaa")),
            listOf(SharedMsg("b", "remote", 2, false), SharedMsg("a", "newer", 3, true, null)),
        )
        assertEquals(2, merged.size)
        assertEquals("newer", merged.first { it.id == "a" }.text)
        assertEquals("file-aaaa", merged.first { it.id == "a" }.attachmentId)
        assertEquals("dm:aa:bb", dmThreadId("bb", "aa"))
    }

    @Test
    fun safetyNumberIsSymmetric() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (31 - it).toByte() }
        assertEquals(Crypto.safetyNumber(a, b), Crypto.safetyNumber(b, a))
    }

    @Test
    fun mlkem768MatchesNobleVector() {
        val seed = ByteArray(64) { 7 }
        val pk = Crypto.pqPublic(seed)
        assertEquals(1184, pk.size)
        assertEquals("c12e9e39db6758fc2ba63a638785b04f8efe3d7df23ba09803b4e39ba2fbc707", sha256(pk))
        val coins = ByteArray(32) { 0x11 }
        val enc = MLKEMGenerator(SecureRandom()).internalGenerateEncapsulated(
            MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, pk),
            coins,
        )
        assertEquals("c2084133f3d3988f2f5ae955578d76a90db033f2dad1b390bd94c3c6a79a3972", sha256(enc.encapsulation))
        val ss = Crypto.pqDecaps(enc.encapsulation, seed)
        assertArrayEquals(enc.secret, ss)
        assertEquals("de59a0c10debc45ca92ecf429aad94a2314b6e18635e7774dc3207fc3b3b7b36", sha256(ss))
        val x3dh = ByteArray(32) { 3 }
        assertFalse(Ratchet.hybridRoot(x3dh, ss).contentEquals(x3dh))
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
