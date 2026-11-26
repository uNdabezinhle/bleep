package za.bleep.personal.protocol

import org.json.JSONObject
import za.bleep.personal.net.AccountRequests
import za.bleep.personal.net.Relay

class Erased : Exception("This device was erased.")

data class Peer(
    val mailboxId: String,
    val edPk: ByteArray,
    val xPk: ByteArray,
    var displayName: String,
    var accepted: Boolean = false,
    var verified: Boolean = false,
    var disappearSec: Int? = null,
    var safetyChanged: Boolean = false,
    var blocked: Boolean = false,
    var nickname: String? = null,
    var mutedUntil: Long? = null,
    var avatarId: String? = null,
    var avatarB64: String? = null,
)

data class GroupMember(val mailboxId: String, val edPk: String, val role: String)

data class GroupState(val id: String, val name: String, val members: List<GroupMember>, val version: Int = 1)

data class ChamberMeta(
    val id: String,
    var title: String,
    val mailboxId: String,
    var peerMailboxId: String?,
    var burnAt: Long,
    val createdAt: Long,
    var unlocked: Boolean,
    var relayToken: String? = null,
)

data class StatusItem(
    val id: String,
    val fromMailbox: String,
    val fromName: String,
    val text: String?,
    val at: Long,
    val until: Long,
    val mime: String? = null,
    val name: String? = null,
    val attachmentId: String? = null,
    val bytesB64: String? = null,
)

data class ChatMsg(
    val id: String,
    val fromMe: Boolean,
    val text: String,
    val at: Long,
    val peerId: String = "",
    val kind: String = "text",
    val mime: String? = null,
    val bytesB64: String? = null,
    val reacted: String? = null,
    val expiresAt: Long? = null,
    val name: String? = null,
    val fileId: String? = null,
    val replyTo: String? = null,
    val replyText: String? = null,
    val viewOnce: Boolean = false,
    val viewed: Boolean = false,
    val viewUntil: Long? = null,
    val deleted: Boolean = false,
    val groupId: String? = null,
    val groupName: String? = null,
    val chamberInvite: Boolean = false,
    val burnAt: Long? = null,
    val starred: Boolean = false,
    val pollQuestion: String? = null,
    val pollOptionsJson: String? = null,
    val pollClosed: Boolean = false,
    val pollVotesJson: String? = null,
)

class Session(private val relay: Relay) {
    lateinit var id: Identity
    lateinit var token: String
    val ready: Boolean get() = this::token.isInitialized

    fun relayBase(url: String) {
        relay.base = url.trim().trimEnd('/')
    }

    fun boxUrl(): String = relay.base

    fun turnLogin(): Pair<String, String> {
        if (!ready) return CallIce.USER to CallIce.PASS
        return try {
            val row = relay.turn(token)
            val user = row.optString("username").ifBlank { CallIce.USER }
            val pass = row.optString("credential").ifBlank { CallIce.PASS }
            user to pass
        } catch (_: Exception) {
            CallIce.USER to CallIce.PASS
        }
    }

    /** Set by the open screen. Invoked after poll releases its lock, so it may send. */
    var onCall: ((Peer, JSONObject) -> Unit)? = null
    private var callBatch: MutableList<Pair<Peer, JSONObject>>? = null

    fun requestReset(email: String): JSONObject = relay.recover(email)

    fun completeReset(token: String, password: String): JSONObject = relay.recoverComplete(token, password)
    lateinit var spk: XPair
    val opks = mutableListOf<Pair<Int, XPair>>()
    val peers = mutableMapOf<String, Peer>()
    val ratchets = mutableMapOf<String, RatchetState>()
    val ephPk = mutableMapOf<String, ByteArray>()
    val pendingPq = mutableMapOf<String, ByteArray>()
    val presence = mutableMapOf<String, Pair<Boolean, String?>>()
    val pendingPk = mutableSetOf<String>()
    val pendingOpk = mutableMapOf<String, Int>()
    val threads = mutableMapOf<String, MutableList<ChatMsg>>()
    val groupThreads = mutableMapOf<String, MutableList<ChatMsg>>()
    val groups = mutableMapOf<String, GroupState>()
    val chambers = mutableMapOf<String, ChamberMeta>()
    val chamberThreads = mutableMapOf<String, MutableList<ChatMsg>>()
    val chamberPins = mutableMapOf<String, String>()
    private val extraGen = mutableMapOf<String, Int>()
    val statuses = mutableListOf<StatusItem>()
    var avatarId: String? = null
    var avatarB64: String? = null
    val requests = mutableListOf<ChatMsg>()
    val outbox = mutableListOf<JSONObject>()
    var lastIngest = 0
    var recoverySeed: String? = null
    var deviceId: String? = null
    var deviceCredential: String? = null
    var accountEmail: String? = null
    var historyGeneration: Int = 0
    val blocked = mutableSetOf<String>()
    var webPocket: JSONObject? = null
    var webPocketKnown: Boolean = false
    var sharedSessions: JSONObject? = null
    private var sessionDirty = false
    var lastDetail: String = ""
    var captureNotice: Boolean = false
    var regionOk: Boolean = true
    var guardianMode: String = "warn"
    var autoLockSec: Int = 60
    var listenBackground: Boolean = false
    var quietStartMin: Int = -1
    var quietEndMin: Int = -1
    /** Mail alert on this device. The call ring does not use this flag. */
    var alertSound: Boolean = true
    var mailAlert: Int = 0
    val later = mutableListOf<LaterQueue.Item>()
    var ringOffer: Boolean = false
    var uiResumed: Boolean = false
    private val ioLock = Any()
    var shortId: String? = null
    var handle: String? = null
    private var liftedIds = false
    private var lastHistoryJson: String? = null
    private val openedIds = mutableSetOf<String>()

    fun create(displayName: String) {
        id = Crypto.generateIdentity(displayName)
        freshPrekeys()
        registerMailbox()
    }

    fun signUp(email: String, password: String, displayName: String, seed: ByteArray): String {
        id = Crypto.identityFromSeed(displayName.ifBlank { "me" }, seed)
        recoverySeed = Bytes.b64e(seed)
        accountEmail = email.trim().lowercase()
        historyGeneration = 0
        freshPrekeys()
        val account = relay.createLogin(JSONObject(AccountRequests.signup(email, password)))
        lastDetail = account.optString("detail")
        if (!account.optBoolean("email_confirmed", true)) return lastDetail
        registerMailbox()
        enrollDevice(account.getString("account_token"))
        return lastDetail
    }

    fun signIn(email: String, password: String, recovery: String) {
        val account = relay.loginAccount(JSONObject(AccountRequests.login(email, password)))
        check(account.optBoolean("email_confirmed")) { "Confirm your email before opening the vault." }
        val fresh = account.optBoolean("setup_pending")
        if (!fresh && account.isNull("mailbox_id")) error("This account has no active mailbox.")
        val mailbox = account.optString("mailbox_id", "")
        if (!fresh && recovery.isBlank()) error("Enter your recovery code to open this account on a new device.")
        val seed = if (fresh && recovery.isBlank()) Crypto.freshRecoverySeed() else Crypto.parseRecovery(recovery)
        id = Crypto.identityFromSeed("me", seed)
        if (!fresh && id.mailboxId != mailbox) error("Recovery code does not match this account.")
        recoverySeed = Bytes.b64e(seed)
        accountEmail = email.trim().lowercase()
        freshPrekeys()
        if (fresh) registerMailbox()
        enrollDevice(account.getString("account_token"))
        if (!fresh) pullHistory()
    }

    private fun freshPrekeys() {
        spk = Crypto.freshX()
        opks.clear()
        repeat(8) { i -> opks += (i + 1) to Crypto.freshX() }
    }

    private fun registerMailbox() {
        val body = JSONObject()
            .put("mailbox_id", id.mailboxId)
            .put("identity_ed25519_b64", Bytes.b64e(id.edPk))
            .put("identity_x25519_b64", Bytes.b64e(id.xPk))
            .put(
                "signed_prekey",
                JSONObject()
                    .put("key_id", 1)
                    .put("pub_b64", Bytes.b64e(spk.pk))
                    .put("sig_b64", Bytes.b64e(Crypto.sign(id.edSk, Crypto.spkMessage(1, spk.pk)))),
            )
            .put(
                "one_time_prekeys",
                org.json.JSONArray().also { arr ->
                    opks.forEach { (kid, k) ->
                        arr.put(JSONObject().put("key_id", kid).put("pub_b64", Bytes.b64e(k.pk)))
                    }
                },
            )
            .put("registration_sig_b64", Bytes.b64e(Crypto.sign(id.edSk, Crypto.registrationMessage(id.mailboxId, id.xPk))))
        attachMlkem(body, id)
        val res = relay.register(body)
        token = res.getString("token")
        shortId = res.optString("short_id").ifBlank { null }
        handle = res.optString("handle").ifBlank { handle }
    }

    private fun enrollDevice(accountToken: String) {
        val ch = relay.challenge()
        val nonce = ch.getString("nonce")
        val sig = Crypto.sign(id.edSk, Crypto.authMessage(id.mailboxId, nonce))
        val dev = relay.addDevice(
            accountToken,
            JSONObject(AccountRequests.device("android", id.mailboxId, nonce, Bytes.b64e(sig))),
        )
        deviceId = dev.getString("device_id")
        deviceCredential = dev.getString("device_credential")
        token = dev.getString("token")
        shortId = dev.optString("short_id").ifBlank { shortId }
        handle = dev.optString("handle").ifBlank { handle }
    }

    fun snapshot(): JSONObject {
        val peersJ = JSONObject()
        peers.forEach { (k, p) ->
            peersJ.put(
                k,
                JSONObject()
                    .put("mailboxId", p.mailboxId)
                    .put("edPk", Bytes.b64e(p.edPk))
                    .put("xPk", Bytes.b64e(p.xPk))
                    .put("displayName", p.displayName)
                    .put("accepted", p.accepted)
                    .put("verified", p.verified)
                    .put("safetyChanged", p.safetyChanged)
                    .put("blocked", p.blocked || blocked.contains(p.mailboxId))
                    .put("disappearSec", p.disappearSec ?: JSONObject.NULL)
                    .put("nickname", p.nickname ?: JSONObject.NULL)
                    .put("mutedUntil", p.mutedUntil ?: JSONObject.NULL)
                    .put("avatarId", p.avatarId ?: JSONObject.NULL)
                    .put("avatarB64", p.avatarB64 ?: JSONObject.NULL),
            )
        }
        val threadsJ = JSONObject()
        threads.forEach { (k, list) ->
            threadsJ.put(k, org.json.JSONArray().also { arr -> list.forEach { arr.put(msgJson(it)) } })
        }
        val ratJ = JSONObject()
        ratchets.forEach { (k, st) -> ratJ.put(k, Ratchet.dump(st)) }
        return JSONObject()
            .put("mailboxId", id.mailboxId)
            .put("displayName", id.displayName)
            .put("edSk", Bytes.b64e(id.edSk))
            .put("edPk", Bytes.b64e(id.edPk))
            .put("xSk", Bytes.b64e(id.xSk))
            .put("xPk", Bytes.b64e(id.xPk))
            .put("pqSeed", if (id.pqSeed.isEmpty()) JSONObject.NULL else Bytes.b64e(id.pqSeed))
            .put("spkSk", Bytes.b64e(spk.sk))
            .put("spkPk", Bytes.b64e(spk.pk))
            .put("peers", peersJ)
            .put("threads", threadsJ)
            .put("ratchets", ratJ)
            .put("requests", org.json.JSONArray().also { arr -> requests.forEach { arr.put(msgJson(it)) } })
            .put("outbox", org.json.JSONArray().also { arr -> outbox.forEach { arr.put(it) } })
            .put("opks", org.json.JSONArray().also { arr ->
                opks.forEach { (kid, k) ->
                    arr.put(JSONObject().put("id", kid).put("sk", Bytes.b64e(k.sk)).put("pk", Bytes.b64e(k.pk)))
                }
            })
            .put("recoverySeed", recoverySeed ?: JSONObject.NULL)
            .put("deviceId", deviceId ?: JSONObject.NULL)
            .put("deviceCredential", deviceCredential ?: JSONObject.NULL)
            .put("accountEmail", accountEmail ?: JSONObject.NULL)
            .put("historyGeneration", historyGeneration)
        .put("blocked", org.json.JSONArray(blocked.toList()))
        .put("webPocketKnown", webPocketKnown)
        .put("webPocket", webPocket ?: JSONObject.NULL)
        .put("sharedSessions", sharedSessions ?: JSONObject.NULL)
        .put("captureNotice", captureNotice)
        .put("guardianMode", guardianMode)
        .put("autoLockSec", autoLockSec)
        .put("listenBackground", listenBackground)
        .put("quietStartMin", quietStartMin)
        .put("quietEndMin", quietEndMin)
        .put("alertSound", alertSound)
        .put("later", org.json.JSONArray().also { arr ->
            later.forEach {
                arr.put(JSONObject().put("id", it.id).put("threadKey", it.threadKey).put("text", it.text).put("sendAt", it.sendAt))
            }
        })
        .put("shortId", shortId ?: JSONObject.NULL)
        .put("handle", handle ?: JSONObject.NULL)
        .put("avatarId", avatarId ?: JSONObject.NULL)
        .put("avatarB64", avatarB64 ?: JSONObject.NULL)
        .put("groups", groupsJson())
        .put("chambers", chambersJson())
        .put("statuses", org.json.JSONArray().also { arr -> statuses.forEach { arr.put(statusJson(it)) } })
        .put("chamberThreads", org.json.JSONObject().also { obj ->
            chamberThreads.forEach { (k, list) ->
                obj.put(k, org.json.JSONArray().also { arr -> list.forEach { arr.put(msgJson(it)) } })
            }
        })
        .put("groupThreads", org.json.JSONObject().also { obj ->
            groupThreads.forEach { (k, list) ->
                obj.put(k, org.json.JSONArray().also { arr -> list.forEach { arr.put(msgJson(it)) } })
            }
        })
    }

