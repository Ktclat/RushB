package dev.matchscope.app.ui

internal enum class LogoTone { ORIGINAL, DARK, LIGHT }

/** Reuse the website ranking's observed light-surface asset by team ID; preserve signed URLs and a source fallback. */
internal fun teamLogoSources(teamId: String, source: String?, lightSources: Map<String, String>, dark: Boolean): List<String> =
    (if (dark) listOfNotNull(source, lightSources[teamId]) else listOfNotNull(lightSources[teamId], source)).distinct()

/** Adapt only transparent, uniformly black/white artwork. Preserve colored and two-tone identities. */
internal fun logoTone(pixels: IntArray): LogoTone {
    val opaque = pixels.filter { it ushr 24 >= 200 }
    if (opaque.size < 8 || pixels.count { it ushr 24 < 16 } < pixels.size / 20) return LogoTone.ORIGINAL
    var dark = true
    var light = true
    for (pixel in opaque) {
        val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
        if (maxOf(r, g, b) - minOf(r, g, b) > 12) return LogoTone.ORIGINAL
        dark = dark && maxOf(r, g, b) <= 112
        light = light && minOf(r, g, b) >= 192
    }
    return when { dark -> LogoTone.DARK; light -> LogoTone.LIGHT; else -> LogoTone.ORIGINAL }
}
