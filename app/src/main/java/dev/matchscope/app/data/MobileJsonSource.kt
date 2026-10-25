package dev.matchscope.app.data

import dev.matchscope.app.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Only observed, read-only routes; no authentication or integrity-session exchange routes. */
enum class MobileRoute(val path: String, val parameter: String?, val page: String) {
    MATCHES("MatchesV4", null, "matches"), MATCH("MatchScreen", "matchId", "matches"),
    EVENTS("EventsData2", null, "events"), EVENT("EventDetails2", "eventId", "events"),
    TEAM("TeamScreen", "teamId", "team"), PLAYER("PlayerScreen", "playerId", "player"),
    TEAM_NEWS("TeamNews", "teamId", "team"), PLAYER_NEWS("PlayerNews", "playerId", "player")
}

class MobileRequest private constructor(val route: MobileRoute, val entityId: String?) {
    val url: String get() = "https://www.hltv.org/mobile/${route.path}" + (route.parameter?.let { "?$it=$entityId" } ?: "")
    val pageUrl: String get() = "https://www.hltv.org/${route.page}" + (entityId?.let { "/$it/-" } ?: "")
    companion object {
        fun create(route: MobileRoute, id: String? = null): MobileRequest {
            require(if (route.parameter == null) id == null else id?.matches(Regex("[1-9][0-9]{0,9}")) == true)
            return MobileRequest(route, id)
        }
    }
}

enum class MobileFailure(val label: String, val cooldownMs: Long) {
    CHALLENGE("请完成网页验证", 300_000), BLOCKED("HLTV 暂时拒绝访问", 300_000),
    INTEGRITY_REQUIRED("此接口需要应用授权", 300_000), INTEGRITY_REJECTED("应用授权已失效", 300_000),
    NETWORK("连接失败，请检查网络", 30_000), UNAUTHORIZED("接口未授权", 300_000),
    RATE_LIMITED("请求过于频繁，请稍后重试", 300_000), REDIRECT("接口发生跳转", 60_000),
    HTTP("接口暂不可用", 30_000), CONTENT_TYPE("接口未返回数据", 60_000),
    INVALID_JSON("数据格式已变化", 60_000), OVERSIZE("数据超过大小限制", 60_000),
    WRONG_RESOURCE("返回的数据不匹配", 60_000)
}

class MobileAccessException(val reason: MobileFailure, val status: Int? = null, cooldownMs: Long = reason.cooldownMs) :
    SourceUnavailable(reason.label, cooldownMs)

data class MobileResponse(val url: String, val status: Int, val headers: Map<String, String>, val body: String)

object MobileResponsePolicy {
    const val MAX_BYTES = 4 * 1024 * 1024
    fun failure(status: Int, headers: Map<String, String>): MobileFailure? {
        val h = headers.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }
        if (status == 401 && h["x-app-integrity-error"]?.trim().equals("required", true)) return MobileFailure.INTEGRITY_REQUIRED
        if (status == 401 && !h["x-app-integrity-error"].isNullOrBlank()) return MobileFailure.INTEGRITY_REJECTED
        if (h["cf-mitigated"].equals("challenge", true)) return MobileFailure.CHALLENGE
        if (status == 403) return MobileFailure.BLOCKED
        if (status == 401) return MobileFailure.UNAUTHORIZED
        if (status == 429) return MobileFailure.RATE_LIMITED
        if (status in 300..399) return MobileFailure.REDIRECT
        if (status !in 200..299) return MobileFailure.HTTP
        val type = h["content-type"].orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        if (type != "application/json" && !(type.startsWith("application/") && type.endsWith("+json"))) return MobileFailure.CONTENT_TYPE
        return null
    }
    internal fun retryDelay(value: String?, nowMs: Long = System.currentTimeMillis()): Long {
        val seconds = value?.trim()?.toLongOrNull()
        val delay = if (seconds != null) seconds.coerceIn(0, 86_400) * 1_000 else runCatching {
            java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs
        }.getOrDefault(0)
        return delay.coerceIn(MobileFailure.RATE_LIMITED.cooldownMs, 86_400_000)
    }
    fun accept(request: MobileRequest, response: MobileResponse): String {
        if (URI(response.url) != URI(request.url)) throw MobileAccessException(MobileFailure.WRONG_RESOURCE, response.status)
        failure(response.status, response.headers)?.let { failure ->
            val retry = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value
            val cooldown = if (failure == MobileFailure.RATE_LIMITED) retryDelay(retry) else failure.cooldownMs
            throw MobileAccessException(failure, response.status, cooldown)
        }
        if (response.body.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw MobileAccessException(MobileFailure.OVERSIZE, response.status)
        return response.body
    }
}

fun interface MobileFetcher { suspend fun get(request: MobileRequest): MobileResponse }

/** The request belongs to RushB; cancellation closes its socket and response body. */
class MobileHttpTransport : MobileFetcher {
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    override suspend fun get(request: MobileRequest): MobileResponse = suspendCancellableCoroutine { pending ->
        val call = client.newCall(Request.Builder().url(request.url)
            .header("User-Agent", "RushB/${BuildConfig.VERSION_NAME} (independent personal open-source client)")
            .header("Accept", "application/json").header("Cache-Control", "no-cache").get().build())
        pending.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (pending.isActive) pending.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        val output = ByteArrayOutputStream()
                        response.body?.byteStream()?.use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (output.size() + n > MobileResponsePolicy.MAX_BYTES) throw MobileAccessException(MobileFailure.OVERSIZE, response.code)
                                output.write(buffer, 0, n)
                            }
                        }
                        MobileResponse(response.request.url.toString(), response.code,
                            listOf("Content-Type", "cf-mitigated", "X-App-Integrity-Error", "Retry-After").mapNotNull { key -> response.header(key)?.let { key to it } }.toMap(),
                            output.toString("UTF-8"))
                    }
                    if (pending.isActive) pending.resume(value)
                } catch (e: Exception) { if (pending.isActive) pending.resumeWithException(e) }
            }
        })
    }
}

