package za.bleep.personal.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun safetyNumberIsSymmetric() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (31 - it).toByte() }
        assertEquals(Crypto.safetyNumber(a, b), Crypto.safetyNumber(b, a))
    }
}
