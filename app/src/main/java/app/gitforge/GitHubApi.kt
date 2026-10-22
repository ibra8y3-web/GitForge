package app.gitforge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Thin, real client for the GitHub REST API. No fake data, every call hits api.github.com. */
class GitHubApi(private val activeToken: () -> String?) {

    class ApiException(val code: Int, msg: String) : Exception(msg)

    class Resp(val code: Int, val body: String, val headers: Headers) {
        fun js(): JsonElement = if (body.isBlank()) JsonNull else json.parseToJsonElement(body)
    }

    class Device(val deviceCode: String, val userCode: String, val uri: String, val interval: Int, val expires: Int)

    var rate by mutableStateOf("")
    var scopes by mutableStateOf("")

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    private fun track(r: Response) {
        r.header("x-ratelimit-remaining")?.let { rate = "$it/${r.header("x-ratelimit-limit") ?: "?"}" }
        r.header("x-oauth-scopes")?.let { scopes = it }
    }

    private fun errMsg(txt: String, fallback: String): String {
        val m = runCatching {
            val o = json.parseToJsonElement(txt).o()
            o.s("message") + (o.list("errors").firstOrNull()?.s("message")?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        }.getOrNull()
        return if (m.isNullOrBlank()) fallback else m
    }

    suspend fun send(
        method: String,
        path: String,
        body: JsonElement? = null,
        token: String? = activeToken(),
        accept: String = "application/vnd.github+json"
    ): Resp = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else "https://api.github.com$path"
        val rb: RequestBody? = when {
            body != null -> json.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)
            method == "POST" || method == "PUT" || method == "PATCH" -> "".toRequestBody(null)
            else -> null
        }
        val req = Request.Builder().url(url)
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, rb).build()
        http.newCall(req).execute().use { r ->
            track(r)
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, errMsg(txt, r.message))
            Resp(r.code, txt, r.headers)
        }
    }

    suspend fun get(path: String): JsonElement = send("GET", path).js()

    suspend fun rawBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com$path")
            .header("Accept", "application/vnd.github.raw+json")
            .header("Authorization", "Bearer ${activeToken()}").build()
        http.newCall(req).execute().use { r ->
            track(r)
            if (!r.isSuccessful) throw ApiException(r.code, errMsg(r.body?.string().orEmpty(), r.message))
            r.body!!.bytes()
        }
    }

    /** Follows Link: rel="next" headers; every page is a real request. */
    suspend fun pages(path: String, max: Int = 30): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var url: String? = "https://api.github.com$path" + (if ("?" in path) "&" else "?") + "per_page=100"
        var i = 0
        while (url != null && i < max) {
            val r = send("GET", url)
            out += r.js().items()
            url = r.headers["Link"]?.split(",")?.firstOrNull { it.contains("rel=\"next\"") }
                ?.substringAfter("<")?.substringBefore(">")
            i++
        }
        return out
    }

    suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val req = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer ${activeToken()}").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ApiException(r.code, errMsg(r.body?.string().orEmpty(), r.message))
                val total = r.body!!.contentLength()
                r.body!!.byteStream().use { i ->
                    dest.outputStream().use { o ->
                        val buf = ByteArray(65536)
                        var done = 0L
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
            dest
        }

    suspend fun uploadAsset(uploadUrl: String, name: String, mime: String, length: Long, open: () -> InputStream) =
        withContext(Dispatchers.IO) {
            val url = uploadUrl.substringBefore("{") + "?name=" + URLEncoder.encode(name, "UTF-8")
            val body = object : RequestBody() {
                override fun contentType() = mime.toMediaType()
                override fun contentLength() = length
                override fun writeTo(sink: BufferedSink) { open().use { sink.writeAll(it.source()) } }
            }
            val req = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer ${activeToken()}")
                .post(body).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ApiException(r.code, errMsg(r.body?.string().orEmpty(), r.message))
            }
        }

    // ---- OAuth device flow (no client secret needed) ----
    suspend fun deviceStart(clientId: String, scope: String): Device = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .post(FormBody.Builder().add("client_id", clientId).add("scope", scope).build()).build()
        http.newCall(req).execute().use { r ->
            val o = json.parseToJsonElement(r.body?.string().orEmpty()).o()
            if (o.s("device_code").isEmpty())
                throw ApiException(r.code, o.s("error_description").ifEmpty { o.s("error").ifEmpty { r.message } })
            Device(o.s("device_code"), o.s("user_code"), o.s("verification_uri"),
                o.n("interval").toInt().coerceAtLeast(5), o.n("expires_in").toInt())
        }
    }

    suspend fun exchangeCode(clientId: String, secret: String, code: String, redirect: String): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .post(FormBody.Builder().add("client_id", clientId).add("client_secret", secret)
                    .add("code", code).add("redirect_uri", redirect).build()).build()
            http.newCall(req).execute().use { r ->
                val o = json.parseToJsonElement(r.body?.string().orEmpty()).o()
                if (o.s("access_token").isEmpty()) throw ApiException(r.code, o.s("error_description").ifEmpty { o.s("error").ifEmpty { r.message } })
                o.s("access_token")
            }
        }

    suspend fun devicePoll(clientId: String, d: Device): String {
        var wait = d.interval
        val deadline = System.currentTimeMillis() + d.expires * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(wait * 1000L)
            val o = withContext(Dispatchers.IO) {
                val req = Request.Builder().url("https://github.com/login/oauth/access_token")
                    .header("Accept", "application/json")
                    .post(FormBody.Builder().add("client_id", clientId).add("device_code", d.deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()).build()
                http.newCall(req).execute().use { r -> json.parseToJsonElement(r.body?.string().orEmpty()).o() }
            }
            when (o.s("error")) {
                "" -> if (o.s("access_token").isNotEmpty()) return o.s("access_token")
                "authorization_pending" -> {}
                "slow_down" -> wait += 5
                else -> throw ApiException(400, o.s("error_description").ifEmpty { o.s("error") })
            }
        }
        throw ApiException(408, "Device code expired")
    }
}
