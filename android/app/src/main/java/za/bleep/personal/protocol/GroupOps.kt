package za.bleep.personal.protocol

object GroupOps {
    /** Null means the leaver was not a member. The list and version stay as they were. */
    fun applyLeave(members: List<GroupMember>, version: Int, leaver: String): Pair<List<GroupMember>, Int>? {
        if (members.none { it.mailboxId == leaver }) return null
        return members.filter { it.mailboxId != leaver } to (version + 1)
    }
}
