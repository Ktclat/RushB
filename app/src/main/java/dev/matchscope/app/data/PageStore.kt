package dev.matchscope.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException

data class PageValue<T>(val value: T, val at: Instant, val cached: Boolean, val warning: String? = null)

/** A shared gate, TTL cache, bounded body, and cooldown prevent repeated upstream hits. */
class PageStore(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val download: suspend (String) -> PageBody = { PageBody(fetchHttps(it)) },
    private val spacingMs: Long = 2_000,
    private val accessScope: () -> String = { "default" },
    private val recoveredPage: suspend (String) -> PageBody? = { null },
) {
    private val gate = Mutex()
    private var lastRequest = 0L
    private val blockedUntil = mutableMapOf<String, Long>()

    /** Reparse an existing public page after a schema upgrade without changing its age. */
    fun <T> cached(url: String, parse: (String) -> T): PageValue<T>? {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$key.txt")
        if (!file.isFile) return null
        return runCatching { PageValue(parse(file.readText()), Instant.ofEpochMilli(file.lastModified()), true) }.getOrNull()
    }

    suspend fun <T> read(url: String, ttlMs: Long, maxStaleMs: Long = 7 * 86_400_000L, forceRefresh: Boolean = false, parse: (String) -> T): PageValue<T> = withContext(Dispatchers.IO) {
        gate.withLock {
            directory.mkdirs()
            val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(directory, "$key.txt")
            val recovered = recoveredPage(url)
            if (recovered == null && !forceRefresh && file.exists() && now() - file.lastModified() in 0 until ttlMs) {
                try { return@withLock PageValue(parse(file.readText()), Instant.ofEpochMilli(file.lastModified()), true) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { file.delete() }
            }
            val host = URI(url).host ?: throw SourceUnavailable("来源地址无效。")
            // A browser session established by the user is separate from anonymous HTTP.
            // Keep the same verified cache, but do not carry rejection cooldowns across transports.
            val accessKey = "$host:${accessScope()}"
            try {
                // A captured page needs no network request. Preserve the existing cooldown
                // for later requests while still parsing this exact, newly opened document.
                val raw = recovered ?: run {
                    if (now() < maxOf(blockedUntil[accessKey] ?: 0, blockedUntil[host] ?: 0)) throw SourceUnavailable("来源暂不可用，访问冷却中，请稍后再试。")
                    val remaining = spacingMs - (now() - lastRequest)
                    if (remaining > 0) delay(remaining)
                    lastRequest = now()
                    download(url)
                }
                // Parse before committing; challenge/error pages never poison a successful cache.
                val value = parse(raw.html)
                val at = raw.capturedAt?.toEpochMilli() ?: now()
                val temp = File(directory, "$key.tmp")
                temp.writeText(raw.html)
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                file.setLastModified(at)
                PageValue(value, Instant.ofEpochMilli(at), raw.cached)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is SourceUnavailable && e.cooldownMs > 0) blockedUntil[if (e.sharedCooldown) host else accessKey] = now() + e.cooldownMs
                // Stale is an explicit state; never stamp old scores with the current time.
                val old = if (file.exists() && now() - file.lastModified() in 0 until maxStaleMs)
                    runCatching { PageValue(parse(file.readText()), Instant.ofEpochMilli(file.lastModified()), true,
                        "${if(e is SourceUnavailable) e.message else "网络连接失败，请检查数据连接。"} 正在显示上次成功读取的缓存，赛况可能已变化。") }.getOrNull() else null
                old ?: throw e
            }
        }
    }
}

private val allowedHosts = setOf("www.hltv.org", "hltv.org", "raw.githubusercontent.com", "api.github.com")
internal fun fetchHttps(url: String): String {
    var current = url
    repeat(4) {
        val uri = URI(current)
        require(uri.scheme == "https" && uri.host in allowedHosts && uri.userInfo == null) { "不支持的来源地址" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "RushB/${dev.matchscope.app.BuildConfig.VERSION_NAME} (independent personal open-source client)")
        try {
            val status = connection.responseCode
            if (status in 300..399) {
                current = uri.resolve(connection.getHeaderField("Location") ?: throw SourceUnavailable("来源重定向异常。")).toString()
                return@repeat
            }
            if (connection.getHeaderField("cf-mitigated") == "challenge") throw SourceUnavailable("来源需要浏览器验证。", 300_000)
            if (status == 403 || status == 429) throw SourceUnavailable("来源返回 HTTP $status，暂时拒绝访问。可打开原网页查看。", 300_000, sharedCooldown = status == 429)
            if (status !in 200..299) throw SourceUnavailable("来源返回 HTTP $status，请稍后重试。")
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 8 * 1024 * 1024) throw SourceUnavailable("来源页面超过大小限制。")
                    output.write(buffer, 0, n)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }
    throw SourceUnavailable("来源重定向次数过多。")
}
