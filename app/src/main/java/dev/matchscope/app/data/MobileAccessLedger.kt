package dev.matchscope.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/** Public error metadata only. Authorization and rate limits apply across phone transports. */
data class MobileAccessIssue(val reason: MobileFailure, val retryAtMillis: Long, val status: Int? = null)

class MobileAccessLedger(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val entries = linkedMapOf<String, MobileAccessIssue>()
    init {
        runCatching {
            val saved = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
            saved.stringPropertyNames().take(128).forEach { key ->
                val parts = saved.getProperty(key).split(',')
                val issue = MobileAccessIssue(MobileFailure.valueOf(parts[0]), parts[1].toLong(), parts.getOrNull(2)?.toIntOrNull())
                if (issue.retryAtMillis - now() in 1..86_400_000) entries[key] = issue
            }
        }
    }
    private fun resource(request: MobileRequest, mode: MobileTransport) = "resource:${mode.name}:" + MessageDigest.getInstance("SHA-256")
        .digest(request.url.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun key(request: MobileRequest, mode: MobileTransport, reason: MobileFailure) = when (reason) {
        MobileFailure.INTEGRITY_REQUIRED, MobileFailure.INTEGRITY_REJECTED -> "authorization"
        MobileFailure.RATE_LIMITED -> "rate-limit"
        MobileFailure.CHALLENGE, MobileFailure.BLOCKED -> "transport:${mode.name}"
        else -> resource(request, mode)
    }
    @Synchronized fun active(request: MobileRequest, mode: MobileTransport): MobileAccessIssue? =
        listOf("authorization", "rate-limit", "transport:${mode.name}", resource(request, mode))
            .mapNotNull(entries::get).firstOrNull { it.retryAtMillis > now() }

    @Synchronized fun latest(): MobileAccessIssue? = entries.values.filter { it.retryAtMillis > now() }.maxByOrNull { it.retryAtMillis }

    fun requireReady(request: MobileRequest, mode: MobileTransport) {
        active(request, mode)?.let { throw MobileAccessException(it.reason, it.status, (it.retryAtMillis - now()).coerceAtLeast(1)) }
    }
    @Synchronized fun rejected(request: MobileRequest, mode: MobileTransport, error: MobileAccessException): MobileAccessIssue {
        val issue = MobileAccessIssue(error.reason, now() + error.cooldownMs.coerceIn(1, 86_400_000), error.status)
        entries.entries.removeAll { it.value.retryAtMillis <= now() }
        entries[key(request, mode, error.reason)] = issue
        while (entries.size > 128) entries.remove(entries.keys.first())
        persist()
        return issue
    }
    @Synchronized fun succeeded(request: MobileRequest, mode: MobileTransport) {
        entries.remove(resource(request, mode))
        entries.remove("transport:${mode.name}")
        entries.entries.removeAll { it.value.retryAtMillis <= now() }
        persist()
    }
    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            val saved = Properties().apply { this@MobileAccessLedger.entries.forEach { (key, issue) -> setProperty(key, "${issue.reason},${issue.retryAtMillis},${issue.status ?: ""}") } }
            temporary.outputStream().use { saved.store(it, "RushB connection cooldowns") }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
