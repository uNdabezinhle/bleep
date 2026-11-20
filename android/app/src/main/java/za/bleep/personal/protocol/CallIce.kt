package za.bleep.personal.protocol

import org.json.JSONObject

/**
 * Same TURN shape as the website. The host is the box the phone is talking to.
 * The user and password match the compose defaults. TURN does not receive a name.
 * This list never uses a public STUN directory.
 */
object CallIce {
    const val USER = "bleep"
    const val PASS = "bleep-dev"

    fun hostOf(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        val afterScheme = if (trimmed.contains("://")) trimmed.substringAfter("://") else trimmed
        val hostPort = afterScheme.substringBefore("/")
        val host = hostPort.substringBefore(":")
        return host
    }

    fun urls(base: String): List<String> {
        val host = hostOf(base)
        return listOf(
            "stun:$host:3478",
            "turn:$host:3478?transport=udp",
            "turn:$host:3478?transport=tcp",
        )
    }

    fun candidateJson(sdpMid: String?, line: Int, sdp: String): String =
        JSONObject()
            .put("candidate", sdp)
            .put("sdpMid", sdpMid ?: "0")
            .put("sdpMLineIndex", line)
            .toString()

    /** sdpMid, sdpMLineIndex, candidate line. Null when the JSON is not a candidate. */
    fun parseCandidate(raw: String): Triple<String, Int, String>? {
        val obj = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        val sdp = obj.optString("candidate")
        if (sdp.isBlank()) return null
        val mid = obj.optString("sdpMid").ifBlank { "0" }
        val line = if (obj.has("sdpMLineIndex") && !obj.isNull("sdpMLineIndex")) obj.optInt("sdpMLineIndex") else 0
        return Triple(mid, line, sdp)
    }
}
