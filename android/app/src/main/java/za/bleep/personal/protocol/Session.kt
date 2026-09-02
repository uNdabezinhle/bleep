package za.bleep.personal.protocol

import org.json.JSONObject
import za.bleep.personal.net.Relay

data class Peer(
    val mailboxId: String,
    val edPk: ByteArray,
    val xPk: ByteArray,
    var displayName: String,
    var accepted: Boolean = false,
    var verified: Boolean = false,
    var disappearSec: Int? = null,
    var safetyChanged: Boolean = false,
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
)

class Session(private val relay: Relay) {
    lateinit var id: Identity
    lateinit var token: String
    val ready: Boolean get() = this::token.isInitialized

    fun relayBase(url: String) {
        relay.base = url.trim().trimEnd('/')
    }
    lateinit var spk: XPair
    val opks = mutableListOf<Pair<Int, XPair>>()
    val peers = mutableMapOf<String, Peer>()
    val ratchets = mutableMapOf<String, RatchetState>()
    val ephPk = mutableMapOf<String, ByteArray>()
    val pendingPk = mutableSetOf<String>()
    val threads = mutableMapOf<String, MutableList<ChatMsg>>()
    val requests = mutableListOf<ChatMsg>()
    val outbox = mutableListOf<JSONObject>()
    var lastIngest = 0

    fun create(displayName: String) {
        id = Crypto.generateIdentity(displayName)
        spk = Crypto.freshX()
        repeat(8) { i -> opks += (i + 1) to Crypto.freshX() }
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
        val res = relay.register(body)
        token = res.getString("token")
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
                    .put("disappearSec", p.disappearSec ?: JSONObject.NULL),
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
            .put("spkSk", Bytes.b64e(spk.sk))
            .put("spkPk", Bytes.b64e(spk.pk))
            .put("peers", peersJ)
            .put("threads", threadsJ)
            .put("ratchets", ratJ)
            .put("requests", org.json.JSONArray().also { arr -> requests.forEach { arr.put(msgJson(it)) } })
            .put("outbox", org.json.JSONArray().also { arr -> outbox.forEach { arr.put(it) } })
    }

