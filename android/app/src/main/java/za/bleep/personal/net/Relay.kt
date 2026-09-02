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
    fun drop(token: String, dest: String, blobB64: String): JSONObject =
        req("POST", "/v1/mail", JSONObject().put("dest_mailbox_id", dest).put("blob_b64", blobB64), token)
    fun fetch(token: String): JSONArray {
        val r = req("GET", "/v1/mail", token = token)
        return r.optJSONArray("items") ?: JSONArray()
    }

    fun unlink(mailboxId: String, token: String): JSONObject =
        req("POST", "/v1/mailboxes/$mailboxId/unlink", JSONObject(), token)
}