    fun restore(snap: JSONObject) {
        id = Identity(
            snap.getString("mailboxId"),
            Bytes.b64d(snap.getString("edSk")),
            Bytes.b64d(snap.getString("edPk")),
            Bytes.b64d(snap.getString("xSk")),
            Bytes.b64d(snap.getString("xPk")),
            snap.getString("displayName"),
            if (snap.isNull("pqSeed")) ByteArray(0) else Bytes.b64d(snap.optString("pqSeed")),
        )
        spk = XPair(Bytes.b64d(snap.getString("spkSk")), Bytes.b64d(snap.getString("spkPk")))
        recoverySeed = snap.optString("recoverySeed").ifBlank { null }
        deviceId = snap.optString("deviceId").ifBlank { null }
        deviceCredential = snap.optString("deviceCredential").ifBlank { null }
        accountEmail = snap.optString("accountEmail").ifBlank { null }
        historyGeneration = snap.optInt("historyGeneration", 0)
        webPocketKnown = snap.optBoolean("webPocketKnown", false)
        captureNotice = snap.optBoolean("captureNotice", false)
        guardianMode = snap.optString("guardianMode", "warn").ifBlank { "warn" }
        autoLockSec = snap.optInt("autoLockSec", 60)
        listenBackground = snap.optBoolean("listenBackground", false)
        quietStartMin = snap.optInt("quietStartMin", -1)
        quietEndMin = snap.optInt("quietEndMin", -1)
        alertSound = snap.optBoolean("alertSound", true)
        shortId = snap.optString("shortId").ifBlank { null }
        handle = snap.optString("handle").ifBlank { null }
        avatarId = if (snap.isNull("avatarId")) null else snap.optString("avatarId").ifBlank { "" }
        avatarB64 = if (snap.isNull("avatarB64")) null else snap.optString("avatarB64").ifBlank { null }
        webPocket = if (snap.isNull("webPocket")) null else snap.optJSONObject("webPocket")
        sharedSessions = if (snap.isNull("sharedSessions")) null else snap.optJSONObject("sharedSessions")
        loadLists(snap)
        val ch = relay.challenge()
        val sig = Crypto.sign(id.edSk, Crypto.authMessage(id.mailboxId, ch.getString("nonce")))
        val body = JSONObject()
            .put("mailbox_id", id.mailboxId)
            .put("nonce", ch.getString("nonce"))
            .put("signature_b64", Bytes.b64e(sig))
        if (!deviceId.isNullOrBlank() && !deviceCredential.isNullOrBlank()) {
            body.put("device_id", deviceId).put("device_credential", deviceCredential)
        }
        try {
            val tok = relay.token(body)
            token = tok.getString("token")
            shortId = tok.optString("short_id").ifBlank { shortId }
            handle = tok.optString("handle").ifBlank { handle }
        } catch (e: Exception) {
            if (e.message?.contains("erase") == true) throw Erased()
            throw e
        }
    }

