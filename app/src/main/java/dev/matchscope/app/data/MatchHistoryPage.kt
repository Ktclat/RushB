package dev.matchscope.app.data

import java.net.URI
import java.net.URLDecoder

data class MatchHistoryPage(val items: List<Match>, val nextUrl: String?)

internal fun historyQueryMatches(url: String, teamIds: Set<String>, allTeams: Boolean): Boolean = runCatching {
    if (!HltvAccess.allowed(url)) return false
    val uri = URI(url)
    if (uri.path != "/results" || uri.fragment != null) return false
    val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }
    teamIds.isNotEmpty() && teamIds.all { it.toLongOrNull()?.let { id -> id > 0 } == true } &&
        query.filter { it.first == "team" }.map { it.second }.toSet() == teamIds &&
        (!allTeams || query.filter { it.first == "requireAllTeams" }.let { it.size == 1 && it.single().second.isEmpty() })
}.getOrDefault(false)
