package za.bleep.personal.protocol

import org.json.JSONObject
import org.json.JSONObject.NULL as JsonNull

data class Header(val dh: String, val n: Int, val pn: Int) {
    fun json(): JSONObject = JSONObject().put("dh", dh).put("n", n).put("pn", pn)
    fun aad(): String = """{"dh":"$dh","n":$n,"pn":$pn}"""
}

class RatchetState(
    var dhsSk: ByteArray,
    var dhsPk: ByteArray,
    var dhr: ByteArray?,
    var rk: ByteArray,
    var cks: ByteArray?,
    var ckr: ByteArray?,
    var ns: Int = 0,
    var nr: Int = 0,
    var pn: Int = 0,
    val skipped: MutableMap<String, String> = mutableMapOf(),
)

object Ratchet {
    private val ZERO = ByteArray(32)

    private fun kdfRk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Crypto.kdf(dhOut, rk, "bleep-rk-v1", 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }

    private fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Crypto.kdf(ck, ZERO, "bleep-ck-v1", 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }

    fun initAlice(sk: ByteArray, bobDhPk: ByteArray): RatchetState {
        val dhs = Crypto.freshX()
        val (rk, ck) = kdfRk(sk, Crypto.dh(dhs.sk, bobDhPk))
        return RatchetState(dhs.sk, dhs.pk, bobDhPk, rk, ck, null)
    }

    fun initBob(sk: ByteArray, bobDhSk: ByteArray, bobDhPk: ByteArray): RatchetState =
        RatchetState(bobDhSk, bobDhPk, null, sk, null, null)

    fun x3dhAlice(aliceXSk: ByteArray, ephSk: ByteArray, bobIdX: ByteArray, bobSpk: ByteArray, bobOpk: ByteArray?): ByteArray {
        val dh1 = Crypto.dh(aliceXSk, bobSpk)
        val dh2 = Crypto.dh(ephSk, bobIdX)
        val dh3 = Crypto.dh(ephSk, bobSpk)
        val parts = if (bobOpk != null) Bytes.concat(dh1, dh2, dh3, Crypto.dh(ephSk, bobOpk)) else Bytes.concat(dh1, dh2, dh3)
        return Crypto.kdf(parts, ZERO, "bleep-x3dh-v1", 32)
    }

    fun x3dhBob(bobXSk: ByteArray, bobSpkSk: ByteArray, aliceIdX: ByteArray, aliceEph: ByteArray, bobOpkSk: ByteArray?): ByteArray {
        val dh1 = Crypto.dh(bobSpkSk, aliceIdX)
        val dh2 = Crypto.dh(bobXSk, aliceEph)
        val dh3 = Crypto.dh(bobSpkSk, aliceEph)
        val parts = if (bobOpkSk != null) Bytes.concat(dh1, dh2, dh3, Crypto.dh(bobOpkSk, aliceEph)) else Bytes.concat(dh1, dh2, dh3)
        return Crypto.kdf(parts, ZERO, "bleep-x3dh-v1", 32)
    }

    fun encrypt(state: RatchetState, plaintext: ByteArray): Pair<Header, ByteArray> {
        val cks = state.cks ?: error("sending chain not ready")
        val (nck, mk) = kdfCk(cks)
        state.cks = nck
        val header = Header(Bytes.b64e(state.dhsPk), state.ns, state.pn)
        state.ns += 1
        val nonce = Bytes.random(12)
        val ad = Bytes.utf8(header.aad())
        val ct = Crypto.aeadEncrypt(mk, nonce, plaintext, ad)
        return header to Bytes.concat(nonce, ct)
    }

    fun decrypt(state: RatchetState, header: Header, ciphertext: ByteArray): ByteArray {
        val their = Bytes.b64d(header.dh)
        val keyId = "${header.dh}:${header.n}"
        state.skipped.remove(keyId)?.let { mk ->
            return open(Bytes.b64d(mk), header, ciphertext)
        }
        if (state.dhr == null || !state.dhr.contentEquals(their)) {
            skipUntil(state, header.pn)
            dhRatchet(state, their)
        }
        skipUntil(state, header.n)
        val ckr = state.ckr ?: error("receiving chain not ready")
        val (nck, mk) = kdfCk(ckr)
        state.ckr = nck
        state.nr += 1
        return open(mk, header, ciphertext)
    }

