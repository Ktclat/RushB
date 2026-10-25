package dev.matchscope.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.io.IOException
import java.util.concurrent.TimeoutException

/** Images use the user's on-device website session. No cookies leave Gecko. */
internal class BrowserImageTransport(private val context: Context) {
    private val executor by lazy {
        runBlocking(Dispatchers.Main) { GeckoWebExecutor(PhoneBrowserRuntime.get(context)) }
    }

    fun get(request: Request, cancelled: () -> Boolean): Response {
        val url = request.url.toString()
        if (MediaUrls.validated(url) == null) throw IOException("Unsupported image source")
        val deadline = android.os.SystemClock.elapsedRealtime() + 25_000
        fun remaining(): Long {
            if (cancelled()) throw IOException("Image request cancelled")
            return (deadline - android.os.SystemClock.elapsedRealtime()).also {
                if (it <= 0) throw IOException("Image request timed out")
            }
        }
        remaining()
        val pending = executor.fetch(WebRequest.Builder(url)
            .header("Accept", request.header("Accept") ?: "image/*").build(), GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS)
        val response = try {
            var result: org.mozilla.geckoview.WebResponse? = null
            while (result == null) {
                try { result = pending.poll(minOf(remaining(), 250)) }
                catch (_: TimeoutException) { /* Check cancellation without another network request. */ }
            }
            result
        } catch (failure: Exception) { pending.cancel(); throw IOException("Image request failed", failure) }
        if (response.uri != url || !response.isSecure) {
            response.body?.close()
            throw IOException("Image source changed")
        }
        val type = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
        val length = response.headers.entries.firstOrNull { it.key.equals("content-length", true) }?.value?.toLongOrNull()
        val bytes = ImageResponseBody.read(response.statusCode, type, length, response.body) {
            response.setReadTimeoutMillis(minOf(15_000, remaining()))
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(response.statusCode).message("Website image")
            .apply { response.headers.forEach { (name, value) -> header(name, value) } }
            .body(bytes.toResponseBody(type?.toMediaTypeOrNull())).build()
    }
}
