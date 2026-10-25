package dev.matchscope.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*
import java.time.ZoneId
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

internal fun newsTime(value: String, zone: ZoneId) = NewsParser.instant(value)?.atZone(zone)?.format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"))
    ?: runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) }.getOrDefault(value)

@Composable
internal fun NewsScreen(state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    LaunchedEffect(Unit) { vm.state.first { !it.busy }; vm.loadNews() }
    NewsListContent(state, vm::refreshNews, { vm.loadNewsMonth(it) }, open, vm::prepareNewsPreviews)
}

@Composable
internal fun NewsListContent(state: ScopeState, refresh: () -> Unit, changeMonth: (YearMonth) -> Unit, open: (String) -> Unit,
    previews: suspend (List<String>) -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable(query, state.newsMonth.toString()) { mutableIntStateOf(30) }
    val relatedOnly = state.news?.items.isNullOrEmpty()
    val available = (if (relatedOnly) state.relatedNews.filter {
        NewsParser.instant(it.publishedAt)?.atZone(state.zone)?.let(YearMonth::from) == state.newsMonth
    } else state.news!!.items)
    val news = available.filter { it.title.contains(query, true) }
    val failure = state.newsFailure
    val list = rememberLazyListState()
    val visibleUrls by remember(list) { derivedStateOf { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.filter(NewsParser::allowed) } }
    LaunchedEffect(visibleUrls, state.busy, state.networkAvailable) {
        delay(400)
        if (!state.busy && state.networkAvailable != false) previews(visibleUrls.filter { url -> available.firstOrNull { it.url == url }?.imageUrl == null })
    }
    LazyColumn(Modifier.testTag("news-list"), state = list, contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                ActionIcon("back", "上月新闻", !state.busy && state.newsMonth > YearMonth.of(2002, 1)) { changeMonth(state.newsMonth.minusMonths(1)) }
                Text("${state.newsMonth.year}年${state.newsMonth.monthValue}月", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (state.newsMonth != YearMonth.now()) TextButton({ changeMonth(YearMonth.now()) }, enabled = !state.busy) { Text("本月") }
                ActionIcon("next", "下月新闻", !state.busy && state.newsMonth < YearMonth.now()) { changeMonth(state.newsMonth.plusMonths(1)) }
            }
        }
        item { Row { Box(Modifier.weight(1f)) { SearchField(query, "搜索新闻") { query = it } }; ActionIcon("refresh", "刷新资讯", !state.busy, onClick = refresh) } }
        if (state.networkAvailable == false) item { Text("离线 · 仅显示本机记录", fontSize = 11.sp, color = Quiet) }
        if (news.isNotEmpty() && relatedOnly) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已缓存新闻", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (failure != null) Text("资讯源暂不可用 · 已缓存的战队与选手新闻", color = Quiet, fontSize = 11.sp)
                }
                if (failure != null) TextButton(refresh, enabled = !state.busy) { Text("全部新闻") }
            }
        }
        if (news.isEmpty()) item {
            when {
                query.isNotBlank() && available.isNotEmpty() -> EmptyCard("没有匹配的新闻", action = "清除筛选") { query = "" }
                state.busy -> EmptyCard("正在读取新闻…")
                failure != null -> NewsFailureCard(failure)
                query.isNotBlank() -> EmptyCard("没有匹配的新闻", action = "清除筛选") { query = "" }
                else -> EmptyCard("暂无新闻")
            }
        }
        items(news.take(limit), key = { it.url }) { article ->
            val parsed = state.articles[article.url]?.value
            NewsRow(article.copy(imageUrl = article.imageUrl ?: parsed?.coverUrl ?: parsed?.blocks?.firstNotNullOfOrNull { it.imageUrl },
                publishedAt = parsed?.publishedAt?.takeIf(String::isNotBlank) ?: article.publishedAt,
                hasCachedBody = article.hasCachedBody || parsed != null), state.zone, open, state.networkAvailable == false)
        }
        if (news.size > limit) item { TextButton({ limit += 30 }, Modifier.fillMaxWidth()) { Text("更多新闻") } }
        state.news?.takeIf { it.warning != null && news.isNotEmpty() }?.let { item { UpdateWarning(it.stamp, state.zone, state.zoneLabel) } }
    }
}

