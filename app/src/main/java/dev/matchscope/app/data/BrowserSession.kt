package dev.matchscope.app.data

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BrowserRequest(val id: Long, val url: String, val jsonUrl: String? = null, val interactive: Boolean = false)

/** Only the visible, app-owned phone browser fulfills requests. Cookies stay in that browser. */
class BrowserSession(private val readTimeoutMs: Long = 90_000, private val now: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private val mutable = MutableStateFlow<BrowserRequest?>(null)
    val request = mutable.asStateFlow()
    private var continuation: CancellableContinuation<String>? = null
    private var sequence = 0L
    private data class VerifiedDocument(val url: String, val html: String, val expiresAt: Long)
    private var verifiedDocument: VerifiedDocument? = null

    suspend fun browse(url: String): String = loadDocument(url, null, true)

    suspend fun load(url: String): String = readDocument(url, null)

    suspend fun loadJson(request: MobileRequest): String = readDocument(request.url, request.url)

    // A stalled navigation must release the busy state so the page can offer recovery.
    // Interactive login deliberately has no read deadline.
    suspend fun takeVerifiedDocument(url: String): String? = withContext(Dispatchers.Main.immediate) {
        if (verifiedDocument?.expiresAt?.let { it <= now() } == true) verifiedDocument = null
        verifiedDocument?.takeIf { HltvAccess.sameResource(url, it.url) }?.also { verifiedDocument = null }?.html
    }

    private suspend fun readDocument(url: String, jsonUrl: String?): String {
        val document = if (jsonUrl == null) takeVerifiedDocument(url) else null
        if (document != null) return document
        return withTimeoutOrNull(readTimeoutMs) { loadDocument(url, jsonUrl) }
            ?: throw SourceUnavailable("网页读取超时，请重试。", 30_000)
    }

    private suspend fun loadDocument(url: String, jsonUrl: String?, interactive: Boolean = false): String = withContext(Dispatchers.Main.immediate) {
        require(HltvAccess.allowed(url)) { "不支持的 HLTV 地址" }
        check(continuation == null) { "已有页面正在读取" }
        suspendCancellableCoroutine { pending ->
            continuation = pending
            mutable.value = BrowserRequest(++sequence, url, jsonUrl, interactive)
            pending.invokeOnCancellation {
                val clear = { if (continuation === pending) { continuation = null; mutable.value = null } }
                if (Looper.myLooper() == Looper.getMainLooper()) clear() else Handler(Looper.getMainLooper()).post { clear() }
            }
        }
    }

    fun complete(id: Long, html: String) {
        val request = mutable.value?.takeIf { it.id == id } ?: return
        // Reuse only the sanitized DOM captured from the exact interactive page. It is
        // private, short lived and consumed once; JSON requests still perform their own read.
        val readableRss = request.url == "https://www.hltv.org/rss/news" &&
            runCatching { NewsParser.rss(html).isNotEmpty() }.getOrDefault(false)
        if (request.interactive && html.length in 1..8 * 1024 * 1024 &&
            (html.trimStart().startsWith("<html", ignoreCase = true) || readableRss)) {
            verifiedDocument = VerifiedDocument(request.url, html, now() + 60_000)
        }
        val pending = continuation
        continuation = null; mutable.value = null
        if (pending?.isActive == true) pending.resume(html)
    }

    fun fail(id: Long, message: String, cooldownMs: Long = 0, reason: MobileFailure? = null) {
        if (mutable.value?.id != id) return
        val pending = continuation
        continuation = null; mutable.value = null
        if (pending?.isActive == true) pending.resumeWithException(reason?.let { MobileAccessException(it, cooldownMs = cooldownMs) } ?: SourceUnavailable(message, cooldownMs))
    }
    fun cancel(id: Long) {
        if (mutable.value?.id != id) return
        val pending = continuation
        continuation = null; mutable.value = null
        pending?.cancel()
    }
}

object HltvAccess {
    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in setOf("www.hltv.org", "hltv.org") &&
            uri.userInfo == null && uri.port in setOf(-1, 443)
    }.getOrDefault(false)

    fun sameResource(expected: String, actual: String): Boolean {
        if (!allowed(expected) || !allowed(actual)) return false
        val a = URI(expected); val b = URI(actual)
        val detail = Regex("^/(matches|player|team|events|news|stats/players)/(\\d+)(?:/.*)?$")
        val x = detail.matchEntire(a.path); val y = detail.matchEntire(b.path)
        if (x != null) return y != null && x.groupValues.take(3).drop(1) == y.groupValues.take(3).drop(1)
        if (a.path.trimEnd('/') == "/ranking/teams") return b.path == "/ranking/teams" || b.path.startsWith("/ranking/teams/")
        return a.path.trimEnd('/') == b.path.trimEnd('/') &&
            a.rawQuery.orEmpty().split('&').filter(String::isNotBlank).all { it in b.rawQuery.orEmpty().split('&') }
    }
}