enum class MobileTransport { HTTPS, BROWSER }

/** One queue for JSON and embedded tournament documents in the same phone session. */
class MobileRequestGate {
    private val mutex = Mutex()
    private var lastStart = 0L
    suspend fun <T> read(block: suspend () -> T): T = mutex.withLock {
        val remaining = 3_000L - (android.os.SystemClock.elapsedRealtime() - lastStart)
        if (remaining > 0) delay(remaining)
        lastStart = android.os.SystemClock.elapsedRealtime()
        block()
    }
}

/** Separate raw cache; a caller's entity decoder must pass before any response is committed. */
class MobileJsonSource(directory: File, private val browser: BrowserSession, private val http: MobileFetcher = MobileHttpTransport(),
    private val onResponse: (MobileTransport, MobileResponse) -> Unit = { _, _ -> },
    private val requestGate: MobileRequestGate = MobileRequestGate(),
    private val preferredTransport: () -> MobileTransport = { MobileTransport.HTTPS },
    private val backgroundBrowser: MobileFetcher? = null) {
    private val ledger = MobileAccessLedger(File(directory, "access.properties"))
    private val mutableIssue = MutableStateFlow(ledger.latest())
    val issue = mutableIssue.asStateFlow()
    private val stores = MobileTransport.entries.associateWith { mode -> store(directory, mode) }
    private val backgroundStore by lazy { store(directory, MobileTransport.BROWSER, background = true) }
    private fun store(directory: File, mode: MobileTransport, background: Boolean = false) =
        PageStore(File(directory, mode.name.lowercase()), download = { url ->
            val request = requestForUrl(url)
            ledger.active(request, mode)?.let { mutableIssue.value = it }
            ledger.requireReady(request, mode)
            try {
            val response = requestGate.read {
                ledger.requireReady(request, mode)
                if (mode == MobileTransport.HTTPS) http.get(request)
                else if (background) (backgroundBrowser ?: throw SourceUnavailable("暂时无法自动更新")).get(request)
                else {
                val message = JSONObject(browser.loadJson(request))
                if (message.optString("state") != "json-response" || message.optString("jsonUrl") != request.url)
                    throw MobileAccessException(MobileFailure.WRONG_RESOURCE)
                val headers = message.getJSONObject("headers")
                MobileResponse(message.getString("responseUrl"), message.getInt("status"), headers.keys().asSequence().associateWith { headers.getString(it) }, message.getString("body"))
            } }
            onResponse(mode, response)
            val body = MobileResponsePolicy.accept(request, response)
            ledger.succeeded(request, mode)
            mutableIssue.value = null
            PageBody(body)
            } catch (e: CancellationException) { throw e }
            catch (e: MobileAccessException) { mutableIssue.value = ledger.rejected(request, mode, e); throw e }
            catch (e: IOException) {
                if (BuildConfig.DEBUG) android.util.Log.w("RushB", "Mobile ${request.route} connection failed", e)
                val error = MobileAccessException(MobileFailure.NETWORK)
                mutableIssue.value = ledger.rejected(request, mode, error)
                throw error
            }
        })
    suspend fun <T> read(request: MobileRequest, mode: MobileTransport = preferredTransport(), refresh: Boolean = false,
        background: Boolean = false, decode: (JSONObject) -> T): Sourced<T> {
        val store = if (background && mode == MobileTransport.BROWSER) backgroundStore else stores.getValue(mode)
        val value = store.read(request.url, 60_000, forceRefresh = refresh) { raw ->
            val root = try { JSONObject(raw) } catch (_: Exception) { throw MobileAccessException(MobileFailure.INVALID_JSON) }
            try { decode(root).also { MediaAssets.flush() } } catch (e: org.json.JSONException) { throw MobileAccessException(MobileFailure.INVALID_JSON) }
        }
        return Sourced(value.value, SourceStamp(request.url, value.at, if (value.cached) DataOrigin.CACHE else DataOrigin.NETWORK), value.warning)
    }
    private fun requestForUrl(url: String): MobileRequest {
        val uri = URI(url)
        val route = MobileRoute.entries.single { uri.path == "/mobile/${it.path}" }
        val id = route.parameter?.let { key -> uri.rawQuery?.takeIf { it.startsWith("$key=") }?.substringAfter('=') }
        return MobileRequest.create(route, id).also { require(it.url == url) }
    }
}

/** Validate the requested entity before any fields or cache entries are used. */
fun mobileMatchEnvelope(root: JSONObject, expectedId: String): JSONObject {
    val data = root.optJSONObject("matchData") ?: throw MobileAccessException(MobileFailure.INVALID_JSON)
    val identity = data.optJSONObject("match")?.optString("matchId")?.takeIf(String::isNotBlank)
        ?: data.optString("matchId").takeIf(String::isNotBlank)
    if (identity != expectedId) throw MobileAccessException(MobileFailure.WRONG_RESOURCE)
    return root
}