@Composable
internal fun NewsRow(article: NewsItem, zone: ZoneId, open: (String) -> Unit, showOfflineAvailability: Boolean = false) {
    CardBlock(Modifier.clickable { open("article|${article.url}") }) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(article.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                val metadata = listOfNotNull(article.publishedAt.takeIf(String::isNotBlank)?.let { newsTime(it, zone) },
                    if (showOfflineAvailability) (if (article.hasCachedBody) "正文已缓存" else "仅标题") else null).joinToString(" · ")
                if (metadata.isNotBlank()) Text(metadata, fontSize = 10.sp, color = Quiet)
            }
            NewsThumbnail(article.imageUrl)
        }
    }
}

@Composable
private fun NewsThumbnail(url: String?) {
    val revision = LocalArtworkRevision.current
    val sources = remember(url, revision) { MediaUrls.candidates(url) }
    var index by remember(url, revision) { mutableIntStateOf(0) }
    val source = sources.getOrNull(index)
    var loaded by remember(source, revision) { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(source, context, density) {
        ImageRequest.Builder(context).data(source)
            .size(with(density) { 88.dp.roundToPx() }, with(density) { 66.dp.roundToPx() }).build()
    }
    Box(Modifier.size(88.dp, 74.dp).clip(RoundedCornerShape(9.dp)).background(PanelRaised).testTag("news-cover"), contentAlignment = Alignment.Center) {
        if (!loaded) Glyph("news", Quiet.copy(alpha = .45f), 25.dp)
        if (source != null) key(source, revision) { AsyncImage(request, "新闻封面", Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, onSuccess = { loaded = true }, onError = { loaded = false; index++ }) }
    }
}

@Composable
internal fun NewsDetailScreen(url: String, state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    LaunchedEffect(url) { vm.state.first { !it.busy }; vm.loadArticle(url) }
    NewsArticleContent(url, state, open)
}

@Composable
internal fun NewsArticleContent(url: String, state: ScopeState, open: (String) -> Unit) {
    val result = state.articles[url]
    val article = result?.value
    val preview = state.articlePreview(url)
    val title = article?.title ?: preview?.title
    val publishedAt = article?.publishedAt?.takeIf(String::isNotBlank) ?: preview?.publishedAt.orEmpty()
    LazyColumn(Modifier.testTag("news-article"), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (state.networkAvailable == false) item(key = "offline") { Text("离线 · 仅显示本机记录", fontSize = 11.sp, color = Quiet) }
        title?.let { item(key = "title") { Text(it, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold) } }
        val byline = listOfNotNull(article?.author, newsTime(publishedAt, state.zone)).filter(String::isNotBlank).joinToString(" · ")
        if (byline.isNotBlank()) item(key = "byline") { Text(byline, color = Quiet, fontSize = 12.sp) }
        if (article == null) item {
            if (state.loadingArticle == url || state.articleFailures[url] == null) EmptyCard("正在读取正文…")
            else NewsFailureCard(state.articleFailures[url] ?: "新闻暂未载入")
        }
        else {
            if (result.warning != null) item { UpdateWarning(result.stamp, state.zone, state.zoneLabel) }
            items(article.blocks) { block ->
                when (block.kind) {
                    "image" -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        NewsArticleImage(block.imageUrl, block.text, article.url)
                        if (block.text.isNotBlank()) Text(block.text, fontSize = 11.sp, color = Quiet, lineHeight = 17.sp)
                    }
                    "table" -> NewsTable(block, open)
                    "quote" -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PanelRaised).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("“", fontSize = 28.sp, color = Quiet)
                        Text(newsAnnotated(block, open), fontSize = 15.sp, lineHeight = 25.sp, color = Ink)
                    }
                    "match" -> if (block.match != null) NewsMatchCard(block.match, open)
                        else CardBlock { Column(Modifier.padding(14.dp)) { Text(block.text, fontSize = 13.sp, lineHeight = 21.sp); NewsLinks(block.links, open) } }
                    "statistics" -> CardBlock {
                        block.team?.let { team -> Row(Modifier.fillMaxWidth().clickable { open("team|${team.id}") }.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            TeamLogo(team, 22.dp); Text(team.name, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("全场数据", fontSize = 10.sp, color = Quiet)
                        } }
                        StatisticsColumnHeader(block.statistics)
                        StatisticsTeamRows(block.statistics, false, open)
                    }
                    else -> Text(newsAnnotated(block, open), fontSize = if (block.kind == "heading") 17.sp else 16.sp, lineHeight = 27.sp,
                        fontWeight = if (block.kind == "heading") FontWeight.SemiBold else FontWeight.Normal, color = if (block.kind == "quote") Quiet else Ink)
                }
            }
            if (article.related.isNotEmpty()) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(color = Stroke); SectionLabel("相关阅读"); NewsLinks(article.related, open)
            } }
        }
        item { SourceLink(url, "浏览器打开原文 ↗") }
    }
}

