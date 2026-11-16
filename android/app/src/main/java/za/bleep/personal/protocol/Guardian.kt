package za.bleep.personal.protocol

data class Hit(val rule: String, val reason: String)

object Guardian {
    fun scan(text: String, peerVerified: Boolean = true, several: Boolean = false): List<Hit> {
        val hits = mutableListOf<Hit>()
        val ask = if (peerVerified) "" else if (several) " A safety number in this chat is not marked verified." else " Their safety number is not marked verified."
        val lower = text.lowercase()
        if (Regex("e-?wallet|gift ?card|bitcoin|\\bbtc\\b|wallet address|mukuru|ozow|proof of payment|send (me )?money|wire transfer|airtime|payshap|snapscan|cash send|instant eft").containsMatchIn(lower)) {
            hits += Hit("money", "This asks for money or a transfer.$ask")
        }
        if (Regex("processing fee|verification fee|customs fee|recruitment fee|claim your prize|guaranteed return|double your money").containsMatchIn(lower)) {
            hits += Hit("fee", "This asks for a fee, a prize, or a return.$ask")
        }
        if (Regex("send (me )?(a |some )?(nudes|nude pics|naked photos?)|verification video").containsMatchIn(lower)) {
            hits += Hit("private", "This asks for a private image or video.$ask")
        }
        if (Regex("recovery code|seed phrase|your password|account password").containsMatchIn(lower)) {
            hits += Hit("secret", "This asks for a password or the recovery code.$ask")
        }
        if (Regex("anydesk|teamviewer|rustdesk|remote access|\\.apk\\b").containsMatchIn(lower)) {
            hits += Hit("remote", "This asks for a remote-control app or an APK.$ask")
        }
        if (Regex("whatsapp|telegram|signal me|continue (this|the chat) on|email me at").containsMatchIn(lower)) {
            hits += Hit("move", "This asks to continue off Bleep.$ask")
        }
        if (Regex("https?://\\S+|www\\.\\S+").containsMatchIn(lower) &&
            (Regex("login|verify your account|password reset|ozow|\\bfnb\\b|capitec|absa|standard\\s*bank|paypal").containsMatchIn(lower) ||
                Regex("https?://\\d{1,3}(?:\\.\\d{1,3}){3}").containsMatchIn(lower))
        ) {
            hits += Hit("link", "This looks like a login or bank link. Guardian did not open it.$ask")
        }
        Regex("\\b\\d{13}\\b").findAll(text).forEach {
            if (validSaId(it.value)) {
                hits += Hit("sa-id", "This looks like a South African ID number. Guardian did not look it up anywhere.")
            }
        }
        Regex("(?:\\d[ -]?){13,19}").findAll(text).forEach {
            val d = it.value.replace(Regex("\\D"), "")
            if (validLuhn(d) && !validSaId(d)) {
                hits += Hit("pan", "This looks like a card number (Luhn checksum matched). It never left the phone.")
            }
        }
        if (looksOtp(text)) {
            hits += Hit("otp", "This looks like a one-time code. Forwarding it can hand someone your login.")
        }
        return hits.distinctBy { it.rule + it.reason }
    }

