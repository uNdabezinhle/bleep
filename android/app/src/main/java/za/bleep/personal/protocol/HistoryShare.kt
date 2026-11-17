package za.bleep.personal.protocol

data class SharedMsg(
    val id: String,
    val text: String,
    val at: Long,
    val fromMe: Boolean,
    val attachmentId: String? = null,
    val kind: String = "text",
    val viewOnce: Boolean = false,
    val replyTo: String? = null,
    val replyText: String? = null,
    val mime: String? = null,
    val name: String? = null,
    val deleted: Boolean = false,
    val expiresAt: Long? = null,
    val reacted: String? = null,
    val fromMailbox: String? = null,
)

fun mergeSharedMessages(local: List<SharedMsg>, remote: List<SharedMsg>): List<SharedMsg> {
    val by = LinkedHashMap<String, SharedMsg>()
    local.forEach { by[it.id] = it }
    remote.forEach { incoming ->
        val prev = by[incoming.id]
        if (prev == null) by[incoming.id] = incoming
        else by[incoming.id] = incoming.copy(
            attachmentId = incoming.attachmentId ?: prev.attachmentId,
            expiresAt = incoming.expiresAt ?: prev.expiresAt,
            reacted = incoming.reacted ?: prev.reacted,
            fromMailbox = incoming.fromMailbox ?: prev.fromMailbox,
            text = if (incoming.deleted) "" else incoming.text.ifBlank { prev.text },
            deleted = incoming.deleted || prev.deleted,
        )
    }
    return by.values.sortedWith(compareBy({ it.at }, { it.id }))
}

fun dmThreadId(a: String, b: String): String = "dm:" + listOf(a, b).sorted().joinToString(":")

fun peerFromDm(threadId: String, selfId: String): String? {
    if (!threadId.startsWith("dm:")) return null
    val parts = threadId.removePrefix("dm:").split(":")
    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
    return when (selfId) {
        parts[0] -> parts[1]
        parts[1] -> parts[0]
        else -> null
    }
}