@Composable
private fun NewsTable(block: NewsBlock, open: (String) -> Unit) {
    val columns = block.rows.maxOfOrNull { it.size } ?: return
    CardBlock {
        block.rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().background(if (index == 0) PanelRaised else Panel).padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (columns <= 5) repeat(columns) { column ->
                    Text(row.getOrNull(column).orEmpty(), Modifier.weight(if (column == 0 && columns > 2) .6f else 1f),
                        fontSize = if (index == 0) 11.sp else 12.sp, lineHeight = 18.sp,
                        fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index == 0) Quiet else Ink)
                } else Text(row.joinToString(" · "), fontSize = 12.sp, lineHeight = 20.sp)
            }
            if (index < block.rows.lastIndex) HorizontalDivider(color = Background)
        }
        if (block.links.isNotEmpty()) Box(Modifier.padding(horizontal = 10.dp)) { NewsLinks(block.links, open) }
    }
}

@Composable
private fun NewsMatchCard(match: NewsMatchSummary, open: (String) -> Unit) {
    CardBlock(Modifier.testTag("news-match").clickable { newsRoute(match.url)?.let(open) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                match.event?.let { event -> Text(event.text, Modifier.weight(1f).clickable { newsRoute(event.url)?.let(open) }, fontSize = 12.sp, color = Quiet) }
                if (match.format.isNotBlank()) Text(match.format, fontSize = 11.sp, color = Quiet)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun team(team: Team, modifier: Modifier) {
                    Column(modifier.clickable { open("team|${team.id}") }, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        TeamLogo(team, 26.dp)
                        val won = match.leftScore != null && match.rightScore != null &&
                            (if (team.id == match.left.id) match.leftScore > match.rightScore else match.rightScore > match.leftScore)
                        Text(team.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (won) Green else Ink,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                team(match.left, Modifier.weight(1f))
                Text("${match.leftScore ?: "—"} : ${match.rightScore ?: "—"}", fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                team(match.right, Modifier.weight(1f))
            }
            if (match.maps.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(color = Stroke)
                match.maps.forEach { map -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val leftWon = map.left != null && map.right != null && map.left > map.right
                    val rightWon = map.left != null && map.right != null && map.right > map.left
                    Text(map.left?.toString() ?: "—", Modifier.weight(1f), fontSize = 12.sp, color = if (leftWon) Green else Ink,
                        fontWeight = if (leftWon) FontWeight.Bold else FontWeight.Normal, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(mapLabel(map.name), Modifier.weight(1f), fontSize = 12.sp, color = Quiet, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(map.right?.toString() ?: "—", Modifier.weight(1f), fontSize = 12.sp, color = if (rightWon) Green else Ink,
                        fontWeight = if (rightWon) FontWeight.Bold else FontWeight.Normal, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                } }
            }
            Text("比赛详情 ›", Modifier.fillMaxWidth(), fontSize = 11.sp, color = Quiet, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun NewsArticleImage(url: String?, description: String, articleUrl: String) {
    val revision = LocalArtworkRevision.current
    val sources = remember(url, revision) { MediaUrls.candidates(url) }
    var index by remember(url, revision) { mutableIntStateOf(0) }
    val source = sources.getOrNull(index)
    if (source == null) {
        SourceLink(MediaUrls.validated(url) ?: articleUrl, "配图暂未载入 · 查看配图 ↗")
        return
    }
    var loaded by remember(source, revision) { mutableStateOf(false) }
    var ratio by remember(source) { mutableFloatStateOf(16f / 9f) }
    val context = LocalContext.current
    val request = remember(source, context) { ImageRequest.Builder(context).data(source).size(1600, 1600).build() }
    key(source, revision) { AsyncImage(request, description.ifBlank { "新闻配图" },
        if (loaded) Modifier.fillMaxWidth().aspectRatio(ratio) else Modifier.size(0.dp), contentScale = ContentScale.Fit,
        onSuccess = { result ->
            val image = result.result.image
            if (image.width > 0 && image.height > 0) ratio = image.width.toFloat() / image.height
            loaded = true
        }, onError = { index++ }) }
    if (!loaded) Text("加载配图…", fontSize = 12.sp, color = Quiet, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
internal fun NewsFailureCard(reason: String) {
    val restricted = listOf("403", "429", "限制", "拒绝", "验证", "冷却").any { it in reason }
    val cancelled = reason == "读取已取消"
    val offline = reason.contains("离线")
    val stillBlocked = reason.startsWith("网站仍拒绝访问")
    CardBlock { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (offline) "正文未缓存" else if (cancelled) "读取已取消" else if (stillBlocked) "网站仍拒绝访问" else if (restricted) "网站暂时限制访问" else "新闻读取失败", fontWeight = FontWeight.SemiBold)
        if (!cancelled) Text(if (offline) "联网后可读取正文" else if (stillBlocked) "请稍后重试，或在浏览器中打开原文" else if (restricted) "可打开网站完成验证后重试" else "请检查网络后重试", fontSize = 12.sp, color = Quiet)
        if (stillBlocked || offline) LocalRecovery.current?.let { actions -> FilledTonalButton(actions.retry, enabled = actions.enabled) { Text("重试") } }
        else RecoveryButtons(verifyFirst = restricted)
    } }
}

@Composable
private fun newsAnnotated(block: NewsBlock, open: (String) -> Unit): androidx.compose.ui.text.AnnotatedString {
    val linkColor = Accent
    return buildAnnotatedString {
    val text = if (block.kind == "bullet") "• ${block.text}" else block.text
    append(text)
    if (block.kind == "bullet") addStyle(ParagraphStyle(textIndent = TextIndent(restLine = 12.sp)), 0, length)
    val covered = mutableListOf<IntRange>()
    block.links.filter { it.text.isNotBlank() }.forEach { link ->
        val route = newsRoute(link.url) ?: return@forEach
        var start = text.indexOf(link.text)
        while (start >= 0) {
            val range = start until start + link.text.length
            if (covered.none { range.first <= it.last && it.first <= range.last }) {
                addLink(LinkAnnotation.Clickable(link.url, TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)), { open(route) }), range.first, range.last + 1)
                covered.add(range)
            }
            start = text.indexOf(link.text, range.last + 1)
        }
    }
}
}

internal fun newsRoute(url: String): String? {
    if (!HltvAccess.allowed(url)) return null
    if (NewsParser.allowed(url)) return "article|$url"
    val match = Regex("^/(team|player|events|matches)/([0-9]+)(?:/|$)").find(java.net.URI(url).path) ?: return null
    val kind = when (match.groupValues[1]) { "events" -> "event"; "matches" -> "match"; else -> match.groupValues[1] }
    return "$kind|${match.groupValues[2]}"
}

@Composable
private fun NewsLinks(links: List<NewsLink>, open: (String) -> Unit) {
    links.forEach { link ->
        val route = newsRoute(link.url)
        if (route != null) TextButton({ open(route) }) { Text(link.text, fontSize = 12.sp) }
        else SourceLink(link.url, link.text)
    }
}
