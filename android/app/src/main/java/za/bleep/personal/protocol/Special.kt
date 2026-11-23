package za.bleep.personal.protocol

import org.json.JSONArray
import org.json.JSONObject

/** Device-side extras. The relay stores none of these fields. */

data class RingNotice(val title: String, val text: String)

fun ringNotice(): RingNotice = RingNotice("Bleep", " ")

object QuietHours {
    fun active(startMin: Int, endMin: Int, nowMin: Int): Boolean {
        if (startMin < 0 || endMin < 0 || startMin > 1439 || endMin > 1439) return false
        if (startMin == endMin) return false
        val now = ((nowMin % 1440) + 1440) % 1440
        return if (startMin < endMin) now in startMin until endMin else now >= startMin || now < endMin
    }

    fun parseClock(raw: String): Int {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(raw.trim()) ?: return -1
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return -1
        return h * 60 + min
    }

    fun clock(min: Int): String {
        if (min < 0 || min > 1439) return ""
        return "%02d:%02d".format(min / 60, min % 60)
    }
}

object LaterQueue {
    const val CAP = 20

    data class Item(val id: String, val threadKey: String, val text: String, val sendAt: Long)

    fun enqueue(items: List<Item>, item: Item): List<Item>? {
        val text = item.text.trim()
        if (text.isEmpty() || item.threadKey.isBlank() || item.sendAt <= 0) return null
        if (items.size >= CAP) return null
        return items + item.copy(text = text)
    }

    fun takeDue(items: List<Item>, now: Long): Pair<List<Item>, List<Item>> {
        val due = items.filter { it.sendAt <= now }.sortedWith(compareBy({ it.sendAt }, { it.id }))
        val ids = due.map { it.id }.toSet()
        return due to items.filter { it.id !in ids }
    }

    /** Minutes from now, or a clock time today (tomorrow if that clock has passed). */
    fun parseSendAt(raw: String, now: Long): Long {
        val t = raw.trim()
        val mins = t.toIntOrNull()
        if (mins != null && mins > 0) return now + mins * 60_000L
        val clock = QuietHours.parseClock(t)
        if (clock < 0) return -1
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, clock / 60)
        cal.set(java.util.Calendar.MINUTE, clock % 60)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DATE, 1)
        return cal.timeInMillis
    }
}

object Polls {
    fun applyVote(
        votes: Map<String, Int>,
        mailbox: String,
        option: Int,
        optionCount: Int,
        members: Set<String>,
        closed: Boolean,
    ): Map<String, Int>? {
        if (closed || mailbox.isBlank() || mailbox !in members) return null
        if (option < 0 || option >= optionCount) return null
        return votes + (mailbox to option)
    }

    fun counts(votes: Map<String, Int>, optionCount: Int): IntArray {
        val out = IntArray(optionCount)
        votes.values.forEach { if (it in 0 until optionCount) out[it] += 1 }
        return out
    }
}

object Stars {
    fun keep(a: Boolean, b: Boolean, deleted: Boolean): Boolean = !deleted && (a || b)
}

fun optionsJson(options: List<String>): String? = if (options.isEmpty()) null else JSONArray(options).toString()

fun optionsOf(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        List(arr.length()) { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }
}

fun votesJson(votes: Map<String, Int>): String? {
    if (votes.isEmpty()) return null
    val o = JSONObject()
    votes.forEach { (k, v) -> o.put(k, v) }
    return o.toString()
}

fun votesOf(raw: String?): Map<String, Int> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        val o = JSONObject(raw)
        val out = linkedMapOf<String, Int>()
        o.keys().forEach { out[it] = o.getInt(it) }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}
