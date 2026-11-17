package za.bleep.personal.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import za.bleep.personal.protocol.REGION
import java.util.concurrent.TimeUnit

class RegionMismatch(val pinned: String) : Exception("Pinned to $pinned. Fail closed.")

class Relay(var base: String = "http://10.0.2.2:8090") {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun headers(token: String? = null) = buildMap {
        put("Content-Type", "application/json")
        put("X-Bleep-Region", REGION)
        if (token != null) put("Authorization", "Bearer $token")
    }

    fun req(method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject {
        val b = body?.toString()?.toRequestBody(jsonType)
        val rb = Request.Builder().url(base.trimEnd('/') + path)
        headers(token).forEach { (k, v) -> rb.addHeader(k, v) }
        when (method) {
            "GET" -> rb.get()
            "POST" -> rb.post(b ?: "{}".toRequestBody(jsonType))
            "PUT" -> rb.put(b ?: "{}".toRequestBody(jsonType))
            "DELETE" -> rb.delete(b ?: "{}".toRequestBody(jsonType))
        }
        http.newCall(rb.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 421) throw RegionMismatch(REGION)
            if (!res.isSuccessful) error("${res.code} $text")
            return if (text.isBlank()) JSONObject() else {
                if (text.trimStart().startsWith("[")) JSONObject().put("items", JSONArray(text))
                else JSONObject(text)
            }
        }
    }

    fun health(): JSONObject = req("GET", "/v1/health")
    fun challenge(): JSONObject = req("GET", "/v1/auth/challenge")
    fun register(body: JSONObject): JSONObject = req("POST", "/v1/mailboxes", body)
    fun token(body: JSONObject): JSONObject = req("POST", "/v1/auth/token", body)
    fun prekey(mailboxId: String, token: String): JSONObject = req("GET", "/v1/mailboxes/$mailboxId/prekey", token = token)
    fun drop(token: String, dest: String, blobB64: String, ttlSeconds: Int? = null): JSONObject {
        val body = JSONObject().put("dest_mailbox_id", dest).put("blob_b64", blobB64)
        if (ttlSeconds != null) body.put("ttl_seconds", ttlSeconds)
        return req("POST", "/v1/mail", body, token)
    }
    fun presence(token: String, ids: List<String>): JSONObject {
        val q = ids.take(30).joinToString("&") { "mailbox_id=" + java.net.URLEncoder.encode(it, "UTF-8") }
        return req("GET", "/v1/presence?$q", token = token)
    }
    fun channels(token: String): JSONObject = req("GET", "/v1/channels", token = token)
    fun createChannel(token: String, name: String): JSONObject =
        req("POST", "/v1/channels", JSONObject().put("name", name), token)
    fun channelPosts(token: String, id: String): JSONObject = req("GET", "/v1/channels/$id/posts", token = token)
    fun postChannel(token: String, id: String, text: String, name: String): JSONObject =
        req("POST", "/v1/channels/$id/posts", JSONObject().put("text", text).put("name", name), token)
    fun fetch(token: String): JSONArray {
        val r = req("GET", "/v1/mail", token = token)
        return r.optJSONArray("items") ?: JSONArray()
    }

    fun unlink(mailboxId: String, token: String): JSONObject =
        req("POST", "/v1/mailboxes/$mailboxId/unlink", JSONObject(), token)

    fun createLogin(body: JSONObject): JSONObject = req("POST", "/v1/accounts", body)
    fun loginAccount(body: JSONObject): JSONObject = req("POST", "/v1/accounts/login", body)
    fun recover(email: String): JSONObject = req("POST", "/v1/accounts/recover", JSONObject(AccountRequests.recover(email)))
    fun recoverComplete(token: String, password: String): JSONObject =
        req("POST", "/v1/accounts/recover/complete", JSONObject(AccountRequests.recoverComplete(token, password)))
    fun addDevice(accountToken: String, body: JSONObject): JSONObject =
        req("POST", "/v1/accounts/devices", body, accountToken)
    fun devices(token: String): JSONObject = req("GET", "/v1/accounts/devices", token = token)
    fun revokeDevice(token: String, id: String): JSONObject =
        req("POST", "/v1/accounts/devices/$id/revoke", JSONObject(), token)
    fun eraseDevice(token: String, id: String): JSONObject =
        req("POST", "/v1/accounts/devices/$id/erase", JSONObject(), token)
    fun history(token: String): JSONObject = req("GET", "/v1/history", token = token)
    fun putHistory(token: String, body: JSONObject): JSONObject = req("PUT", "/v1/history", body, token)
    fun directory(token: String, q: String): JSONObject = req("GET", AccountRequests.directoryPath(q), token = token)
    fun resolveHandle(token: String, handle: String): JSONObject = req("GET", "/v1/handles/$handle", token = token)
    fun resolveShort(token: String, shortId: String): JSONObject = req("GET", "/v1/ids/$shortId", token = token)
    fun dropHandle(token: String): JSONObject = req("DELETE", "/v1/handles", token = token)
    fun changePassword(accountToken: String, body: JSONObject): JSONObject =
        req("POST", "/v1/accounts/password", body, accountToken)
    fun closeAccount(accountToken: String, body: JSONObject): JSONObject =
        req("POST", "/v1/accounts/close", body, accountToken)
    fun confirm(body: JSONObject): JSONObject = req("POST", "/v1/accounts/confirm", body)
    fun putHistoryFile(token: String, fileId: String, body: JSONObject): JSONObject =
        req("PUT", "/v1/history/files/$fileId", body, token)
    fun getHistoryFile(token: String, fileId: String): JSONObject =
        req("GET", "/v1/history/files/$fileId", token = token)
    fun putHistoryExtra(token: String, extraId: String, body: JSONObject): JSONObject =
        req("PUT", "/v1/history/extras/$extraId", body, token)
    fun getHistoryExtra(token: String, extraId: String): JSONObject =
        req("GET", "/v1/history/extras/$extraId", token = token)
}

object AccountRequests {
    fun signup(email: String, password: String): String =
        obj("email" to email.trim().lowercase(), "password" to password)

    fun login(email: String, password: String): String = signup(email, password)

    fun device(label: String, mailboxId: String, nonce: String, signatureB64: String): String =
        obj("label" to label, "mailbox_id" to mailboxId, "nonce" to nonce, "signature_b64" to signatureB64)

    fun history(generation: Int, nonceB64: String, blobB64: String): String =
        """{"generation":$generation,"nonce_b64":${js(nonceB64)},"blob_b64":${js(blobB64)}}"""

    fun recover(email: String): String = obj("email" to email.trim().lowercase())

    fun recoverComplete(token: String, password: String): String =
        obj("token" to token.trim(), "password" to password)

    fun directoryPath(q: String): String = "/v1/directory?q=" + java.net.URLEncoder.encode(q, "UTF-8")

    fun close(password: String): String = obj("password" to password)

    fun confirm(token: String): String = obj("token" to token.trim())

    fun password(current: String, next: String): String =
        obj("current_password" to current, "new_password" to next)

    fun file(nonceB64: String, blobB64: String, mime: String, name: String): String =
        """{"nonce_b64":${js(nonceB64)},"blob_b64":${js(blobB64)},"mime":${js(mime)},"name":${js(name)}}"""

    private fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> js(k) + ":" + js(v) }

    private fun js(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(c)
            }
        }
        append('"')
    }
}