    fun restore(snap: JSONObject) {
        id = Identity(
            snap.getString("mailboxId"),
            Bytes.b64d(snap.getString("edSk")),
            Bytes.b64d(snap.getString("edPk")),
            Bytes.b64d(snap.getString("xSk")),
            Bytes.b64d(snap.getString("xPk")),
            snap.getString("displayName"),
        )
        spk = XPair(Bytes.b64d(snap.getString("spkSk")), Bytes.b64d(snap.getString("spkPk")))
        peers.clear(); threads.clear(); ratchets.clear(); requests.clear()
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
            )
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
        val ch = relay.challenge()
        val sig = Crypto.sign(id.edSk, Crypto.authMessage(id.mailboxId, ch.getString("nonce")))
        val tok = relay.token(
            JSONObject()
                .put("mailbox_id", id.mailboxId)
                .put("nonce", ch.getString("nonce"))
                .put("signature_b64", Bytes.b64e(sig)),
        )
        token = tok.getString("token")
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
    )

    fun addByCode(code: String): Peer {
        val (mid, ed, x) = Crypto.parseCode(code)
        val p = Peer(mid, ed, x, mid.take(6))
        peers[mid] = p
        return p
    }

    fun sendText(peer: Peer, text: String) {
        if (peer.safetyChanged) error("Safety number changed. This is a new key. Verify it before you send.")
        ensureAlice(peer)
        val st = ratchets[peer.mailboxId]!!
        val exp = peer.disappearSec?.let { System.currentTimeMillis() + it * 1000L }
        val payload = JSONObject().put("kind", "text").put("id", java.util.UUID.randomUUID().toString()).put("text", text)
        if (peer.disappearSec != null) payload.put("expireSec", peer.disappearSec)
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
        ephPk.remove(peer.mailboxId)?.let { unsigned.put("ek", Bytes.b64e(it)) }
        val sig = Crypto.sign(id.edSk, Bytes.utf8(Canonical.of(unsigned)))
        unsigned.put("sig", Bytes.b64e(sig))
        val blob = Seal.outer(peer.xPk, Bytes.utf8(unsigned.toString()))
        enqueueDrop(peer.mailboxId, Bytes.b64e(blob))
        val list = threads.getOrPut(peer.mailboxId) { mutableListOf() }
        list += ChatMsg(payload.getString("id"), true, text, System.currentTimeMillis(), peer.mailboxId, expiresAt = exp)
    }

    fun sendReact(peer: Peer, targetId: String, emoji: String = "👍") {
        sendPayload(peer, JSONObject().put("kind", "react").put("id", java.util.UUID.randomUUID().toString()).put("target", targetId).put("emoji", emoji), intro = false)
        val list = threads[peer.mailboxId] ?: return
        val i = list.indexOfFirst { it.id == targetId }
        if (i >= 0) list[i] = list[i].copy(reacted = emoji)
    }

    fun sweep() {
        val now = System.currentTimeMillis()
        threads.values.forEach { list ->
            list.replaceAll { m ->
                if (m.expiresAt != null && m.expiresAt < now) m.copy(text = "", kind = "system") else m
            }
        }
    }

    fun sendMedia(peer: Peer, kind: String, bytes: ByteArray, mime: String, name: String) {
        if (peer.safetyChanged) error("Safety number changed. This is a new key. Verify it before you send.")
        val id = java.util.UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("kind", kind)
            .put("id", id)
            .put("text", "")
            .put("mime", mime)
            .put("name", name)
            .put("bytesB64", Bytes.b64e(bytes))
        sendPayload(peer, payload, intro = false)
        threads.getOrPut(peer.mailboxId) { mutableListOf() } +=
            ChatMsg(id, true, if (kind == "voice") "Voice note" else "Photo", System.currentTimeMillis(), peer.mailboxId, kind, mime, Bytes.b64e(bytes))
    }

    fun sendIntro(peer: Peer, hello: String) {
        sendPayload(peer, JSONObject().put("kind", "intro").put("id", java.util.UUID.randomUUID().toString()).put("text", hello).put("displayName", id.displayName), intro = true)
    }

    private fun sendPayload(peer: Peer, payload: JSONObject, intro: Boolean) {
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
        ephPk.remove(peer.mailboxId)?.let { unsigned.put("ek", Bytes.b64e(it)) }
        unsigned.put("sig", Bytes.b64e(Crypto.sign(id.edSk, Bytes.utf8(Canonical.of(unsigned)))))
        enqueueDrop(peer.mailboxId, Bytes.b64e(Seal.outer(peer.xPk, Bytes.utf8(unsigned.toString()))))
    }

    private fun enqueueDrop(dest: String, blobB64: String) {
        try {
            relay.drop(token, dest, blobB64)
        } catch (_: Exception) {
            outbox += JSONObject().put("dest", dest).put("blob", blobB64)
        }
    }

    fun flushOutbox() {
        val left = mutableListOf<JSONObject>()
        for (q in outbox) {
            try {
                relay.drop(token, q.getString("dest"), q.getString("blob"))
            } catch (_: Exception) {
                left += q
            }
        }
        outbox.clear()
        outbox.addAll(left)
    }

    fun poll() {
        lastIngest = 0
        flushOutbox()
        val items = relay.fetch(token)
        for (i in 0 until items.length()) {
            val blob = items.getJSONObject(i).getString("blob_b64")
            ingest(blob)
        }
        sweep()
    }

    private fun ingest(blobB64: String) {
        val innerBytes = try {
            Seal.openOuter(id.xSk, Bytes.b64d(blobB64))
        } catch (_: Exception) {
            return
        }
        val inner = JSONObject(Bytes.fromUtf8(innerBytes))
        val rest = JSONObject(inner.toString()).apply { remove("sig") }
        if (!Crypto.verify(Bytes.b64d(inner.getString("sid")), Bytes.utf8(Canonical.of(rest)), Bytes.b64d(inner.getString("sig")))) return
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
        if (st == null) {
            if (!inner.has("ek")) return
            val sk = Ratchet.x3dhBob(id.xSk, spk.sk, fromX, Bytes.b64d(inner.getString("ek")), null)
            st = Ratchet.initBob(sk, spk.sk, spk.pk)
            ratchets[mid] = st
        }
        val h = inner.getJSONObject("h")
        val header = Header(h.getString("dh"), h.getInt("n"), h.getInt("pn"))
        val pt = JSONObject(Bytes.fromUtf8(Ratchet.decrypt(st, header, Bytes.b64d(inner.getString("ct")))))
        val kind = pt.optString("kind")
        if (kind == "react") {
            val target = pt.optString("target")
            val emoji = pt.optString("emoji")
            threads[mid]?.let { list ->
                val i = list.indexOfFirst { it.id == target }
                if (i >= 0) list[i] = list[i].copy(reacted = emoji)
            }
            return
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
        val msg = ChatMsg(pt.optString("id"), false, label, System.currentTimeMillis(), mid, msgKind, pt.optString("mime").ifBlank { null }, bytesB64)
        if (!peer.accepted || kind == "intro") {
            requests += msg
            lastIngest++
            if (kind == "intro" && pt.has("displayName")) peer.displayName = pt.getString("displayName")
            return
        }
        threads.getOrPut(mid) { mutableListOf() } += msg
        lastIngest++
    }

    fun verifyPeer(peer: Peer) {
        peer.verified = true
        peer.safetyChanged = false
    }

    fun unlink() {
        if (ready) relay.unlink(id.mailboxId, token)
    }

    fun accept(mailboxId: String) {
        peers[mailboxId]?.accepted = true
        val moved = requests.filter { it.peerId == mailboxId || it.peerId.isEmpty() }
        requests.removeAll(moved.toSet())
        threads.getOrPut(mailboxId) { mutableListOf() }.addAll(moved.map { it.copy(peerId = mailboxId) })
    }

    private fun ensureAlice(peer: Peer) {
        if (ratchets[peer.mailboxId] != null) return
        val bundle = relay.prekey(peer.mailboxId, token)
        val eph = Crypto.freshX()
        val bobSpk = Bytes.b64d(bundle.getJSONObject("signed_prekey").getString("pub_b64"))
        val opk = bundle.optJSONObject("one_time_prekey")?.let { Bytes.b64d(it.getString("pub_b64")) }
        val sk = Ratchet.x3dhAlice(id.xSk, eph.sk, peer.xPk, bobSpk, opk)
        ratchets[peer.mailboxId] = Ratchet.initAlice(sk, bobSpk)
        ephPk[peer.mailboxId] = eph.pk
        pendingPk += peer.mailboxId
    }
}
