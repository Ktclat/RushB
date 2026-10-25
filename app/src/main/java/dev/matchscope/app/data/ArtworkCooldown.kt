package dev.matchscope.app.data

import java.net.URI

/** Retrying a view must not retry a refused asset through every signed size variant. */
internal class ArtworkCooldown(private val now: () -> Long = System::currentTimeMillis) {
    private val until = linkedMapOf<String, Long>()
    private fun asset(url: String, access: String) = URI(url).let { "$access:${it.host}${it.path}" }
    @Synchronized fun blocked(url: String, access: String): Boolean =
        maxOf(until[asset(url, access)] ?: 0, until[URI(url).host] ?: 0) > now()

    @Synchronized fun record(url: String, access: String, status: Int, retryAfter: String? = null) {
        if (status !in setOf(403, 429)) return
        val time = now()
        until.entries.removeAll { it.value <= time }
        if (until.size >= 512) until.remove(until.keys.first())
        val key = if (status == 429) URI(url).host else asset(url, access)
        until[key] = time + if (status == 429) MobileResponsePolicy.retryDelay(retryAfter, time) else 300_000
    }
}
