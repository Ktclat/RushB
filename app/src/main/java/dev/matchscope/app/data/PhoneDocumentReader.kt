package dev.matchscope.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeoutException
import kotlin.coroutines.coroutineContext

/** Read public documents with this phone's website session, without opening a modal. */
internal class PhoneDocumentReader(private val context: Context) {
    suspend fun read(url: String): String = withTimeoutOrNull(45_000) {
        readPublicDocument(url) { response(it, "text/html") }
    } ?: throw SourceUnavailable("网页读取超时", 30_000)

    suspend fun response(url: String, accept: String): MobileResponse = withContext(Dispatchers.IO) {
        require(HltvAccess.allowed(url))
        val executor = withContext(Dispatchers.Main) { GeckoWebExecutor(PhoneBrowserRuntime.get(context)) }
        val task = coroutineContext
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        fun remaining(): Long {
            task.ensureActive()
            return (deadline - android.os.SystemClock.elapsedRealtime()).also {
                if (it <= 0) throw SourceUnavailable("网页读取超时", 30_000)
            }
        }
        val pending = executor.fetch(WebRequest.Builder(url).header("Accept", accept)
            .header("Cache-Control", "no-cache").build(), GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS)
        val response = try {
            var result: org.mozilla.geckoview.WebResponse? = null
            while (result == null) {
                try { result = pending.poll(minOf(remaining(), 250)) }
                catch (_: TimeoutException) { /* Poll the same request; do not retry it. */ }
            }
            result
        } catch (e: Exception) { pending.cancel(); throw e }
        response.body?.use { input ->
            if (!response.isSecure || !HltvAccess.sameResource(url, response.uri)) throw SourceUnavailable("返回页面不匹配", 60_000)
            val headers = response.headers.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                response.setReadTimeoutMillis(minOf(remaining(), 3_000))
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 8 * 1024 * 1024) throw SourceUnavailable("页面过大", 60_000)
                output.write(buffer, 0, count)
            }
            MobileResponse(response.uri, response.statusCode, headers, output.toString("UTF-8"))
        } ?: throw SourceUnavailable("网页为空", 30_000)
    }
}