    private fun loadLists(snap: JSONObject) {
        peers.clear(); threads.clear(); groupThreads.clear(); groups.clear(); chambers.clear(); chamberThreads.clear(); statuses.clear(); later.clear()
        chamberPins.clear()
        ratchets.clear(); requests.clear(); outbox.clear(); blocked.clear()
        opks.clear()
        snap.optJSONArray("opks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                opks += o.getInt("id") to XPair(Bytes.b64d(o.getString("sk")), Bytes.b64d(o.getString("pk")))
            }
        }
        snap.optJSONObject("peers")?.keys()?.forEach { k ->
            val o = snap.getJSONObject("peers").getJSONObject(k)
            peers[k] = Peer(
                o.getString("mailboxId"),
                Bytes.b64d(o.getString("edPk")),
                Bytes.b64d(o.getString("xPk")),
                o.getString("displayName"),
                o.optBoolean("accepted"),
                o.optBoolean("verified"),
                if (o.isNull("disappearSec")) null else o.optInt("disappearSec"),
                o.optBoolean("safetyChanged"),
                o.optBoolean("blocked"),
                o.optString("nickname").ifBlank { null },
                if (o.isNull("mutedUntil")) null else o.optLong("mutedUntil"),
                if (o.isNull("avatarId")) null else o.optString("avatarId"),
                if (o.isNull("avatarB64")) null else o.optString("avatarB64").ifBlank { null },
            )
            if (o.optBoolean("blocked")) blocked += k
        }
        snap.optJSONArray("blocked")?.let { arr ->
            for (i in 0 until arr.length()) blocked += arr.getString(i)
        }
        snap.optJSONObject("threads")?.keys()?.forEach { k ->
            val arr = snap.getJSONObject("threads").getJSONArray(k)
            threads[k] = MutableList(arr.length()) { i -> msgFrom(arr.getJSONObject(i)) }
        }
        snap.optJSONObject("ratchets")?.keys()?.forEach { k ->
            ratchets[k] = Ratchet.load(snap.getJSONObject("ratchets").getJSONObject(k))
        }
        snap.optJSONArray("requests")?.let { arr ->
            for (i in 0 until arr.length()) requests += msgFrom(arr.getJSONObject(i))
        }
        snap.optJSONArray("outbox")?.let { arr ->
            outbox.clear()
            for (i in 0 until arr.length()) outbox += arr.getJSONObject(i)
        }
        snap.optJSONObject("groups")?.let { importGroups(it, replace = true) }
        snap.optJSONArray("statuses")?.let { arr ->
            for (i in 0 until arr.length()) statuses += statusFrom(arr.getJSONObject(i))
        }
        snap.optJSONObject("groupThreads")?.keys()?.forEach { k ->
            val arr = snap.getJSONObject("groupThreads").getJSONArray(k)
            groupThreads[k] = MutableList(arr.length()) { i -> msgFrom(arr.getJSONObject(i)) }
        }
        snap.optJSONObject("chambers")?.let { importChambers(it) }
        snap.optJSONObject("chamberThreads")?.keys()?.forEach { k ->
            val arr = snap.getJSONObject("chamberThreads").getJSONArray(k)
            chamberThreads[k] = MutableList(arr.length()) { i -> msgFrom(arr.getJSONObject(i)) }
        }
        chambers.values.forEach { it.unlocked = false }
        snap.optJSONArray("later")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                later += LaterQueue.Item(o.optString("id"), o.optString("threadKey"), o.optString("text"), o.optLong("sendAt"))
            }
        }
    }

    fun quietNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMs
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return QuietHours.active(quietStartMin, quietEndMin, nowMin)
    }

    fun scheduleText(threadKey: String, text: String, sendAt: Long): String {
        if (threadKey.startsWith("ch:")) return "Send later stays on a direct chat or a group."
        val next = LaterQueue.enqueue(later.toList(), LaterQueue.Item(java.util.UUID.randomUUID().toString(), threadKey, text, sendAt))
            ?: return "Twenty scheduled messages is the limit on this device."
        later.clear()
        later.addAll(next)
        return "Scheduled on this phone. It sends while Bleep is open, or while this phone is listening. Otherwise it sends the next time you open Bleep."
    }

    private fun flushLater() {
        if (!ready) return
        val split = LaterQueue.takeDue(later.toList(), System.currentTimeMillis())
        later.clear()
        later.addAll(split.second)
        for (item in split.first) {
            if (item.threadKey.startsWith("ch:")) continue
            try {
                if (item.threadKey.startsWith("g:")) sendGroupText(item.threadKey.removePrefix("g:"), item.text)
                else {
                    val peer = peers[item.threadKey] ?: continue
                    sendText(peer, item.text)
                }
            } catch (_: Exception) {
                later += item
            }
        }
    }

    private fun ingestPoll(mid: String, pt: JSONObject): Boolean {
        val gid = pt.optString("groupId")
        val group = groups[gid] ?: return false
        val members = group.members.map { it.mailboxId }.toSet()
        if (mid !in members) return false
        val key = "g:$gid"
        return when (pt.optString("kind")) {
            "poll" -> {
                val id = pt.optString("id")
                if (id.isBlank() || alreadyHave(id)) return false
                val options = pt.optJSONArray("options")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: return false
                if (options.size < 2) return false
                groupThreads.getOrPut(key) { mutableListOf() } += ChatMsg(
                    id,
                    false,
                    pt.optString("question"),
                    System.currentTimeMillis(),
                    mid,
                    kind = "poll",
                    pollQuestion = pt.optString("question").take(120),
                    pollOptionsJson = optionsJson(options.map { it.take(40) }),
                    groupId = gid,
                )
                true
            }
            "vote" -> {
                val list = groupThreads[key] ?: return false
                val i = list.indexOfFirst { it.id == pt.optString("pollId") }
                if (i < 0) return false
                val row = list[i]
                val next = Polls.applyVote(
                    votesOf(row.pollVotesJson),
                    mid,
                    pt.optInt("option", -1),
                    optionsOf(row.pollOptionsJson).size,
                    members,
                    row.pollClosed,
                ) ?: return false
                list[i] = row.copy(pollVotesJson = votesJson(next))
                true
            }
            "poll-close" -> {
                val list = groupThreads[key] ?: return false
                val i = list.indexOfFirst { it.id == pt.optString("pollId") }
                if (i < 0) return false
                val row = list[i]
                val author = row.peerId.ifBlank { if (row.fromMe) id.mailboxId else "" }
                if (mid != author) return false
                list[i] = row.copy(pollClosed = true)
                true
            }
            else -> false
        }
    }

    fun toggleStar(threadKey: String, msgId: String) {
        val list = bucket(threadKey) ?: return
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0 || list[i].deleted) return
        list[i] = list[i].copy(starred = !list[i].starred)
    }

    private fun msgJson(m: ChatMsg) = JSONObject()
        .put("id", m.id)
        .put("fromMe", m.fromMe)
        .put("text", m.text)
        .put("at", m.at)
        .put("peerId", m.peerId)
        .put("kind", m.kind)
        .put("mime", m.mime ?: JSONObject.NULL)
        .put("bytesB64", m.bytesB64 ?: JSONObject.NULL)
        .put("reacted", m.reacted ?: JSONObject.NULL)
        .put("expiresAt", m.expiresAt ?: JSONObject.NULL)
        .put("name", m.name ?: JSONObject.NULL)
        .put("fileId", m.fileId ?: JSONObject.NULL)
        .put("replyTo", m.replyTo ?: JSONObject.NULL)
        .put("replyText", m.replyText ?: JSONObject.NULL)
        .put("viewOnce", m.viewOnce)
        .put("viewed", m.viewed)
        .put("viewUntil", m.viewUntil ?: JSONObject.NULL)
        .put("deleted", m.deleted)
        .put("groupId", m.groupId ?: JSONObject.NULL)
        .put("groupName", m.groupName ?: JSONObject.NULL)
        .put("chamberInvite", m.chamberInvite)
        .put("burnAt", m.burnAt ?: JSONObject.NULL)
        .put("starred", m.starred && !m.deleted)
        .put("pollQuestion", m.pollQuestion ?: JSONObject.NULL)
        .put("pollOptionsJson", m.pollOptionsJson ?: JSONObject.NULL)
        .put("pollClosed", m.pollClosed)
        .put("pollVotesJson", m.pollVotesJson ?: JSONObject.NULL)

    private fun msgFrom(o: JSONObject) = ChatMsg(
        o.getString("id"),
        o.getBoolean("fromMe"),
        o.optString("text"),
        o.getLong("at"),
        o.optString("peerId"),
        o.optString("kind", "text"),
        if (o.isNull("mime")) null else o.optString("mime"),
        if (o.isNull("bytesB64")) null else o.optString("bytesB64"),
        if (o.isNull("reacted")) null else o.optString("reacted"),
        if (o.isNull("expiresAt")) null else o.optLong("expiresAt"),
        if (o.isNull("name")) null else o.optString("name"),
        if (o.isNull("fileId")) o.optString("attachmentId").takeIf { it.isNotEmpty() } else o.optString("fileId").takeIf { it.isNotEmpty() },
        o.optString("replyTo").ifBlank { null },
        o.optString("replyText").ifBlank { null },
        o.optBoolean("viewOnce"),
        o.optBoolean("viewed"),
        if (o.isNull("viewUntil")) null else o.optLong("viewUntil"),
        o.optBoolean("deleted"),
        o.optString("groupId").ifBlank { null },
        o.optString("groupName").ifBlank { null },
        o.optBoolean("chamberInvite") || o.optBoolean("chamber"),
        if (o.isNull("burnAt")) null else o.optLong("burnAt").takeIf { it > 0 },
        o.optBoolean("starred") && !o.optBoolean("deleted"),
        o.optString("pollQuestion").ifBlank { null },
        o.optString("pollOptionsJson").ifBlank { null },
        o.optBoolean("pollClosed"),
        o.optString("pollVotesJson").ifBlank { null },
    )

    fun addByCode(code: String): Peer {
        val short = Crypto.shortIdFromInput(code)
        if (short != null) {
            try {
                return addByShort(short)
            } catch (e: Exception) {
                val bare = code.trim().removePrefix("@").lowercase()
                if (e.message?.contains("404") == true && !code.contains("-") && Regex("^[a-z][a-z0-9_]{2,23}$").matches(bare)) {
                    return addByHandle(bare)
                }
                if (e.message?.contains("404") == true) error("No Bleep ID with that code.")
                throw e
            }
        }
        val named = Crypto.handleFromInput(code)
        if (named != null) return addByHandle(named)
        val (mid, ed, x) = Crypto.parseCode(code)
        if (mid == id.mailboxId) error("That is this account.")
        if (blocked.contains(mid)) error("That person is blocked.")
        val p = Peer(mid, ed, x, mid.take(6))
        peers[mid] = p
        return p
    }

    fun addByShort(raw: String): Peer {
        val short = Crypto.shortIdFromInput(raw) ?: error("Paste a Bleep ID such as k7m2-pq9x, or a published handle.")
        val row = relay.resolveShort(token, short)
        val mid = row.getString("mailbox_id")
        if (mid == id.mailboxId) error("That is this account.")
        if (blocked.contains(mid)) error("That person is blocked.")
        val handle = row.optString("handle")
        val label = if (handle.isNotEmpty()) "@$handle" else "No handle"
        val p = Peer(mid, Bytes.b64d(row.getString("identity_ed25519_b64")), Bytes.b64d(row.getString("identity_x25519_b64")), label)
        peers[mid] = p
        return p
    }

    fun addByHandle(raw: String): Peer {
        val named = raw.trim().removePrefix("@").lowercase()
        if (!Regex("^[a-z][a-z0-9_]{2,23}$").matches(named)) error("Paste a Bleep ID such as k7m2-pq9x, or a published handle.")
        val row = try {
            relay.resolveHandle(token, named)
        } catch (e: Exception) {
            if (e.message?.contains("404") == true) error("No published handle with that name.")
            throw e
        }
        val mid = row.getString("mailbox_id")
        if (mid == id.mailboxId) error("That is this account.")
        if (blocked.contains(mid)) error("That person is blocked.")
        return addFromDirectory(row.optString("handle", named), mid, row.getString("identity_ed25519_b64"), row.getString("identity_x25519_b64"))
    }

    fun sendText(peer: Peer, text: String, replyTo: String? = null, replyText: String? = null, viewOnce: Boolean = false) {
        if (peer.safetyChanged) error("Safety number changed. This is a new key. Verify it before you send.")
        beginSend(peer)
        ensureAlice(peer)
        val st = ratchets[peer.mailboxId]!!
        val exp = peer.disappearSec?.let { System.currentTimeMillis() + it * 1000L }
        val payload = JSONObject().put("kind", "text").put("id", java.util.UUID.randomUUID().toString()).put("text", text)
        if (peer.disappearSec != null) payload.put("expireSec", peer.disappearSec)
        if (!replyTo.isNullOrBlank()) payload.put("replyTo", replyTo).put("replyText", replyText ?: "")
        if (viewOnce) payload.put("viewOnce", true)
        val (header, ct) = Ratchet.encrypt(st, Bytes.utf8(payload.toString()))
        val isPk = pendingPk.remove(peer.mailboxId)
        val unsigned = JSONObject()
            .put("v", 1)
            .put("t", if (isPk) "pkmsg" else "msg")
            .put("sid", Bytes.b64e(id.edPk))
            .put("spk", Bytes.b64e(id.xPk))
            .put("mid", id.mailboxId)
            .put("h", header.json())
            .put("ct", Bytes.b64e(ct))
            .put("ts", System.currentTimeMillis())
        stampPrekey(unsigned, peer.mailboxId, isPk)
        val sig = Crypto.sign(id.edSk, Bytes.utf8(Canonical.of(unsigned)))
        unsigned.put("sig", Bytes.b64e(sig))
        val blob = Seal.outer(peer.xPk, Bytes.utf8(unsigned.toString()))
        enqueueDrop(peer.mailboxId, Bytes.b64e(blob))
        val list = threads.getOrPut(peer.mailboxId) { mutableListOf() }
        list += ChatMsg(payload.getString("id"), true, text, System.currentTimeMillis(), peer.mailboxId, expiresAt = exp, replyTo = replyTo, replyText = replyText, viewOnce = viewOnce)
        finishSend(peer.mailboxId)
    }

    fun sendReact(peer: Peer, targetId: String, emoji: String = "👍") {
        sendPayload(peer, JSONObject().put("kind", "react").put("id", java.util.UUID.randomUUID().toString()).put("target", targetId).put("emoji", emoji), intro = false)
        val list = threads[peer.mailboxId] ?: return
        val i = list.indexOfFirst { it.id == targetId }
        if (i >= 0) list[i] = list[i].copy(reacted = emoji)
    }

    fun sweep() {
        val now = System.currentTimeMillis()
        val drop: (ChatMsg) -> ChatMsg = { m ->
            when {
                m.expiresAt != null && m.expiresAt < now -> m.copy(text = "", bytesB64 = null, kind = "system", deleted = true)
                m.viewUntil != null && m.viewUntil < now -> m.copy(text = "", bytesB64 = null, kind = "system", deleted = true)
                else -> m
            }
        }
        threads.values.forEach { list -> list.replaceAll(drop) }
        groupThreads.values.forEach { list -> list.replaceAll(drop) }
        chamberThreads.values.forEach { list -> list.replaceAll(drop) }
        statuses.removeAll { it.until < now }
    }

    fun sendCapture(peer: Peer) {
        if (!captureNotice || peer.safetyChanged) return
        val id = java.util.UUID.randomUUID().toString()
        sendPayload(peer, JSONObject().put("kind", "ctrl").put("id", id).put("op", "screen-capture"), intro = false)
        threads.getOrPut(peer.mailboxId) { mutableListOf() } += ChatMsg(
            id,
            true,
            "This phone captured the screen. The note has no image.",
            System.currentTimeMillis(),
            peer.mailboxId,
            kind = "system",
        )
    }

    fun sendMedia(
        peer: Peer,
        kind: String,
        bytes: ByteArray,
        mime: String,
        name: String,
        replyTo: String? = null,
        replyText: String? = null,
        viewOnce: Boolean = false,
    ) {
        if (peer.safetyChanged) error("Safety number changed. This is a new key. Verify it before you send.")
        val id = java.util.UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("kind", kind)
            .put("id", id)
            .put("text", "")
            .put("mime", mime)
            .put("name", name)
            .put("bytesB64", Bytes.b64e(bytes))
        if (!replyTo.isNullOrBlank()) payload.put("replyTo", replyTo).put("replyText", replyText ?: "")
        if (viewOnce) payload.put("viewOnce", true)
        sendPayload(peer, payload, intro = false)
        threads.getOrPut(peer.mailboxId) { mutableListOf() } +=
            ChatMsg(
                id,
                true,
                if (kind == "voice") "Voice note" else if (kind == "photo") "Photo" else name,
                System.currentTimeMillis(),
                peer.mailboxId,
                kind,
                mime,
                Bytes.b64e(bytes),
                name = name,
                fileId = id,
                replyTo = replyTo,
                replyText = replyText,
                viewOnce = viewOnce,
            )
    }

    fun sendIntro(peer: Peer, hello: String) {
        val msgId = java.util.UUID.randomUUID().toString()
        val payload = JSONObject().put("kind", "intro").put("id", msgId).put("text", hello).put("displayName", id.displayName)
        handle?.let { payload.put("handle", it) }
        sendPayload(peer, payload, intro = true, freshIntro = true)
        peer.accepted = true
        threads.getOrPut(peer.mailboxId) { mutableListOf() } += ChatMsg(msgId, true, hello, System.currentTimeMillis(), peer.mailboxId)
        try {
            shareProfileWith(peer)
        } catch (_: Exception) { }
    }

    private fun sendPayload(peer: Peer, payload: JSONObject, intro: Boolean, ttlSeconds: Int? = null, freshIntro: Boolean = false) {
        beginSend(peer)
        if (freshIntro && threads[peer.mailboxId].orEmpty().isEmpty()) clearSession(peer.mailboxId)
        ensureAlice(peer)
        val st = ratchets[peer.mailboxId]!!
        val (header, ct) = Ratchet.encrypt(st, Bytes.utf8(payload.toString()))
        val isPk = pendingPk.remove(peer.mailboxId)
        val unsigned = JSONObject()
            .put("v", 1)
            .put("t", if (intro) "intro" else if (isPk) "pkmsg" else "msg")
            .put("sid", Bytes.b64e(id.edPk))
            .put("spk", Bytes.b64e(id.xPk))
            .put("mid", id.mailboxId)
            .put("h", header.json())
            .put("ct", Bytes.b64e(ct))
            .put("ts", System.currentTimeMillis())
        stampPrekey(unsigned, peer.mailboxId, isPk)
        unsigned.put("sig", Bytes.b64e(Crypto.sign(id.edSk, Bytes.utf8(Canonical.of(unsigned)))))
        enqueueDrop(peer.mailboxId, Bytes.b64e(Seal.outer(peer.xPk, Bytes.utf8(unsigned.toString()))), ttlSeconds)
        finishSend(peer.mailboxId)
    }

    private fun enqueueDrop(dest: String, blobB64: String, ttlSeconds: Int? = null) {
        try {
            relay.drop(token, dest, blobB64, ttlSeconds)
        } catch (_: Exception) {
            val queued = JSONObject().put("dest", dest).put("blob", blobB64)
            if (ttlSeconds != null) queued.put("ttl", ttlSeconds)
            outbox += queued
        }
    }

    fun flushOutbox() {
        val left = mutableListOf<JSONObject>()
        for (q in outbox) {
            try {
                val ttl = if (q.has("ttl") && !q.isNull("ttl")) q.optInt("ttl") else null
                relay.drop(token, q.getString("dest"), q.getString("blob"), ttl)
            } catch (_: Exception) {
                left += q
            }
        }
        outbox.clear()
        outbox.addAll(left)
    }

    fun poll() {
        val calls = mutableListOf<Pair<Peer, JSONObject>>()
        synchronized(ioLock) {
        callBatch = calls
        try {
        lastIngest = 0
        mailAlert = 0
        flushLater()
        liftShortIds()
        flushOutbox()
        val items = relay.fetch(token)
        val ack = org.json.JSONArray()
        for (i in 0 until items.length()) {
            val row = items.getJSONObject(i)
            val eid = row.optString("id")
            val opened = if (eid.isNotBlank() && eid in openedIds) true
            else try {
                ingest(row.getString("blob_b64"))
            } catch (_: Exception) {
                false
            }
            if (opened && eid.isNotBlank()) {
                openedIds += eid
                ack.put(eid)
            }
        }
        if (ack.length() > 0) {
            try {
                relay.ack(token, ack)
                for (i in 0 until ack.length()) openedIds.remove(ack.getString(i))
            } catch (_: Exception) { }
        }
        sweep()
        refreshPresence()
        if (sessionDirty) {
            sessionDirty = false
            try {
                pushHistory(force = true)
            } catch (_: Exception) { }
        }
        } finally {
            callBatch = null
        }
        }
        for ((who, payload) in calls) {
            try {
                onCall?.invoke(who, payload)
            } catch (_: Exception) { }
        }
    }

    fun sendCall(peer: Peer, phase: String, sdp: String?, candidate: String?, media: String?) {
        val payload = JSONObject()
            .put("kind", "call")
            .put("id", java.util.UUID.randomUUID().toString())
            .put("phase", phase)
        if (!sdp.isNullOrBlank()) payload.put("sdp", sdp)
        if (!candidate.isNullOrBlank()) payload.put("candidate", candidate)
        if (media == "audio" || media == "video") payload.put("media", media)
        sendPayload(peer, payload, intro = false)
    }

    fun noteMissedCall(peer: Peer, fromMe: Boolean) {
        threads.getOrPut(peer.mailboxId) { mutableListOf() } += ChatMsg(
            java.util.UUID.randomUUID().toString(),
            fromMe,
            "Missed call",
            System.currentTimeMillis(),
            peer.mailboxId,
            kind = "call",
        )
        lastIngest++
    }

    private fun refreshPresence() {
        if (!ready) return
        val ids = peers.values.filter { it.accepted }.map { it.mailboxId }.take(30)
        if (ids.isEmpty()) return
        try {
            val rows = relay.presence(token, ids).optJSONArray("items") ?: return
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                presence[row.getString("mailbox_id")] = row.optBoolean("online") to row.optString("last_seen").ifBlank { null }
            }
        } catch (_: Exception) { }
    }

    fun sendLocation(peer: Peer, lat: Double, lon: Double, until: Long) {
        val stop = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))
        val latText = java.lang.String.format(java.util.Locale.US, "%.5f", lat)
        val lonText = java.lang.String.format(java.util.Locale.US, "%.5f", lon)
        val text = "Live location $latText, $lonText until $stop. https://www.openstreetmap.org/?mlat=$latText&mlon=$lonText"
        val msgId = java.util.UUID.randomUUID().toString()
        sendPayload(peer, JSONObject().put("kind", "location").put("id", msgId).put("text", text).put("lat", lat).put("lon", lon).put("until", until), intro = false)
        threads.getOrPut(peer.mailboxId) { mutableListOf() } += ChatMsg(msgId, true, text, System.currentTimeMillis(), peer.mailboxId)
    }

    fun createChannel(name: String): String = relay.createChannel(token, name).getString("id")

    fun channelNames(): org.json.JSONArray = relay.channels(token).optJSONArray("items") ?: org.json.JSONArray()

    fun channelPosts(id: String): org.json.JSONArray = relay.channelPosts(token, id).optJSONArray("posts") ?: org.json.JSONArray()

    fun postChannel(channelId: String, text: String) {
        relay.postChannel(token, channelId, text, id.displayName)
    }

    private fun attachMlkem(body: JSONObject, ident: Identity) {
        if (ident.pqSeed.size != 64) return
        val pub = Crypto.pqPublic(ident.pqSeed)
        body.put("identity_mlkem_b64", Bytes.b64e(pub))
        body.put("mlkem_sig_b64", Bytes.b64e(Crypto.sign(ident.edSk, Bytes.concat(Bytes.utf8("BLEEP-MLKEM-v1|"), pub))))
    }

    private fun alreadyHave(messageId: String): Boolean {
        if (messageId.isBlank()) return false
        if (requests.any { it.id == messageId }) return true
        if (statuses.any { it.id == messageId }) return true
        if (threads.values.any { list -> list.any { it.id == messageId } }) return true
        if (groupThreads.values.any { list -> list.any { it.id == messageId } }) return true
        if (chamberThreads.values.any { list -> list.any { it.id == messageId } }) return true
        return false
    }

    private fun ingest(blobB64: String): Boolean {
        val innerBytes = try {
            Seal.openOuter(id.xSk, Bytes.b64d(blobB64))
        } catch (_: Exception) {
            return false
        }
        val inner = try {
            JSONObject(Bytes.fromUtf8(innerBytes))
        } catch (_: Exception) {
            return false
        }
        val signed = try {
            val rest = JSONObject(inner.toString()).apply { remove("sig") }
            Crypto.verify(Bytes.b64d(inner.getString("sid")), Bytes.utf8(Canonical.of(rest)), Bytes.b64d(inner.getString("sig")))
        } catch (_: Exception) {
            return false
        }
        if (!signed) return true
        val mid = inner.getString("mid")
        val fromEd = Bytes.b64d(inner.getString("sid"))
        val fromX = Bytes.b64d(inner.getString("spk"))
        peers[mid]?.let { existing ->
            if (!existing.edPk.contentEquals(fromEd)) {
                existing.safetyChanged = true
                existing.verified = false
            }
        }
        var st = ratchets[mid]
        val created = st == null
        if (st == null) {
            val kind = inner.optString("t")
            if (kind != "pkmsg" && kind != "intro") return false
            if (!inner.has("ek")) return false
            val opkId = if (inner.has("used_opk") && !inner.isNull("used_opk")) inner.getInt("used_opk") else null
            val opkSk = if (opkId == null) null else opks.firstOrNull { it.first == opkId }?.second?.sk
            if (opkId != null && opkSk == null) return false
            var sk = Ratchet.x3dhBob(id.xSk, spk.sk, fromX, Bytes.b64d(inner.getString("ek")), opkSk)
            val pq = inner.optString("pq")
            if (pq.isNotBlank()) {
                if (id.pqSeed.size != 64) return false
                sk = try {
                    Ratchet.hybridRoot(sk, Crypto.pqDecaps(Bytes.b64d(pq), id.pqSeed))
                } catch (_: Exception) {
                    return false
                }
            }
            st = Ratchet.initBob(sk, spk.sk, spk.pk)
        }
        val h = inner.getJSONObject("h")
        val header = Header(h.getString("dh"), h.getInt("n"), h.getInt("pn"))
        val state = st ?: return false
        val pt = try {
            JSONObject(Bytes.fromUtf8(Ratchet.decrypt(state, header, Bytes.b64d(inner.getString("ct")))))
        } catch (_: Exception) {
            return false
        }
        if (created) ratchets[mid] = state
        publishSession(mid)
        sessionDirty = true
        val kind = pt.optString("kind")
        if (kind == "ctrl" && pt.optString("op") == "screen-capture") {
            val peer = peers[mid] ?: return true
            if (!peer.accepted) return true
            threads.getOrPut(mid) { mutableListOf() } += ChatMsg(
                pt.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                false,
                "Their phone reported a screen capture. A photo of the screen is not attached, and a different app can stay silent.",
                System.currentTimeMillis(),
                mid,
                kind = "system",
            )
            noteMail("ctrl", "screen-capture")
            return true
        }
        if (kind == "ctrl" && pt.optString("op") == "status") {
            val data = pt.optJSONObject("data") ?: JSONObject()
            val peer = peers.getOrPut(mid) {
                Peer(mid, fromEd, fromX, mid.take(6), accepted = false)
            }
            val raw = data.optString("bytesB64").ifBlank { null }
            val item = StatusItem(
                pt.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                mid,
                peer.displayName,
                data.optString("text").ifBlank { null },
                System.currentTimeMillis(),
                if (data.has("until")) data.optLong("until") else System.currentTimeMillis() + 86_400_000L,
                data.optString("mime").ifBlank { null },
                data.optString("name").ifBlank { null },
                if (raw == null) null else java.util.UUID.randomUUID().toString(),
                raw,
            )
            if (statuses.none { it.id == item.id }) statuses.add(0, item)
            noteMail("ctrl", "status")
            return true
        }
        if (kind == "ctrl" && pt.optString("op") == "profile") {
            val data = pt.optJSONObject("data") ?: JSONObject()
            val peer = peers.getOrPut(mid) {
                Peer(mid, fromEd, fromX, mid.take(6), accepted = false)
            }
            val raw = data.optString("bytesB64").ifBlank { null }
            if (data.optBoolean("clear") || raw == null) {
                peer.avatarId = ""
                peer.avatarB64 = null
            } else {
                peer.avatarId = java.util.UUID.randomUUID().toString()
                peer.avatarB64 = raw
            }
            noteMail("ctrl", "profile")
            return true
        }
        if (kind == "ctrl" && pt.optString("op") == "leave-group") {
            val gid = pt.optJSONObject("data")?.optString("groupId").orEmpty()
            val group = groups[gid]
            if (group != null) {
                val next = GroupOps.applyLeave(group.members, group.version, mid)
                if (next != null) {
                    groups[gid] = group.copy(members = next.first, version = next.second)
                    groupThreads.getOrPut("g:$gid") { mutableListOf() } += ChatMsg(
                        pt.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        false,
                        "They left.",
                        System.currentTimeMillis(),
                        mid,
                        kind = "system",
                    )
                    if (Alerts.mail("ctrl", op = "leave-group")) mailAlert++
                }
            }
            lastIngest++
            return true
        }
        if (kind == "call") {
            val who = peers[mid]
            if (who != null && who.accepted && !who.blocked) {
                callBatch?.add(who to JSONObject(pt.toString()))
            }
            if (pt.optString("phase") == "offer") lastIngest++
            if (pt.optString("phase") == "hangup" || pt.optString("phase") == "missed") ringOffer = false
            return true
        }
        if (kind == "poll" || kind == "vote" || kind == "poll-close") {
            if (ingestPoll(mid, pt)) noteMail(kind)
            return true
        }
        if (kind == "react") {
            val target = pt.optString("target")
            val emoji = pt.optString("emoji")
            fun mark(list: MutableList<ChatMsg>?) {
                if (list == null) return
                val i = list.indexOfFirst { it.id == target }
                if (i >= 0) list[i] = list[i].copy(reacted = emoji)
            }
            mark(threads[mid])
            groupThreads.values.forEach { mark(it) }
            return true
        }
        val text = pt.optString("text")
        val peer = peers.getOrPut(mid) {
            Peer(mid, Bytes.b64d(inner.getString("sid")), fromX, mid.take(6), accepted = false)
        }
        val bytesB64 = pt.optString("bytesB64").ifBlank { null }
        val msgKind = if (kind in listOf("photo", "voice", "file")) kind else "text"
        val label = when (msgKind) {
            "voice" -> "Voice note"
            "photo" -> "Photo"
            else -> text.ifBlank { "Request to message" }
        }
        val groupInvite = pt.optString("groupId").ifBlank { null }
        val msg = ChatMsg(
            pt.optString("id"),
            false,
            label,
            System.currentTimeMillis(),
            mid,
            msgKind,
            pt.optString("mime").ifBlank { null },
            bytesB64,
            name = pt.optString("name").ifBlank { null },
            replyTo = pt.optString("replyTo").ifBlank { null },
            replyText = pt.optString("replyText").ifBlank { null },
            viewOnce = pt.optBoolean("viewOnce"),
            groupId = groupInvite,
            groupName = pt.optString("groupName").ifBlank { null },
            chamberInvite = pt.optBoolean("chamber"),
            burnAt = if (pt.has("burnAt") && !pt.isNull("burnAt")) pt.optLong("burnAt") else null,
        )
        if (msg.id.isNotBlank() && alreadyHave(msg.id)) return true
        val holdIntro = kind == "intro" && (!peer.accepted || groupInvite != null || msg.chamberInvite)
        if (holdIntro || !peer.accepted) {
            requests += msg
            noteMail(kind)
            if (kind == "intro") {
                val incoming = pt.optString("handle").removePrefix("@").lowercase()
                if (incoming.isNotEmpty()) peer.displayName = "@$incoming"
                else if (!peer.displayName.startsWith("@")) {
                    val named = pt.optString("displayName")
                    peer.displayName = when {
                        named.isNotEmpty() && Crypto.shortIdFromInput(named) == null -> named
                        Crypto.shortIdFromInput(peer.displayName) != null -> "No handle"
                        else -> peer.displayName
                    }
                }
            }
            return true
        }
        if (!groupInvite.isNullOrBlank() && groups.containsKey(groupInvite)) {
            groupThreads.getOrPut("g:$groupInvite") { mutableListOf() } += msg
            noteMail(if (kind == "location") kind else msgKind)
            return true
        }
        if (!groupInvite.isNullOrBlank()) {
            requests += msg
            noteMail(if (kind == "location") kind else msgKind)
            return true
        }
        if (pt.optBoolean("chamber") && kind != "intro") {
            val chamber = chambers.values.find { it.peerMailboxId == mid || it.id == mid }
            if (chamber != null) chamberThreads.getOrPut("ch:${chamber.id}") { mutableListOf() } += msg
            else requests += msg.copy(chamberInvite = true)
            noteMail(if (kind == "location") kind else msgKind)
            return true
        }
        threads.getOrPut(mid) { mutableListOf() } += msg
        if (guardianMode != "off") {
            val textHits = Guardian.scan(text, peer.verified)
            val fileHits = if (!bytesB64.isNullOrBlank()) {
                Guardian.scanFile(pt.optString("name"), pt.optString("mime"), Bytes.b64d(bytesB64), standing = false)
            } else emptyList()
            val notes = textHits + fileHits
            if (notes.isNotEmpty()) {
                threads.getOrPut(mid) { mutableListOf() } += ChatMsg(
                    java.util.UUID.randomUUID().toString(),
                    false,
                    "Guardian: " + notes.joinToString(" ") { it.reason },
                    System.currentTimeMillis(),
                    mid,
                    kind = "system",
                )
            }
        }
        noteMail(if (kind == "location") kind else msgKind)
        return true
    }

    private fun noteMail(kind: String, op: String = "") {
        lastIngest++
        if (Alerts.mail(kind, op = op)) mailAlert++
    }

    fun verifyPeer(peer: Peer) {
        peer.verified = true
        peer.safetyChanged = false
    }

    fun unlink() {
        if (ready) relay.unlink(id.mailboxId, token)
    }

    fun searchDirectory(q: String): org.json.JSONArray {
        if (q.trim().length < 2 || !ready) return org.json.JSONArray()
        val rows = relay.directory(token, q.trim().lowercase()).optJSONArray("items") ?: org.json.JSONArray()
        val out = org.json.JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (!blocked.contains(row.optString("mailbox_id"))) out.put(row)
        }
        return out
    }

    fun changePassword(current: String, next: String) {
        val email = accountEmail ?: error("This vault has no email login.")
        val account = relay.loginAccount(JSONObject(AccountRequests.login(email, current)))
        relay.changePassword(account.getString("account_token"), JSONObject(AccountRequests.password(current, next)))
    }

    fun closeAccount(password: String) {
        val email = accountEmail ?: error("This vault has no email login.")
        val account = relay.loginAccount(JSONObject(AccountRequests.login(email, password)))
        relay.closeAccount(account.getString("account_token"), JSONObject(AccountRequests.close(password)))
    }

    fun confirmAddress(token: String) {
        relay.confirm(JSONObject(AccountRequests.confirm(token)))
    }

    fun removeHandle() {
        if (ready) relay.dropHandle(token)
        handle = null
    }

    private fun liftShortIds() {
        if (liftedIds || !ready) return
        var ok = true
        for (p in peers.values.toList()) {
            val short = Crypto.shortIdFromInput(p.displayName) ?: continue
            try {
                val named = relay.resolveShort(token, short).optString("handle")
                p.displayName = if (named.isNotEmpty()) "@$named" else "No handle"
            } catch (e: Exception) {
                if (e.message?.contains("404") == true) p.displayName = "No handle"
                else ok = false
            }
        }
        if (ok) liftedIds = true
    }

    fun eraseThisDevice() {
        val dev = deviceId ?: return
        if (ready) relay.eraseDevice(token, dev)
    }

    fun addFromDirectory(handle: String, mailboxId: String, edB64: String, xB64: String): Peer {
        val p = Peer(mailboxId, Bytes.b64d(edB64), Bytes.b64d(xB64), "@$handle")
        peers[mailboxId] = p
        return p
    }

    fun deviceRows(): org.json.JSONArray {
        if (!ready) return org.json.JSONArray()
        return relay.devices(token).optJSONArray("items") ?: org.json.JSONArray()
    }

    fun revokeDevice(device: String) {
        if (ready) relay.revokeDevice(token, device)
    }

    fun eraseRemote(device: String) {
        if (ready) relay.eraseDevice(token, device)
    }

    fun pullHistory() {
        val seedB64 = recoverySeed ?: return
        if (!ready) return
        val remote = relay.history(token)
        val gen = remote.optInt("generation", 0)
        if (gen <= 0 || !remote.has("blob_b64")) {
            webPocketKnown = true
            return
        }
        val view = openHistory(seedB64, remote)
        notePocket(view)
        if (gen <= historyGeneration) {
            hydrateFiles()
            return
        }
        applyShared(view)
        historyGeneration = gen
        hydrateFiles()
        lastHistoryJson = historyView().toString()
    }

    fun pushHistory(force: Boolean = false, rethrowConflict: Boolean = false) {
        val seedB64 = recoverySeed ?: return
        if (!ready) return
        try {
            if (!webPocketKnown) pullHistory()
            uploadFiles(seedB64)
            pushChambers()
            val remote = relay.history(token)
            val text = historyView().toString()
            if (!force && text == lastHistoryJson) return
            val gen = maxOf(historyGeneration, remote.optInt("generation", 0)) + 1
            val nonce = Bytes.random(12)
            val ct = Crypto.aeadEncrypt(Crypto.historyKey(Bytes.b64d(seedB64)), nonce, text.toByteArray(Charsets.UTF_8))
            relay.putHistory(token, JSONObject(AccountRequests.history(gen, Bytes.b64e(nonce), Bytes.b64e(ct))))
            historyGeneration = gen
            lastHistoryJson = text
        } catch (e: Exception) {
            if (e.message?.contains("erase") == true) throw Erased()
            if (e.message?.contains("409") == true) {
                if (rethrowConflict) throw e
                pullHistory()
            } else if (rethrowConflict) {
                throw e
            }
        }
    }

    private fun notePocket(view: JSONObject) {
        when (view.optString("kind")) {
            "shared-v1" -> {
                webPocketKnown = true
                webPocket = if (!view.has("web") || view.isNull("web")) null else view.optJSONObject("web")
                if (view.has("android") && !view.isNull("android")) applyAndroidPocket(view.getJSONObject("android"))
            }
            "web" -> {
                webPocketKnown = true
                webPocket = view
            }
            "android" -> {
                webPocketKnown = true
                webPocket = null
            }
        }
    }

    private fun historyView(): JSONObject {
        val peersJ = JSONObject()
        peers.forEach { (k, p) ->
            if (p.edPk.none { it != 0.toByte() } || p.xPk.none { it != 0.toByte() }) return@forEach
            peersJ.put(
                k,
                JSONObject()
                    .put("mailboxId", p.mailboxId)
                    .put("edPk", Bytes.b64e(p.edPk))
                    .put("xPk", Bytes.b64e(p.xPk))
                    .put("displayName", p.displayName)
                    .put("accepted", p.accepted)
                    .put("verified", p.verified)
                    .put("blocked", p.blocked || blocked.contains(k))
                    .put("avatarId", p.avatarId ?: JSONObject.NULL),
            )
        }
        val threadsJ = JSONObject()
        val messagesJ = JSONObject()
        threads.forEach { (peerId, list) ->
            if (peerId.startsWith("g:")) return@forEach
            val threadId = dmThreadId(id.mailboxId, peerId)
            val last = list.lastOrNull()
            threadsJ.put(
                threadId,
                JSONObject()
                    .put("id", threadId)
                    .put("kind", "dm")
                    .put("peerMailboxId", peerId)
                    .put("title", peers[peerId]?.displayName ?: peerId.take(6))
                    .put("lastPreview", last?.text ?: "")
                    .put("lastAt", last?.at ?: 0L),
            )
            val disappear = peers[peerId]?.disappearSec
            if (disappear != null) threadsJ.getJSONObject(threadId).put("disappearingSec", disappear)
            val arr = org.json.JSONArray()
            list.forEach { m -> arr.put(sharedMessageJson(m, threadId, peerId)) }
            messagesJ.put(threadId, arr)
        }
        groups.forEach { (gid, g) ->
            val threadId = "g:$gid"
            val list = groupThreads[threadId].orEmpty()
            val last = list.lastOrNull()
            threadsJ.put(
                threadId,
                JSONObject()
                    .put("id", threadId)
                    .put("kind", "group")
                    .put("title", g.name)
                    .put("groupId", gid)
                    .put("lastPreview", last?.text ?: "Group created. Join is explicit.")
                    .put("lastAt", last?.at ?: 0L),
            )
            val arr = org.json.JSONArray()
            list.forEach { m -> arr.put(sharedMessageJson(m, threadId, "")) }
            messagesJ.put(threadId, arr)
        }
        val req = org.json.JSONArray()
        requests.forEach { m ->
            if (m.chamberInvite) return@forEach
            req.put(
                JSONObject()
                    .put("id", m.id)
                    .put("text", m.text)
                    .put("at", m.at)
                    .put("fromMe", m.fromMe)
                    .put("kind", m.kind)
                    .put("peerId", m.peerId)
                    .put("groupId", m.groupId ?: JSONObject.NULL)
                    .put("groupName", m.groupName ?: JSONObject.NULL)
                    .put("chamberInvite", m.chamberInvite),
            )
        }
        val view = JSONObject()
            .put("kind", "shared-v1")
            .put("displayName", id.displayName)
            .put("peers", peersJ)
            .put("threads", threadsJ)
            .put("messages", messagesJ)
            .put("requests", req)
            .put("blocked", org.json.JSONArray(blocked.toList()))
            .put("sessions", sharedSessions ?: JSONObject())
            .put("android", androidPocket())
        if (webPocketKnown) view.put("web", webOverlay())
        return view
    }

    private fun sharedMessageJson(m: ChatMsg, threadId: String, other: String) = JSONObject()
        .put("id", m.id)
        .put("threadId", threadId)
        .put("fromMe", m.fromMe)
        .put("text", m.text)
        .put("at", m.at)
        .put("kind", m.kind)
        .put("mime", m.mime ?: JSONObject.NULL)
        .put("name", m.name ?: JSONObject.NULL)
        .put("attachmentId", m.fileId ?: JSONObject.NULL)
        .put("fromMailbox", if (m.fromMe) id.mailboxId else m.peerId.ifBlank { other })
        .put("peerId", m.peerId.ifBlank { other })
        .put("viewOnce", m.viewOnce)
        .put("viewed", m.viewed)
        .put("deleted", m.deleted)
        .put("replyTo", m.replyTo ?: JSONObject.NULL)
        .put("replyText", m.replyText ?: JSONObject.NULL)
        .put("expiresAt", m.expiresAt ?: JSONObject.NULL)
        .put("reacted", m.reacted ?: JSONObject.NULL)
        .put("starred", m.starred && !m.deleted)
        .put("pollQuestion", m.pollQuestion ?: JSONObject.NULL)
        .put("pollOptions", if (m.pollOptionsJson.isNullOrBlank()) JSONObject.NULL else org.json.JSONArray(optionsOf(m.pollOptionsJson)))
        .put("pollClosed", m.pollClosed)
        .put("pollVotes", if (m.pollVotesJson.isNullOrBlank()) JSONObject.NULL else JSONObject(m.pollVotesJson))

    private fun webOverlay(): JSONObject {
        val web = if (webPocket == null) JSONObject() else JSONObject(webPocket.toString())
        web.put("groups", groupsJson())
        web.put("statuses", org.json.JSONArray().also { arr ->
            statuses.forEach { st ->
                arr.put(statusJson(st).apply { remove("bytesB64") })
            }
        })
        val settings = web.optJSONObject("settings") ?: JSONObject()
        if (avatarId != null) settings.put("avatarId", avatarId)
        web.put("settings", settings)
        return web
    }

    private fun androidPocket(): JSONObject {
        val ratJ = JSONObject()
        ratchets.forEach { (k, st) -> ratJ.put(k, Ratchet.dump(st)) }
        return JSONObject()
            .put("ratchets", ratJ)
            .put("spkSk", Bytes.b64e(spk.sk))
            .put("spkPk", Bytes.b64e(spk.pk))
            .put("opks", org.json.JSONArray().also { arr ->
                opks.forEach { (kid, k) ->
                    arr.put(JSONObject().put("id", kid).put("sk", Bytes.b64e(k.sk)).put("pk", Bytes.b64e(k.pk)))
                }
            })
            .put("outbox", org.json.JSONArray().also { arr -> outbox.forEach { arr.put(it) } })
    }

    private fun applyAndroidPocket(pocket: JSONObject) {
        if (pocket.has("spkSk") && pocket.has("spkPk")) {
            spk = XPair(Bytes.b64d(pocket.getString("spkSk")), Bytes.b64d(pocket.getString("spkPk")))
        }
        pocket.optJSONArray("opks")?.let { arr ->
            opks.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                opks += o.getInt("id") to XPair(Bytes.b64d(o.getString("sk")), Bytes.b64d(o.getString("pk")))
            }
        }
        pocket.optJSONArray("outbox")?.let { arr ->
            outbox.clear()
            for (i in 0 until arr.length()) outbox += arr.getJSONObject(i)
        }
    }

    private fun applyShared(view: JSONObject) {
        if (view.optString("kind") == "android") {
            if (view.has("displayName")) id = id.copy(displayName = view.getString("displayName"))
            rememberShared(view)
            loadLists(view)
            return
        }
        if (view.has("displayName")) id = id.copy(displayName = view.getString("displayName"))
        rememberShared(view)
        view.optJSONObject("web")?.let { web ->
            importGroups(web.optJSONObject("groups"), replace = false)
            importStatuses(web.optJSONArray("statuses"))
            val settings = web.optJSONObject("settings")
            if (settings != null && settings.has("avatarId") && !settings.isNull("avatarId")) {
                val remote = settings.optString("avatarId")
                if (remote != avatarId) {
                    avatarId = remote
                    avatarB64 = null
                }
            }
        }
        view.optJSONObject("peers")?.let { obj ->
            obj.keys().forEach { k ->
                val o = obj.getJSONObject(k)
                val prev = peers[k]
                val name = o.optString("displayName").ifBlank { prev?.displayName ?: k.take(6) }
                val nextAvatar = if (o.has("avatarId") && !o.isNull("avatarId")) o.optString("avatarId") else prev?.avatarId
                val edRaw = o.optString("edPk")
                val xRaw = o.optString("xPk")
                peers[k] = Peer(
                    o.optString("mailboxId", k),
                    if (edRaw.isNotBlank()) Bytes.b64d(edRaw) else prev?.edPk ?: ByteArray(32),
                    if (xRaw.isNotBlank()) Bytes.b64d(xRaw) else prev?.xPk ?: ByteArray(32),
                    name,
                    (prev?.accepted == true) || o.optBoolean("accepted"),
                    (prev?.verified == true) || o.optBoolean("verified"),
                    prev?.disappearSec,
                    prev?.safetyChanged == true,
                    (prev?.blocked == true) || o.optBoolean("blocked"),
                    o.optString("nickname").ifBlank { prev?.nickname },
                    if (o.has("mutedUntil") && !o.isNull("mutedUntil")) o.optLong("mutedUntil") else prev?.mutedUntil,
                    nextAvatar,
                    if (nextAvatar == prev?.avatarId) prev?.avatarB64 else null,
                )
            }
        }
        view.optJSONArray("blocked")?.let { arr ->
            for (i in 0 until arr.length()) blocked += arr.getString(i)
        }
        val mine = id.mailboxId
        val threadMeta = view.optJSONObject("threads")
        val messageMap = view.optJSONObject("messages")
        if (threadMeta != null) {
            threadMeta.keys().forEach { tid ->
                val meta = threadMeta.optJSONObject(tid)
                val asArray = threadMeta.optJSONArray(tid)
                if (tid.startsWith("ch:") || meta?.optString("kind") == "chamber") return@forEach
                if (tid.startsWith("g:") || meta?.optString("kind") == "group") {
                    val gid = meta?.optString("groupId").orEmpty().ifBlank { tid.removePrefix("g:") }
                    val title = meta?.optString("title").orEmpty().ifBlank { "Group" }
                    val prevGroup = groups[gid]
                    if (prevGroup == null) groups[gid] = GroupState(gid, title, emptyList(), 1)
                    else if (title != "Group" && prevGroup.name == "Group") groups[gid] = prevGroup.copy(name = title)
                    val incomingGroup = messageMap?.optJSONArray(tid) ?: org.json.JSONArray()
                    mergeBucket(groupThreads, "g:$gid", incomingGroup, gid)
                    return@forEach
                }
                val peerId = when {
                    meta != null && meta.optString("peerMailboxId").isNotBlank() -> meta.optString("peerMailboxId")
                    peerFromDm(tid, mine) != null -> peerFromDm(tid, mine)!!
                    asArray != null && tid.isNotBlank() -> tid
                    else -> ""
                }
                if (peerId.isBlank() || peerId == mine) return@forEach
                val incoming = when {
                    messageMap?.optJSONArray(tid) != null -> messageMap.getJSONArray(tid)
                    asArray != null -> asArray
                    else -> org.json.JSONArray()
                }
                mergeBucket(threads, peerId, incoming, peerId)
                val title = meta?.optString("title").orEmpty().ifBlank { peerId.take(6) }
                val prev = peers[peerId]
                if (prev == null) {
                    peers[peerId] = Peer(peerId, ByteArray(32), ByteArray(32), title, accepted = true)
                } else if (!prev.blocked) {
                    prev.accepted = true
                    if (prev.displayName.isBlank()) prev.displayName = title
                }
                if (meta != null && meta.has("disappearingSec") && !meta.isNull("disappearingSec")) {
                    val sec = meta.optInt("disappearingSec")
                    if (sec > 0) peers[peerId]?.disappearSec = sec
                }
            }
        }
        view.optJSONArray("requests")?.let { arr ->
            val have = requests.map { it.id }.toMutableSet()
            for (i in 0 until arr.length()) {
                val m = msgFrom(arr.getJSONObject(i))
                if (m.id !in have) requests += m
            }
        }
        if (mine.isBlank()) return
    }

    private fun groupsJson(): JSONObject {
        val obj = JSONObject()
        groups.forEach { (gid, g) ->
            val members = org.json.JSONArray()
            g.members.forEach { m ->
                members.put(JSONObject().put("mailboxId", m.mailboxId).put("edPk", m.edPk).put("role", m.role))
            }
            obj.put(gid, JSONObject().put("id", g.id).put("name", g.name).put("version", g.version).put("members", members))
        }
        return obj
    }

    private fun statusJson(st: StatusItem) = JSONObject()
        .put("id", st.id)
        .put("fromMailbox", st.fromMailbox)
        .put("fromName", st.fromName)
        .put("text", st.text ?: JSONObject.NULL)
        .put("at", st.at)
        .put("until", st.until)
        .put("mime", st.mime ?: JSONObject.NULL)
        .put("name", st.name ?: JSONObject.NULL)
        .put("attachmentId", st.attachmentId ?: JSONObject.NULL)
        .put("bytesB64", st.bytesB64 ?: JSONObject.NULL)

    private fun statusFrom(o: JSONObject) = StatusItem(
        o.optString("id"),
        o.optString("fromMailbox"),
        o.optString("fromName"),
        o.optString("text").ifBlank { null },
        o.optLong("at"),
        o.optLong("until"),
        o.optString("mime").ifBlank { null },
        o.optString("name").ifBlank { null },
        o.optString("attachmentId").ifBlank { null },
        if (o.isNull("bytesB64")) null else o.optString("bytesB64").ifBlank { null },
    )

    private fun importGroups(obj: JSONObject?, replace: Boolean) {
        if (obj == null) return
        if (replace) groups.clear()
        obj.keys().forEach { gid ->
            val o = obj.optJSONObject(gid) ?: return@forEach
            val members = mutableListOf<GroupMember>()
            o.optJSONArray("members")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    members += GroupMember(m.optString("mailboxId"), m.optString("edPk"), m.optString("role").ifBlank { "member" })
                }
            }
            val next = GroupState(o.optString("id", gid), o.optString("name").ifBlank { "Group" }, members, o.optInt("version", 1))
            val prev = groups[gid]
            if (prev == null || next.version >= prev.version) groups[gid] = next
        }
    }

    private fun importStatuses(arr: org.json.JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val st = statusFrom(arr.getJSONObject(i))
            if (st.id.isBlank() || statuses.any { it.id == st.id }) continue
            statuses.add(0, st)
        }
    }

    private fun mergeBucket(
        bucket: MutableMap<String, MutableList<ChatMsg>>,
        key: String,
        incoming: org.json.JSONArray,
        peerId: String,
    ) {
        val local = bucket.getOrPut(key) { mutableListOf() }
        val merged = mergeSharedMessages(
            local.map {
                SharedMsg(
                    it.id, it.text, it.at, it.fromMe, it.fileId, it.kind, it.viewOnce, it.replyTo, it.replyText,
                    it.mime, it.name, it.deleted, it.expiresAt, it.reacted, it.peerId.ifBlank { null },
                    it.starred, it.pollQuestion, optionsOf(it.pollOptionsJson), it.pollClosed, votesOf(it.pollVotesJson),
                )
            },
            List(incoming.length()) { i ->
                val o = incoming.getJSONObject(i)
                val att = o.optString("attachmentId").ifEmpty { o.optString("fileId") }
                SharedMsg(
                    o.optString("id"),
                    o.optString("text"),
                    o.optLong("at"),
                    o.optBoolean("fromMe"),
                    att.takeIf { it.isNotEmpty() },
                    o.optString("kind", "text"),
                    o.optBoolean("viewOnce"),
                    o.optString("replyTo").ifBlank { null },
                    o.optString("replyText").ifBlank { null },
                    o.optString("mime").ifBlank { null },
                    o.optString("name").ifBlank { null },
                    o.optBoolean("deleted"),
                    if (o.has("expiresAt") && !o.isNull("expiresAt")) o.optLong("expiresAt") else null,
                    o.optString("reacted").ifBlank { null },
                    o.optString("fromMailbox").ifBlank { o.optString("peerId").ifBlank { null } },
                    o.optBoolean("starred") && !o.optBoolean("deleted"),
                    o.optString("pollQuestion").ifBlank { null },
                    o.optJSONArray("pollOptions")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
                    o.optBoolean("pollClosed"),
                    o.optJSONObject("pollVotes")?.let { votes ->
                        val out = linkedMapOf<String, Int>()
                        votes.keys().forEach { out[it] = votes.getInt(it) }
                        out
                    } ?: emptyMap(),
                )
            },
        )
        val byId = local.associateBy { it.id }
        bucket[key] = merged.map { sm ->
            val prev = byId[sm.id]
            if (prev != null) {
                prev.copy(
                    text = if (sm.deleted) "" else sm.text.ifBlank { prev.text },
                    deleted = sm.deleted || prev.deleted,
                    viewOnce = sm.viewOnce || prev.viewOnce,
                    replyTo = sm.replyTo ?: prev.replyTo,
                    replyText = sm.replyText ?: prev.replyText,
                    mime = sm.mime ?: prev.mime,
                    name = sm.name ?: prev.name,
                    fileId = sm.attachmentId ?: prev.fileId,
                    kind = if (sm.kind.isBlank()) prev.kind else sm.kind,
                    expiresAt = sm.expiresAt ?: prev.expiresAt,
                    reacted = sm.reacted ?: prev.reacted,
                    peerId = if (key.startsWith("g:")) sm.fromMailbox ?: prev.peerId else prev.peerId.ifBlank { peerId },
                    starred = Stars.keep(prev.starred, sm.starred, sm.deleted || prev.deleted),
                    pollQuestion = sm.pollQuestion ?: prev.pollQuestion,
                    pollOptionsJson = optionsJson(if (sm.pollOptions.isNotEmpty()) sm.pollOptions else optionsOf(prev.pollOptionsJson)),
                    pollClosed = sm.pollClosed || prev.pollClosed,
                    pollVotesJson = votesJson(votesOf(prev.pollVotesJson) + sm.pollVotes),
                )
            } else {
                ChatMsg(
                    sm.id,
                    sm.fromMe,
                    if (sm.deleted) "" else sm.text,
                    sm.at,
                    if (key.startsWith("g:")) sm.fromMailbox.orEmpty() else peerId,
                    sm.kind.ifBlank { "text" },
                    sm.mime,
                    fileId = sm.attachmentId,
                    reacted = sm.reacted,
                    expiresAt = sm.expiresAt,
                    name = sm.name,
                    replyTo = sm.replyTo,
                    replyText = sm.replyText,
                    viewOnce = sm.viewOnce,
                    deleted = sm.deleted,
                    groupId = if (key.startsWith("g:")) key.removePrefix("g:") else null,
                    starred = sm.starred && !sm.deleted,
                    pollQuestion = sm.pollQuestion,
                    pollOptionsJson = optionsJson(sm.pollOptions),
                    pollClosed = sm.pollClosed,
                    pollVotesJson = votesJson(sm.pollVotes),
                )
            }
        }.toMutableList()
    }

    private fun uploadFiles(seedB64: String) {
        val key = Crypto.historyKey(Bytes.b64d(seedB64))
        fun push(fid: String, raw: String, mime: String, name: String) {
            if (!fid.matches(Regex("^[A-Za-z0-9_-]{8,80}$"))) return
            val nonce = Bytes.random(12)
            val ct = Crypto.aeadEncrypt(key, nonce, Bytes.b64d(raw))
            relay.putHistoryFile(
                token,
                fid,
                JSONObject(AccountRequests.file(Bytes.b64e(nonce), Bytes.b64e(ct), mime, name)),
            )
        }
        fun walk(list: MutableList<ChatMsg>) {
            list.forEachIndexed { index, m ->
                val raw = m.bytesB64 ?: return@forEachIndexed
                val fid = m.fileId ?: m.id
                push(fid, raw, m.mime ?: "application/octet-stream", m.name ?: "file")
                list[index] = m.copy(fileId = fid)
            }
        }
        threads.values.forEach { walk(it) }
        groupThreads.values.forEach { walk(it) }
        val mine = avatarId
        val mineBytes = avatarB64
        if (!mine.isNullOrBlank() && !mineBytes.isNullOrBlank()) push(mine, mineBytes, "image/jpeg", "profile.jpg")
        peers.values.forEach { p ->
            val fid = p.avatarId
            val raw = p.avatarB64
            if (!fid.isNullOrBlank() && !raw.isNullOrBlank()) push(fid, raw, "image/jpeg", "profile.jpg")
        }
        statuses.forEach { st ->
            val fid = st.attachmentId
            val raw = st.bytesB64
            if (!fid.isNullOrBlank() && !raw.isNullOrBlank()) push(fid, raw, st.mime ?: "application/octet-stream", st.name ?: "status")
        }
    }

    private fun fetchFileB64(fid: String): String? {
        if (!fid.matches(Regex("^[A-Za-z0-9_-]{8,80}$"))) return null
        val seedB64 = recoverySeed ?: return null
        return try {
            val row = relay.getHistoryFile(token, fid)
            val pt = Crypto.aeadDecrypt(
                Crypto.historyKey(Bytes.b64d(seedB64)),
                Bytes.b64d(row.getString("nonce_b64")),
                Bytes.b64d(row.getString("blob_b64")),
            )
            Bytes.b64e(pt)
        } catch (_: Exception) {
            null
        }
    }

    private fun hydrateFiles() {
        val mine = avatarId
        if (!mine.isNullOrBlank() && avatarB64 == null) avatarB64 = fetchFileB64(mine)
        peers.values.forEach { p ->
            val fid = p.avatarId
            if (!fid.isNullOrBlank() && p.avatarB64 == null) p.avatarB64 = fetchFileB64(fid)
        }
        for (i in statuses.indices) {
            val st = statuses[i]
            val fid = st.attachmentId ?: continue
            if (st.bytesB64 == null) statuses[i] = st.copy(bytesB64 = fetchFileB64(fid))
        }
    }

    fun openAttachment(peerId: String, msgId: String) {
        val seedB64 = recoverySeed ?: return
        val list = bucket(peerId) ?: return
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val m = list[i]
        if (m.bytesB64 != null || m.fileId.isNullOrBlank()) return
        val row = relay.getHistoryFile(token, m.fileId)
        val pt = Crypto.aeadDecrypt(
            Crypto.historyKey(Bytes.b64d(seedB64)),
            Bytes.b64d(row.getString("nonce_b64")),
            Bytes.b64d(row.getString("blob_b64")),
        )
        list[i] = m.copy(bytesB64 = Bytes.b64e(pt), mime = m.mime ?: row.optString("mime"), name = m.name ?: row.optString("name"))
    }

    private fun openHistory(seedB64: String, remote: JSONObject): JSONObject {
        val pt = Crypto.aeadDecrypt(
            Crypto.historyKey(Bytes.b64d(seedB64)),
            Bytes.b64d(remote.getString("nonce_b64")),
            Bytes.b64d(remote.getString("blob_b64")),
        )
        return JSONObject(String(pt, Charsets.UTF_8))
    }

    fun accept(mailboxId: String) {
        peers[mailboxId]?.accepted = true
        val moved = requests.filter {
            (it.peerId == mailboxId || it.peerId.isEmpty()) && it.groupId.isNullOrBlank() && !it.chamberInvite
        }
        requests.removeAll(moved.toSet())
        threads.getOrPut(mailboxId) { mutableListOf() }.addAll(moved.map { it.copy(peerId = mailboxId) })
        peers[mailboxId]?.let { shareProfileWith(it) }
    }

    private fun bucket(threadKey: String): MutableList<ChatMsg>? = when {
        threadKey.startsWith("g:") -> groupThreads[threadKey]
        threadKey.startsWith("ch:") -> chamberThreads[threadKey]
        else -> threads[threadKey]
    }

    fun openViewOnce(threadKey: String, msgId: String) {
        val list = bucket(threadKey) ?: return
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val m = list[i]
        if (!m.viewOnce || m.viewed || m.fromMe || m.deleted) return
        list[i] = m.copy(viewed = true, viewUntil = System.currentTimeMillis() + 8000)
    }

    fun hideForMe(threadKey: String, msgId: String) {
        val list = bucket(threadKey) ?: return
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        list[i] = list[i].copy(deleted = true, text = "", bytesB64 = null, starred = false)
    }

    fun setProfilePhoto(bytes: ByteArray) {
        if (bytes.size > 1_500_000) error("Profile photo is too large.")
        avatarId = java.util.UUID.randomUUID().toString()
        avatarB64 = Bytes.b64e(bytes)
        peers.values.filter { it.accepted && !it.blocked && !blocked.contains(it.mailboxId) }.forEach { shareProfileWith(it) }
    }

    fun clearProfilePhoto() {
        avatarId = ""
        avatarB64 = null
        peers.values.filter { it.accepted && !it.blocked && !blocked.contains(it.mailboxId) }.forEach { peer ->
            sendPayload(
                peer,
                JSONObject()
                    .put("kind", "ctrl")
                    .put("id", java.util.UUID.randomUUID().toString())
                    .put("op", "profile")
                    .put("data", JSONObject().put("clear", true)),
                intro = false,
            )
        }
    }

    private fun shareProfileWith(peer: Peer) {
        if (peer.blocked || blocked.contains(peer.mailboxId)) return
        val fid = avatarId ?: return
        if (fid.isBlank()) return
        val raw = avatarB64 ?: return
        sendPayload(
            peer,
            JSONObject()
                .put("kind", "ctrl")
                .put("id", java.util.UUID.randomUUID().toString())
                .put("op", "profile")
                .put("data", JSONObject().put("mime", "image/jpeg").put("bytesB64", raw)),
            intro = false,
        )
    }

    fun publishStatus(text: String, bytes: ByteArray? = null, mime: String? = null, name: String? = null) {
        val body = text.trim()
        if (body.isEmpty() && (bytes == null || bytes.isEmpty())) return
        if (bytes != null && bytes.size > 3_000_000) error("Keep status files under 3 MB.")
        val until = System.currentTimeMillis() + 86_400_000L
        val itemId = java.util.UUID.randomUUID().toString()
        val att = if (bytes == null || bytes.isEmpty()) null else java.util.UUID.randomUUID().toString()
        statuses.add(
            0,
            StatusItem(
                itemId,
                id.mailboxId,
                id.displayName,
                body.ifBlank { null },
                System.currentTimeMillis(),
                until,
                mime,
                name,
                att,
                bytes?.let { Bytes.b64e(it) },
            ),
        )
        val data = JSONObject().put("until", until)
        if (body.isNotEmpty()) data.put("text", body)
        if (bytes != null && bytes.isNotEmpty()) {
            data.put("mime", mime ?: "application/octet-stream").put("name", name ?: "file").put("bytesB64", Bytes.b64e(bytes))
        }
        val payload = JSONObject().put("kind", "ctrl").put("id", itemId).put("op", "status").put("data", data)
        peers.values.filter { it.accepted && !it.blocked && !blocked.contains(it.mailboxId) }.forEach { peer ->
            sendPayload(peer, payload, intro = false, ttlSeconds = 86400)
        }
    }

    fun createGroup(name: String, memberIds: List<String>): String {
        if (!ready) error("locked")
        val clean = name.trim()
        if (clean.isEmpty()) error("Name the group.")
        if (memberIds.size > 15) error("v1 groups stay small")
        val gid = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val members = mutableListOf(GroupMember(id.mailboxId, Bytes.b64e(id.edPk), "admin"))
        memberIds.distinct().forEach { mid ->
            val peer = peers[mid] ?: error("That person is not on this phone.")
            members += GroupMember(mid, Bytes.b64e(peer.edPk), "member")
        }
        groups[gid] = GroupState(gid, clean, members, 1)
        groupThreads.getOrPut("g:$gid") { mutableListOf() } += ChatMsg(
            java.util.UUID.randomUUID().toString(),
            true,
            "Group created. Join is explicit.",
            System.currentTimeMillis(),
            "",
            kind = "system",
        )
        memberIds.distinct().forEach { mid ->
            val peer = peers[mid] ?: return@forEach
            val payload = JSONObject()
                .put("kind", "intro")
                .put("id", java.util.UUID.randomUUID().toString())
                .put("text", "${id.displayName} invited you to “$clean”. Join is explicit — they cannot silent-add you.")
                .put("displayName", id.displayName)
                .put("groupId", gid)
                .put("groupName", clean)
            handle?.let { payload.put("handle", it) }
            sendPayload(peer, payload, intro = true)
        }
        return "g:$gid"
    }

    fun acceptGroup(fromMailbox: String, groupId: String, groupName: String) {
        val peer = peers[fromMailbox] ?: return
        peer.accepted = true
        groups[groupId] = GroupState(
            groupId,
            groupName.ifBlank { "Group" },
            listOf(
                GroupMember(id.mailboxId, Bytes.b64e(id.edPk), "member"),
                GroupMember(peer.mailboxId, Bytes.b64e(peer.edPk), "admin"),
            ),
            1,
        )
        groupThreads.getOrPut("g:$groupId") { mutableListOf() } += ChatMsg(
            java.util.UUID.randomUUID().toString(),
            true,
            "You joined. Each person joins on purpose.",
            System.currentTimeMillis(),
            "",
            kind = "system",
        )
        val held = requests.filter { it.groupId == groupId }
        requests.removeAll { it.groupId == groupId }
        groupThreads.getOrPut("g:$groupId") { mutableListOf() }.addAll(held)
        shareProfileWith(peer)
    }

    fun leaveGroup(groupId: String) {
        val group = groups[groupId] ?: return
        group.members.forEach { member ->
            if (member.mailboxId == id.mailboxId) return@forEach
            val peer = peers[member.mailboxId] ?: return@forEach
            if (peer.xPk.size != 32 || peer.xPk.all { it == 0.toByte() }) return@forEach
            sendPayload(
                peer,
                JSONObject()
                    .put("kind", "ctrl")
                    .put("id", java.util.UUID.randomUUID().toString())
                    .put("op", "leave-group")
                    .put("data", JSONObject().put("groupId", groupId)),
                intro = false,
            )
        }
        groups.remove(groupId)
        groupThreads.remove("g:$groupId")
    }

    fun startChamber(peer: Peer, days: Int = 7): String {
        if (!ready) error("locked")
        if (peer.blocked || blocked.contains(peer.mailboxId)) error("That person is blocked.")
        val ident = Crypto.generateIdentity("chamber")
        val chamberToken = registerIdentity(ident)
        val burnAt = System.currentTimeMillis() + days * 86400_000L
        val meta = ChamberMeta(
            ident.mailboxId,
            "Chamber · ${peer.displayName}",
            ident.mailboxId,
            peer.mailboxId,
            burnAt,
            System.currentTimeMillis(),
            true,
            chamberToken,
        )
        chambers[meta.id] = meta
        chamberThreads.getOrPut("ch:${meta.id}") { mutableListOf() } += ChatMsg(
            java.util.UUID.randomUUID().toString(),
            true,
            "Waiting for them to accept. Screenshots are not ours to delete.",
            System.currentTimeMillis(),
            "",
            kind = "system",
        )
        val payload = JSONObject()
            .put("kind", "intro")
            .put("id", java.util.UUID.randomUUID().toString())
            .put("text", "${id.displayName} wants a Chamber. Burn in $days days. Public Bleep cannot delete their screenshot.")
            .put("displayName", id.displayName)
            .put("chamber", true)
            .put("burnAt", burnAt)
            .put("ephCode", ident.mailboxId)
        handle?.let { payload.put("handle", it) }
        sendPayload(peer, payload, intro = true)
        return meta.id
    }

    fun acceptChamber(fromMailbox: String, burnAt: Long?) {
        val peer = peers[fromMailbox] ?: return
        peer.accepted = true
        val meta = ChamberMeta(
            fromMailbox,
            "Chamber · ${peer.displayName}",
            id.mailboxId,
            fromMailbox,
            burnAt ?: System.currentTimeMillis() + 7 * 86400_000L,
            System.currentTimeMillis(),
            true,
            null,
        )
        chambers[fromMailbox] = meta
        val moved = requests.filter { it.peerId == fromMailbox && it.chamberInvite }
        requests.removeAll { it.peerId == fromMailbox && it.chamberInvite }
        val list = chamberThreads.getOrPut("ch:$fromMailbox") { mutableListOf() }
        if (list.isEmpty()) {
            list += ChatMsg(
                java.util.UUID.randomUUID().toString(),
                true,
                "Both of you accepted. Screenshots are not ours to delete.",
                System.currentTimeMillis(),
                "",
                kind = "system",
            )
        }
        list.addAll(moved.map { it.copy(chamberInvite = false) })
        shareProfileWith(peer)
    }

    fun lockChamber(id: String) {
        chamberPins.remove(id)
        chambers[id]?.unlocked = false
    }

    fun unlockChamber(id: String, pin: String): Boolean {
        if (pin.length < 4 || chambers[id] == null) return false
        if (ready) {
            try {
                val row = relay.getHistoryExtra(token, id)
                val pt = Crypto.aeadDecrypt(
                    Crypto.kdf(Bytes.utf8(pin), Bytes.utf8(id), "bleep-chamber-v1"),
                    Bytes.b64d(row.getString("nonce_b64")),
                    Bytes.b64d(row.getString("blob_b64")),
                )
                val data = JSONObject(String(pt, Charsets.UTF_8))
                data.optJSONObject("meta")?.let { o ->
                    chambers[id]?.apply {
                        title = o.optString("title", title)
                        burnAt = if (o.has("burnAt")) o.optLong("burnAt") else burnAt
                        unlocked = true
                    }
                }
                data.optJSONArray("messages")?.let { arr ->
                    chamberThreads["ch:$id"] = MutableList(arr.length()) { i -> msgFrom(arr.getJSONObject(i)) }
                }
            } catch (e: Exception) {
                if (e.message?.contains("404") != true) return false
            }
        }
        chamberPins[id] = pin
        chambers[id]?.unlocked = true
        return true
    }

    fun burnChamber(id: String) {
        val ch = chambers[id] ?: return
        val tok = ch.relayToken
        if (!tok.isNullOrBlank() && ch.mailboxId != this.id.mailboxId) {
            try { relay.unlink(ch.mailboxId, tok) } catch (_: Exception) {}
        }
        chamberPins.remove(id)
        chambers.remove(id)
        chamberThreads.remove("ch:$id")
    }

    fun sendChamberText(chamberId: String, text: String, replyTo: String? = null, replyText: String? = null, viewOnce: Boolean = false) {
        val ch = chambers[chamberId] ?: error("no chamber")
        if (!ch.unlocked) error("This chamber is locked.")
        val peer = ch.peerMailboxId?.let { peers[it] } ?: error("no peer")
        if (peer.safetyChanged) error("Safety number changed. This is a new key. Verify it before you send.")
        val msgId = java.util.UUID.randomUUID().toString()
        val exp = System.currentTimeMillis() + 86400_000L
        val payload = JSONObject()
            .put("kind", "text")
            .put("id", msgId)
            .put("text", text)
            .put("chamber", true)
            .put("expireSec", 86400)
        if (!replyTo.isNullOrBlank()) payload.put("replyTo", replyTo).put("replyText", replyText ?: "")
        if (viewOnce) payload.put("viewOnce", true)
        sendPayload(peer, payload, intro = false)
        chamberThreads.getOrPut("ch:$chamberId") { mutableListOf() } += ChatMsg(
            msgId, true, text, System.currentTimeMillis(), "", expiresAt = exp, replyTo = replyTo, replyText = replyText, viewOnce = viewOnce,
        )
    }

    fun sendChamberMedia(
        chamberId: String,
        kind: String,
        bytes: ByteArray,
        mime: String,
        name: String,
        replyTo: String? = null,
        replyText: String? = null,
        viewOnce: Boolean = false,
    ) {
        val ch = chambers[chamberId] ?: error("no chamber")
        if (!ch.unlocked) error("This chamber is locked.")
        val peer = ch.peerMailboxId?.let { peers[it] } ?: error("no peer")
        if (peer.safetyChanged) error("Safety number changed. This is a new key. Verify it before you send.")
        val msgId = java.util.UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("kind", kind)
            .put("id", msgId)
            .put("text", "")
            .put("mime", mime)
            .put("name", name)
            .put("bytesB64", Bytes.b64e(bytes))
            .put("chamber", true)
            .put("expireSec", 86400)
        if (!replyTo.isNullOrBlank()) payload.put("replyTo", replyTo).put("replyText", replyText ?: "")
        if (viewOnce) payload.put("viewOnce", true)
        sendPayload(peer, payload, intro = false)
        chamberThreads.getOrPut("ch:$chamberId") { mutableListOf() } += ChatMsg(
            msgId,
            true,
            if (kind == "voice") "Voice note" else if (kind == "photo") "Photo" else name,
            System.currentTimeMillis(),
            "",
            kind,
            mime,
            Bytes.b64e(bytes),
            name = name,
            fileId = msgId,
            expiresAt = System.currentTimeMillis() + 86400_000L,
            replyTo = replyTo,
            replyText = replyText,
            viewOnce = viewOnce,
        )
    }

    private fun registerIdentity(ident: Identity): String {
        val spk = Crypto.freshX()
        val body = JSONObject()
            .put("mailbox_id", ident.mailboxId)
            .put("identity_ed25519_b64", Bytes.b64e(ident.edPk))
            .put("identity_x25519_b64", Bytes.b64e(ident.xPk))
            .put(
                "signed_prekey",
                JSONObject()
                    .put("key_id", 1)
                    .put("pub_b64", Bytes.b64e(spk.pk))
                    .put("sig_b64", Bytes.b64e(Crypto.sign(ident.edSk, Crypto.spkMessage(1, spk.pk)))),
            )
            .put(
                "one_time_prekeys",
                org.json.JSONArray().also { arr ->
                    repeat(8) { i ->
                        val k = Crypto.freshX()
                        arr.put(JSONObject().put("key_id", i + 1).put("pub_b64", Bytes.b64e(k.pk)))
                    }
                },
            )
            .put("registration_sig_b64", Bytes.b64e(Crypto.sign(ident.edSk, Crypto.registrationMessage(ident.mailboxId, ident.xPk))))
        attachMlkem(body, ident)
        return relay.register(body).getString("token")
    }

    private fun chambersJson(): JSONObject {
        val obj = JSONObject()
        chambers.forEach { (id, ch) ->
            obj.put(
                id,
                JSONObject()
                    .put("id", ch.id)
                    .put("title", ch.title)
                    .put("mailboxId", ch.mailboxId)
                    .put("peerMailboxId", ch.peerMailboxId ?: JSONObject.NULL)
                    .put("burnAt", ch.burnAt)
                    .put("createdAt", ch.createdAt)
                    .put("unlocked", ch.unlocked)
                    .put("relayToken", ch.relayToken ?: JSONObject.NULL),
            )
        }
        return obj
    }

    private fun importChambers(obj: JSONObject) {
        obj.keys().forEach { id ->
            val o = obj.optJSONObject(id) ?: return@forEach
            chambers[id] = ChamberMeta(
                o.optString("id", id),
                o.optString("title", "Chamber"),
                o.optString("mailboxId", id),
                o.optString("peerMailboxId").ifBlank { null },
                o.optLong("burnAt"),
                o.optLong("createdAt"),
                false,
                if (o.isNull("relayToken")) null else o.optString("relayToken").ifBlank { null },
            )
        }
    }

    private fun pushChambers() {
        if (!ready) return
        for ((id, pin) in chamberPins) {
            if (pin.length < 4 || chambers[id] == null) continue
            val ch = chambers[id] ?: continue
            val meta = JSONObject()
                .put("id", ch.id)
                .put("title", ch.title)
                .put("mailboxId", ch.mailboxId)
                .put("peerMailboxId", ch.peerMailboxId ?: JSONObject.NULL)
                .put("burnAt", ch.burnAt)
                .put("createdAt", ch.createdAt)
                .put("unlocked", true)
            val messages = org.json.JSONArray()
            chamberThreads["ch:$id"].orEmpty().forEach { m ->
                messages.put(msgJson(m).apply { remove("bytesB64") })
            }
            val body = JSONObject().put("meta", meta).put("messages", messages)
            val nonce = Bytes.random(12)
            val ct = Crypto.aeadEncrypt(Crypto.kdf(Bytes.utf8(pin), Bytes.utf8(id), "bleep-chamber-v1"), nonce, Bytes.utf8(body.toString()))
            val generation = (extraGen[id] ?: 0) + 1
            try {
                val res = relay.putHistoryExtra(
                    token,
                    id,
                    JSONObject()
                        .put("generation", generation)
                        .put("nonce_b64", Bytes.b64e(nonce))
                        .put("blob_b64", Bytes.b64e(ct)),
                )
                extraGen[id] = res.optInt("generation", generation)
            } catch (e: Exception) {
                if (e.message?.contains("409") == true) {
                    try {
                        extraGen[id] = relay.getHistoryExtra(token, id).optInt("generation", generation)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun sendGroupText(groupId: String, text: String, replyTo: String? = null, replyText: String? = null, viewOnce: Boolean = false) {
        val dest = groupDestinations(groupId)
        if (dest.isEmpty()) error("no peer")
        if (dest.any { it.safetyChanged }) error("Safety number changed. This is a new key. Verify it before you send.")
        val msgId = java.util.UUID.randomUUID().toString()
        val payload = JSONObject().put("kind", "text").put("id", msgId).put("text", text).put("groupId", groupId)
        if (!replyTo.isNullOrBlank()) payload.put("replyTo", replyTo).put("replyText", replyText ?: "")
        if (viewOnce) payload.put("viewOnce", true)
        dest.forEach { sendPayload(it, payload, intro = false) }
        groupThreads.getOrPut("g:$groupId") { mutableListOf() } += ChatMsg(
            msgId,
            true,
            text,
            System.currentTimeMillis(),
            "",
            replyTo = replyTo,
            replyText = replyText,
            viewOnce = viewOnce,
        )
    }

    fun sendGroupPoll(groupId: String, question: String, options: List<String>) {
        val clean = options.map { it.trim() }.filter { it.isNotEmpty() }.take(4).map { it.take(40) }
        val asked = question.trim().take(120)
        if (asked.isEmpty() || clean.size < 2) error("A poll needs a question and at least two choices.")
        val dest = groupDestinations(groupId)
        if (dest.isEmpty()) error("no peer")
        val msgId = java.util.UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("kind", "poll")
            .put("id", msgId)
            .put("question", asked)
            .put("options", org.json.JSONArray(clean))
            .put("groupId", groupId)
        dest.forEach { sendPayload(it, payload, intro = false) }
        groupThreads.getOrPut("g:$groupId") { mutableListOf() } += ChatMsg(
            msgId,
            true,
            asked,
            System.currentTimeMillis(),
            id.mailboxId,
            kind = "poll",
            pollQuestion = asked,
            pollOptionsJson = optionsJson(clean),
            groupId = groupId,
        )
    }

    fun voteGroupPoll(groupId: String, pollId: String, option: Int) {
        val list = groupThreads["g:$groupId"] ?: return
        val i = list.indexOfFirst { it.id == pollId }
        if (i < 0) return
        val row = list[i]
        val members = groups[groupId]?.members?.map { it.mailboxId }?.toSet() ?: return
        val next = Polls.applyVote(votesOf(row.pollVotesJson), id.mailboxId, option, optionsOf(row.pollOptionsJson).size, members, row.pollClosed) ?: return
        list[i] = row.copy(pollVotesJson = votesJson(next))
        val payload = JSONObject()
            .put("kind", "vote")
            .put("id", java.util.UUID.randomUUID().toString())
            .put("pollId", pollId)
            .put("option", option)
            .put("groupId", groupId)
        groupDestinations(groupId).forEach { sendPayload(it, payload, intro = false) }
    }

    fun closeGroupPoll(groupId: String, pollId: String) {
        val list = groupThreads["g:$groupId"] ?: return
        val i = list.indexOfFirst { it.id == pollId }
        if (i < 0) return
        val row = list[i]
        val author = row.peerId.ifBlank { if (row.fromMe) id.mailboxId else "" }
        if (id.mailboxId != author) return
        list[i] = row.copy(pollClosed = true)
        val payload = JSONObject()
            .put("kind", "poll-close")
            .put("id", java.util.UUID.randomUUID().toString())
            .put("pollId", pollId)
            .put("groupId", groupId)
        groupDestinations(groupId).forEach { sendPayload(it, payload, intro = false) }
    }

    fun sendGroupMedia(
        groupId: String,
        kind: String,
        bytes: ByteArray,
        mime: String,
        name: String,
        replyTo: String? = null,
        replyText: String? = null,
        viewOnce: Boolean = false,
    ) {
        val dest = groupDestinations(groupId)
        if (dest.isEmpty()) error("no peer")
        if (dest.any { it.safetyChanged }) error("Safety number changed. This is a new key. Verify it before you send.")
        val msgId = java.util.UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("kind", kind)
            .put("id", msgId)
            .put("text", "")
            .put("mime", mime)
            .put("name", name)
            .put("bytesB64", Bytes.b64e(bytes))
            .put("groupId", groupId)
        if (!replyTo.isNullOrBlank()) payload.put("replyTo", replyTo).put("replyText", replyText ?: "")
        if (viewOnce) payload.put("viewOnce", true)
        dest.forEach { sendPayload(it, payload, intro = false) }
        groupThreads.getOrPut("g:$groupId") { mutableListOf() } += ChatMsg(
            msgId,
            true,
            if (kind == "voice") "Voice note" else if (kind == "photo") "Photo" else name,
            System.currentTimeMillis(),
            "",
            kind,
            mime,
            Bytes.b64e(bytes),
            name = name,
            fileId = msgId,
            replyTo = replyTo,
            replyText = replyText,
            viewOnce = viewOnce,
        )
    }

    private fun groupDestinations(groupId: String): List<Peer> {
        val g = groups[groupId] ?: return emptyList()
        return g.members.mapNotNull { m -> if (m.mailboxId == id.mailboxId) null else peers[m.mailboxId] }
    }

    private fun beginSend(peer: Peer) {
        if (peer.xPk.size != 32 || peer.xPk.all { it == 0.toByte() }) {
            error("This chat is on this phone, but the key has not arrived yet.")
        }
        synchronized(ioLock) {
            for (attempt in 0..1) {
                try {
                    pullHistory()
                } catch (e: Exception) {
                    if (e is Erased) throw e
                }
                adoptShared(peer.mailboxId)
                try {
                    pushHistory(force = true, rethrowConflict = true)
                    return
                } catch (e: Exception) {
                    if (e is Erased) throw e
                    if (e.message?.contains("409") == true) {
                        if (attempt == 1) error("This chat is open on the other device. Wait, then send again.")
                        continue
                    }
                    return
                }
            }
        }
    }

    private fun finishSend(peerId: String) {
        synchronized(ioLock) {
            publishSession(peerId)
            sessionDirty = false
            try {
                pushHistory(force = true, rethrowConflict = true)
            } catch (e: Exception) {
                if (e is Erased) throw e
                if (e.message?.contains("409") == true) {
                    try {
                        pullHistory()
                    } catch (err: Exception) {
                        if (err is Erased) throw err
                    }
                }
            }
        }
    }

    private fun adoptShared(peerId: String) {
        val dumped = sharedSessions?.optJSONObject(peerId) ?: return
        if (!dumped.has("dhsSk")) return
        ratchets[peerId] = Ratchet.load(dumped)
        pendingPk.remove(peerId)
        pendingOpk.remove(peerId)
        ephPk.remove(peerId)
        pendingPq.remove(peerId)
    }

    private fun publishSession(peerId: String) {
        val st = ratchets[peerId] ?: return
        if (sharedSessions == null) sharedSessions = JSONObject()
        sharedSessions!!.put(peerId, Ratchet.dump(st))
    }

    private fun rememberShared(view: JSONObject) {
        if (sharedSessions == null) sharedSessions = JSONObject()
        val dest = sharedSessions!!
        fun putDump(id: String, row: JSONObject?) {
            if (row != null && row.has("dhsSk")) dest.put(id, row)
        }
        view.optJSONObject("sessions")?.let { top ->
            top.keys().forEach { k -> putDump(k, top.optJSONObject(k)) }
        }
        view.optJSONObject("android")?.optJSONObject("ratchets")?.let { rats ->
            rats.keys().forEach { k -> if (!dest.has(k)) putDump(k, rats.optJSONObject(k)) }
        }
        view.optJSONObject("ratchets")?.let { rats ->
            rats.keys().forEach { k -> if (!dest.has(k)) putDump(k, rats.optJSONObject(k)) }
        }
        view.optJSONObject("web")?.optJSONObject("sessions")?.let { sessions ->
            sessions.keys().forEach { k ->
                if (!dest.has(k)) putDump(k, sessions.optJSONObject(k)?.optJSONObject("ratchet"))
            }
        }
    }

    private fun ensureAlice(peer: Peer) {
        if (ratchets[peer.mailboxId] != null) return
        val bundle = relay.prekey(peer.mailboxId, token)
        val eph = Crypto.freshX()
        val bobSpk = Bytes.b64d(bundle.getJSONObject("signed_prekey").getString("pub_b64"))
        val opkRow = bundle.optJSONObject("one_time_prekey")
        val opk = opkRow?.let { Bytes.b64d(it.getString("pub_b64")) }
        var sk = Ratchet.x3dhAlice(id.xSk, eph.sk, peer.xPk, bobSpk, opk)
        val mlkem = bundle.optString("identity_mlkem_b64")
        if (mlkem.isNotBlank() && id.pqSeed.size == 64) {
            val enc = Crypto.pqEncaps(Bytes.b64d(mlkem))
            sk = Ratchet.hybridRoot(sk, enc.second)
            pendingPq[peer.mailboxId] = enc.first
        }
        ratchets[peer.mailboxId] = Ratchet.initAlice(sk, bobSpk)
        ephPk[peer.mailboxId] = eph.pk
        if (opkRow != null) pendingOpk[peer.mailboxId] = opkRow.getInt("key_id")
        pendingPk += peer.mailboxId
    }

    private fun clearSession(peerId: String) {
        ratchets.remove(peerId)
        pendingPk.remove(peerId)
        pendingOpk.remove(peerId)
        ephPk.remove(peerId)
        pendingPq.remove(peerId)
        sharedSessions?.remove(peerId)
    }

    private fun stampPrekey(unsigned: JSONObject, peerId: String, isPk: Boolean) {
        ephPk.remove(peerId)?.let { unsigned.put("ek", Bytes.b64e(it)) }
        if (!isPk) return
        pendingOpk.remove(peerId)?.let { unsigned.put("used_opk", it) }
        pendingPq.remove(peerId)?.let { unsigned.put("pq", Bytes.b64e(it)) }
    }
}
