package dev.matchscope.app.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant

data class RelayConfig(val enabled: Boolean = false, val token: String = "")
data class PageBody(val html: String, val capturedAt: Instant? = null, val cached: Boolean = false)

/** USB adb reverse exposes only the development relay's loopback port to the phone. */
internal fun relayPage(url: String, config: RelayConfig): PageBody {
    if (!Regex("[a-fA-F0-9]{64}").matches(config.token)) throw SourceUnavailable("请先在数据连接中填写电脑中继令牌。")
    val endpoint = "http://127.0.0.1:8765/page?url=${URLEncoder.encode(url, "UTF-8")}" // never an arbitrary proxy
    val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 5_000
    connection.readTimeout = 70_000
    connection.setRequestProperty("Authorization", "Bearer ${config.token}")
    try {
        val status = connection.responseCode
        if (status != 200) {
            val raw = connection.errorStream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(4096)
                var length = 0
                while (length < buffer.size) {
                    val count = reader.read(buffer, length, buffer.size - length)
                    if(count < 0) break
                    length += count
                }
                String(buffer, 0, length)
            }.orEmpty()
            val message = runCatching { JSONObject(raw).optString("message") }.getOrNull()?.takeIf(String::isNotBlank)
            throw SourceUnavailable(message ?: "电脑中继返回 HTTP $status，请检查服务与 USB 连接。", if(status == 429) 30_000 else 0)
        }
        val bytes = connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if(out.size() + count > 12 * 1024 * 1024) throw SourceUnavailable("中继响应超过大小限制。")
                out.write(buffer, 0, count)
            }
            out.toString("UTF-8")
        }
        val result = JSONObject(bytes)
        if (result.optInt("version") != 1 || result.getString("url") != url || !HltvAccess.sameResource(url, result.getString("resolvedUrl")))
            throw SourceUnavailable("中继返回的来源与请求不一致。")
        val at = Instant.parse(result.getString("capturedAt"))
        if (at > Instant.now().plusSeconds(300)) throw SourceUnavailable("中继采集时间异常，请校准电脑时间。")
        return PageBody(result.getString("html"), at, result.optBoolean("cached"))
    } finally { connection.disconnect() }
}
