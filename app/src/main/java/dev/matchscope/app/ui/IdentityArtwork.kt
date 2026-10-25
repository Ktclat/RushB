package dev.matchscope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import dev.matchscope.app.data.*

internal val LocalArtworkRevision = staticCompositionLocalOf { 0L }
internal val LocalLightTeamLogos = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

@Composable
fun IdentityImage(url: String?, description: String, modifier: Modifier = Modifier, adaptiveMonochrome: Boolean = false, darkSurface: Boolean? = null, alternatives: List<String> = emptyList(), onUnavailable: () -> Unit = {}, fallback: @Composable () -> Unit) {
    val revision = LocalArtworkRevision.current
    val sources = remember(url, alternatives, revision) { (listOfNotNull(url) + alternatives).flatMap(MediaUrls::candidates).distinct() }
    var index by remember(sources, revision) { mutableIntStateOf(0) }
    val source = sources.getOrNull(index)
    var loaded by remember(source, revision) { mutableStateOf(false) }
    var tone by remember(source, revision) { mutableStateOf(LogoTone.ORIGINAL) }
    val context = LocalContext.current
    val request = remember(source, adaptiveMonochrome, context) {
        if (adaptiveMonochrome) ImageRequest.Builder(context).data(source).allowHardware(false).build() else source
    }
    val dark = darkSurface ?: LocalRushColors.current.dark
    val filter = if (adaptiveMonochrome && (tone == LogoTone.DARK && dark || tone == LogoTone.LIGHT && !dark))
        ColorFilter.tint(if (dark) DarkColors.ink else LightColors.ink) else null
    Box(modifier.clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (!loaded) fallback()
        if (source != null) key(source, revision) { AsyncImage(request, null,
            Modifier.matchParentSize(), contentScale = ContentScale.Fit, alignment = Alignment.BottomCenter, colorFilter = filter,
            onSuccess = {
                if (adaptiveMonochrome) tone = sampleLogoTone(it.result.image)
                loaded = true
            }, onError = { loaded = false; if (index < sources.lastIndex) index++ else onUnavailable() }) }
    }
}

@Composable
fun TeamLogo(team: Team, size: Dp = 42.dp, darkSurface: Boolean? = null) {
    val dark = darkSurface ?: LocalRushColors.current.dark
    val sources = teamLogoSources(team.id, team.logoUrl, LocalLightTeamLogos.current, dark)
    IdentityImage(sources.firstOrNull(), "${team.name} 徽标", Modifier.size(size).padding(size * .04f),
        adaptiveMonochrome = true, darkSurface = dark, alternatives = sources.drop(1)) {
        Text(team.monogram, color = if (dark) DarkColors.ink else LightColors.ink, fontWeight = FontWeight.Bold,
            fontSize = (size.value * .24f).coerceIn(6f, 23f).sp, maxLines = 1)
    }
}

@Composable
fun PlayerPhoto(player: PlayerRef, modifier: Modifier = Modifier) {
    IdentityImage(player.photoUrl, "${player.name} 照片", modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(8.dp)).background(PanelRaised)) {
        Glyph("profile", Quiet, 28.dp)
    }
}

@Composable
fun EventLogo(event: Tournament, size: Dp = 42.dp) {
    IdentityImage(event.logoUrl, "${event.name} 徽标", Modifier.size(size).padding(size * .06f), adaptiveMonochrome = true) {
        Glyph("matches", Quiet, size * .6f)
    }
}

private fun sampleLogoTone(image: coil3.Image): LogoTone = runCatching {
    if (image.width <= 0 || image.height <= 0) return@runCatching LogoTone.ORIGINAL
    val sample = createBitmap(32, 32)
    try {
        val canvas = android.graphics.Canvas(sample)
        canvas.scale(32f / image.width, 32f / image.height)
        image.draw(canvas)
        val pixels = IntArray(32 * 32)
        sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        logoTone(pixels)
    } finally { sample.recycle() }
}.getOrDefault(LogoTone.ORIGINAL)

@Composable
fun PlayerRow(player: PlayerRef, onOpen: () -> Unit, trailing: @Composable () -> Unit = { Glyph("next", Quiet, 16.dp) }) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerPhoto(player)
        Text(player.name, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
fun PlayerPoster(player: PlayerRef, onOpen: () -> Unit) {
    Column(Modifier.width(120.dp).clip(RoundedCornerShape(8.dp)).background(Panel).clickable(onClick = onOpen)) {
        PlayerPhoto(player, Modifier.fillMaxWidth().height(146.dp))
        Text(player.name, Modifier.padding(12.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EventBanner(event: Tournament) {
    Column {
    RemoteCover(event.bannerUrl, "${event.name} 海报", 4.2f)
    Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(PanelRaised, Panel))).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        EventLogo(event, 32.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(event.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
        }
    }
    }
}

@Composable
internal fun RemoteCover(url: String?, description: String, ratio: Float = 16f / 9f) {
    val revision = LocalArtworkRevision.current
    val sources = remember(url, revision) { MediaUrls.candidates(url) }
    var index by remember(sources, revision) { mutableIntStateOf(0) }
    sources.getOrNull(index)?.let { source ->
        key(source, revision) { AsyncImage(source, description, Modifier.fillMaxWidth().aspectRatio(ratio), contentScale = ContentScale.Crop,
            onError = { index++ }) }
    }
}