    fun scanFile(name: String, mime: String, bytes: ByteArray, standing: Boolean = true): List<Hit> {
        val hits = mutableListOf<Hit>()
        val n = name.lowercase()
        val claimed = mime.lowercase()
        val magic = sniff(bytes)
        val image = claimed.startsWith("image/") || (magic?.startsWith("image/") == true)
        val video = claimed.startsWith("video/") || n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".webm")
        if (standing && (image || video)) {
            hits += Hit("frame", "This check does not read the picture or the frames. A frame can show an ID, a banking screen, or a face.")
        }
        if (n.endsWith(".apk") || n.endsWith(".html") || n.endsWith(".vcf") || "export" in n || "whatsapp chat" in n) {
            hits += Hit("filename", "“$name” looks like an app, page, or export.")
        }
        if (Regex("id[_ -]?scan|passport|statement|fica|selfie").containsMatchIn(n) || n.endsWith(".pdf")) {
            hits += Hit("docname", "This looks like an identity or financial document.")
        }
        if (claimed.startsWith("image/") && magic != null && !magic.startsWith("image/")) {
            hits += Hit("mime", "Declared as $mime, but the bytes look like $magic.")
        }
        if (hasExifGps(bytes)) {
            hits += Hit("exif-gps", "This photo still has a location in the file. You can strip it on this device.")
        }
        return hits.distinctBy { it.rule + it.reason }
    }

    fun stripJpegExif(bytes: ByteArray): ByteArray {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return bytes
        val out = java.io.ByteArrayOutputStream()
        out.write(bytes, 0, 2)
        var i = 2
        while (i + 4 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) break
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xDA) {
                out.write(bytes, i, bytes.size - i)
                return out.toByteArray()
            }
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker != 0xE1) out.write(bytes, i, 2 + len)
            i += 2 + len
            if (len < 2) break
        }
        return bytes
    }

    private fun sniff(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "image/jpeg"
        if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte()) return "image/png"
        if (bytes.size >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte()) return "application/pdf"
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return "application/zip"
        return null
    }

    fun hasExifGps(bytes: ByteArray): Boolean {
        if (bytes.size < 20 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return false
        var i = 2
        while (i + 4 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) break
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker == 0xE1 && i + 10 < bytes.size) {
                val head = String(bytes, i + 4, 4, Charsets.US_ASCII)
                if (head == "Exif") return exifHasGps(bytes.copyOfRange(i + 10, minOf(bytes.size, i + 2 + len)))
            }
            if (len < 2) break
            i += 2 + len
        }
        return false
    }

    private fun exifHasGps(tiff: ByteArray): Boolean {
        if (tiff.size < 8) return false
        val le = tiff[0] == 0x49.toByte()
        fun u16(o: Int): Int {
            if (o + 1 >= tiff.size) return 0
            val a = tiff[o].toInt() and 0xFF
            val b = tiff[o + 1].toInt() and 0xFF
            return if (le) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(o: Int): Int {
            if (o + 3 >= tiff.size) return 0
            val b0 = tiff[o].toInt() and 0xFF
            val b1 = tiff[o + 1].toInt() and 0xFF
            val b2 = tiff[o + 2].toInt() and 0xFF
            val b3 = tiff[o + 3].toInt() and 0xFF
            return if (le) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24) else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
        val ifd0 = u32(4)
        if (ifd0 + 2 > tiff.size) return false
        val n = u16(ifd0)
        for (k in 0 until n) {
            val off = ifd0 + 2 + k * 12
            if (off + 2 > tiff.size) break
            if (u16(off) == 0x8825) return true
        }
        return false
    }

    fun validSaId(s: String): Boolean {
        if (!s.matches(Regex("^\\d{13}$"))) return false
        val mm = s.substring(2, 4).toInt()
        val dd = s.substring(4, 6).toInt()
        if (mm !in 1..12 || dd !in 1..31) return false
        val odd = listOf(0, 2, 4, 6, 8, 10).sumOf { s[it].digitToInt() }
        val evenConcat = listOf(1, 3, 5, 7, 9, 11).joinToString("") { s[it].toString() }.toInt() * 2
        val even = evenConcat.toString().sumOf { it.digitToInt() }
        val check = (10 - ((odd + even) % 10)) % 10
        return check == s[12].digitToInt()
    }

    fun validLuhn(s: String): Boolean {
        val d = s.replace(Regex("\\D"), "")
        if (d.length !in 13..19) return false
        var sum = 0
        var alt = false
        for (i in d.length - 1 downTo 0) {
            var n = d[i].digitToInt()
            if (alt) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }

    fun looksOtp(text: String): Boolean {
        val t = text.lowercase()
        if (t.trim().matches(Regex("\\d{4,8}"))) return true
        val code = Regex("\\b\\d{4,8}\\b").containsMatchIn(t)
        val cue = Regex("otp|one[ -]?time|verification code|send me the code|2fa|password|pin|bank").containsMatchIn(t)
        return code && cue
    }
}