    private fun dhRatchet(state: RatchetState, theirPk: ByteArray) {
        state.pn = state.ns
        state.ns = 0
        state.nr = 0
        state.dhr = theirPk
        val recv = kdfRk(state.rk, Crypto.dh(state.dhsSk, theirPk))
        state.rk = recv.first
        state.ckr = recv.second
        val dhs = Crypto.freshX()
        state.dhsSk = dhs.sk
        state.dhsPk = dhs.pk
        val send = kdfRk(state.rk, Crypto.dh(state.dhsSk, theirPk))
        state.rk = send.first
        state.cks = send.second
    }

    private fun skipUntil(state: RatchetState, until: Int) {
        val ckr = state.ckr ?: return
        val dhr = state.dhr ?: return
        require(until - state.nr <= 200) { "too many skipped" }
        var ck = ckr
        while (state.nr < until) {
            val (nck, mk) = kdfCk(ck)
            ck = nck
            state.skipped["${Bytes.b64e(dhr)}:${state.nr}"] = Bytes.b64e(mk)
            state.nr += 1
        }
        state.ckr = ck
    }

    fun dump(state: RatchetState): JSONObject = JSONObject()
        .put("dhsSk", Bytes.b64e(state.dhsSk))
        .put("dhsPk", Bytes.b64e(state.dhsPk))
        .put("dhr", state.dhr?.let { Bytes.b64e(it) } ?: JsonNull)
        .put("rk", Bytes.b64e(state.rk))
        .put("cks", state.cks?.let { Bytes.b64e(it) } ?: JsonNull)
        .put("ckr", state.ckr?.let { Bytes.b64e(it) } ?: JsonNull)
        .put("ns", state.ns)
        .put("nr", state.nr)
        .put("pn", state.pn)
        .put("skipped", JSONObject().also { o -> state.skipped.forEach { (k, v) -> o.put(k, v) } })

    fun load(o: JSONObject): RatchetState = RatchetState(
        dhsSk = Bytes.b64d(o.getString("dhsSk")),
        dhsPk = Bytes.b64d(o.getString("dhsPk")),
        dhr = if (o.isNull("dhr")) null else Bytes.b64d(o.getString("dhr")),
        rk = Bytes.b64d(o.getString("rk")),
        cks = if (o.isNull("cks")) null else Bytes.b64d(o.getString("cks")),
        ckr = if (o.isNull("ckr")) null else Bytes.b64d(o.getString("ckr")),
        ns = o.getInt("ns"),
        nr = o.getInt("nr"),
        pn = o.getInt("pn"),
        skipped = mutableMapOf<String, String>().also { m ->
            val sk = o.optJSONObject("skipped") ?: JSONObject()
            sk.keys().forEach { k -> m[k] = sk.getString(k) }
        },
    )

    private fun open(mk: ByteArray, header: Header, ciphertext: ByteArray): ByteArray {
        val nonce = ciphertext.copyOfRange(0, 12)
        val ct = ciphertext.copyOfRange(12, ciphertext.size)
        val ad = Bytes.utf8(header.aad())
        return Crypto.aeadDecrypt(mk, nonce, ct, ad)
    }
}

object Canonical {
    fun of(obj: JSONObject): String {
        val keys = obj.keys().asSequence().toMutableList().sorted()
        return keys.joinToString(",", "{", "}") { k ->
            "\"$k\":${value(obj.get(k))}"
        }
    }

    private fun value(v: Any): String = when (v) {
        is JSONObject -> of(v)
        is Number -> if (v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Boolean -> v.toString()
        JSONObject.NULL -> "null"
        else -> JSONObject.quote(v.toString())
    }
}
