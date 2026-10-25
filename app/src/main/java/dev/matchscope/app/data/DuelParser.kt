package dev.matchscope.app.data

import org.jsoup.nodes.Document
import java.net.URI

object DuelParser {
    private val statisticsPath = Regex("^/stats/matches/(?:performance/)?(mapstatsid/)?([1-9][0-9]*)/([A-Za-z0-9_-]+)$")

    /** A verified Performance DOM is required before this adapter may return duel counts. */
    @Suppress("UNUSED_PARAMETER")
    fun parse(doc: Document): MatchDuels {
        // Available HTML captures do not contain the matrix. Never infer it from scoreboard K-D.
        throw SourceUnavailable("对位数据暂不可用")
    }

    /** Uses a source-provided stats ID. Main match IDs cannot be converted to stats IDs. */
    fun performanceUrl(statsUrl: String): String? {
        val uri = publicUri(statsUrl) ?: return null
        val path = statisticsPath.matchEntire(uri.rawPath) ?: return null
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "https://www.hltv.org/stats/matches/performance/${path.groupValues[1]}${path.groupValues[2]}/${path.groupValues[3]}$query"
    }

    private fun publicUri(raw: String): URI? = runCatching {
        URI(raw.trim()).takeIf {
            it.scheme.equals("https", ignoreCase = true) &&
                it.host?.lowercase() in setOf("hltv.org", "www.hltv.org") &&
                it.rawUserInfo == null && it.port == -1 && it.rawFragment == null
        }
    }.getOrNull()
}
