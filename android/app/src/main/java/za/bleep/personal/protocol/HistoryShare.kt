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
)

fun mergeSharedMessages(local: List<SharedMsg>, remote: List<SharedMsg>): List<SharedMsg> {
    val by = LinkedHashMap<String, SharedMsg>()
    local.forEach { by[it.id] = it }
    remote.forEach { incoming ->
        val prev = by[incoming.id]
        if (prev == null) by[incoming.id] = incoming
        else by[incoming.id] = incoming.copy(attachmentId = incoming.attachmentId ?: prev.attachmentId)
    }
    return by.values.toList()
}

fun dmThreadId(a: String, b: String): String = "dm:" + listOf(a, b).sorted().joinToString(":")
