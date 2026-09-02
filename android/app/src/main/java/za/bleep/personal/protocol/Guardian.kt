package za.bleep.personal.protocol

data class Hit(val rule: String, val reason: String)

object Guardian {
    fun scan(text: String): List<Hit> {
        val hits = mutableListOf<Hit>()
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

    fun scanFile(name: String, mime: String, bytes: ByteArray): List<Hit> {
        val hits = mutableListOf<Hit>()
        val n = name.lowercase()
        if (n.endsWith(".apk") || n.endsWith(".html") || n.endsWith(".vcf") || "export" in n || "whatsapp chat" in n) {
            hits += Hit("filename", "“$name” looks like an app, page, or export.")
        }
        if (mime.startsWith("image/") && bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            hits += Hit("mime", "Declared as $mime, but the bytes look like a zip/APK.")
        }
        return hits
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
