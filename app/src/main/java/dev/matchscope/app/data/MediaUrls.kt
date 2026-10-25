package dev.matchscope.app.data

import java.net.URI

/** Preserve the source's signed query; only identity artwork is loaded, never page advertisements. */
object MediaUrls {
    /** A CDN failure may reuse an already observed variant of the very same image asset. */
    fun candidates(raw: String?): List<String> {
        val primary = validated(raw) ?: return emptyList()
        val path = URI(primary).path
        val known = SeedArtwork.teams.values + SeedArtwork.rosters.values.flatMap { it.values.flatten() }.mapNotNull { it.photoUrl } + SeedArtwork.eventLogo
        return (listOf(primary) + MediaAssets.variants(primary) + known.mapNotNull(::validated).filter { URI(it).path == path }).distinct()
    }

    fun validated(raw: String?): String? = runCatching {
        val uri = URI(raw?.trim()?.takeIf(String::isNotEmpty) ?: return null)
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) || uri.fragment != null) return null
        if (uri.host == "www.hltv.org" && uri.rawQuery == null &&
            Regex("^/img/static/maps/(ancient|anubis|cache|dust2|inferno|mirage|nuke|tba)\\.png$").matches(uri.path)) return uri.toASCIIString()
        if (uri.host == "www.hltv.org" && uri.rawQuery == null &&
            (Regex("^/img/static/event/trophies/[0-9]{4}/[0-9]{1,2}\\.png$").matches(uri.path) ||
                Regex("^/img/static/award/[A-Za-z0-9_-]+\\.png$").matches(uri.path))) return uri.toASCIIString()
        if (uri.host != "img-cdn.hltv.org") return null
        if (!Regex("^/(teamlogo|playerbodyshot|playerprofile|eventlogo|eventtrophy|eventbanner|mvpmedal|gallerypicture)/[A-Za-z0-9_-]+\\.(png|jpg|jpeg|webp|svg)$").matches(uri.path)) return null
        uri.toASCIIString()
    }.getOrNull()
}
