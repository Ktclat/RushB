package dev.matchscope.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NewsUiTest {
    @get:Rule val app = createComposeRule()
    private val url = "https://www.hltv.org/news/43123/current-title"
    private val preview = NewsItem("CS2 article headline", "https://www.hltv.org/news/43123/previous-title", "2026-10-20T12:00:00Z")

    @Test fun offlineNewsDistinguishesKnownBodyFromHeadlineOnlyAndUsesTheExactLocalTime() {
        val items = listOf(preview.copy(hasCachedBody = true), NewsItem("Unopened story", "https://www.hltv.org/news/43124/unopened", "2026-10-20"))
        val state = ScopeState(news = Feed(items, SeedSnapshot.matches.stamp), networkAvailable = false, timezone = "Asia/Hong_Kong")
        app.setContent { MatchScopeTheme { NewsListContent(state, {}, {}, {}) } }
        app.onNodeWithText("2026.10.20 20:00 · 正文已缓存").assertIsDisplayed()
        app.onNodeWithText("2026.10.20 · 仅标题").assertIsDisplayed()
    }

    @Test fun embeddedMatchAndStatisticsKeepScoresAlignedAndOpenTheCorrectEntities() {
        val raw = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("hltv/news-match-2026-10-24.html").bufferedReader().use { it.readText() }
        val article = NewsParser.article(raw, url)
        var route = ""
        val state = ScopeState(articles = mapOf(url to Sourced(article, SeedSnapshot.matches.stamp)))
        app.setContent { MatchScopeTheme { NewsArticleContent(url, state) { route = it } } }
        app.onNodeWithTag("news-article").performScrollToNode(hasTestTag("news-match"))
        app.onNodeWithText("1 : 2").assertIsDisplayed()
        app.onNodeWithTag("news-match").performClick()
        app.runOnIdle { assertEquals("match|2399639", route) }
        app.onNodeWithTag("news-article").performScrollToNode(hasTestTag("stats-player-23295"))
        app.onNodeWithTag("stats-player-23295").assertIsDisplayed().performClick()
        app.runOnIdle { assertEquals("player|23295", route) }
        app.onNodeWithText("61-51").assertIsDisplayed()
        app.onAllNodesWithText("72.7%", useUnmergedTree = true).onFirst().assertExists()
    }

    @Test fun searchEmptyStateCanClearItsFilterEvenWhenTheNewsSourceIsUnavailable() {
        val state = ScopeState(news = Feed(listOf(preview), SeedSnapshot.matches.stamp), newsFailure = "HTTP 403")
        app.setContent { MatchScopeTheme { NewsListContent(state, {}, {}, {}) } }
        app.onNode(hasSetTextAction()).performTextInput("no-matching-headline")
        app.onNodeWithText("没有匹配的新闻").assertIsDisplayed()
        app.onNodeWithText("网站暂时限制访问").assertDoesNotExist()
        app.onNodeWithText("清除筛选").performClick()
        app.onNodeWithText(preview.title).assertIsDisplayed()
    }

    @Test fun monthControlsSelectTheRequestedMonthAndDisableFutureOrBusyNavigation() {
        val current = java.time.YearMonth.now()
        var state by mutableStateOf(ScopeState(newsMonth = current))
        var selected: java.time.YearMonth? = null
        app.setContent { MatchScopeTheme { NewsListContent(state, {}, { selected = it }, {}) } }
        app.onNodeWithContentDescription("下月新闻").assertIsNotEnabled()
        app.onNodeWithContentDescription("上月新闻").performClick()
        app.runOnIdle { assertEquals(current.minusMonths(1), selected); state = state.copy(newsMonth = selected!!, busy = true) }
        app.onNodeWithContentDescription("上月新闻").assertIsNotEnabled()
        app.onNodeWithContentDescription("下月新闻").assertIsNotEnabled()
        app.onNodeWithText("本月").assertIsNotEnabled()
        app.runOnIdle { state = state.copy(busy = false) }
        app.onNodeWithText("本月").performClick()
        app.runOnIdle { assertEquals(current, selected) }
    }

    @Test fun websiteDatesKeepDateOnlyPrecisionWhileExactTimesUseTheDeviceZone() {
        assertEquals("2026.10.24", newsTime("2026-10-24", java.time.ZoneId.of("America/Los_Angeles")))
        assertEquals("2026.10.24 08:00", newsTime("2026-10-24T00:00:00Z", java.time.ZoneId.of("Asia/Hong_Kong")))
    }

    @Test fun articleKeepsKnownTitleDuringLoadingFailureRetryAndSuccess() {
        var state by mutableStateOf(ScopeState(news = Feed(listOf(preview), SeedSnapshot.matches.stamp), loadingArticle = url))
        var verification = 0
        app.setContent { MatchScopeTheme { CompositionLocalProvider(LocalRecovery provides RecoveryActions(true, {
            state = state.copy(loadingArticle = url, articleFailures = emptyMap())
        }, { verification++ })) { NewsArticleContent(url, state) {} } } }
        app.onNodeWithText(preview.title).assertIsDisplayed()
        app.onNodeWithText("正在读取正文…").assertIsDisplayed()
        app.runOnIdle { state = state.copy(loadingArticle = null, articleFailures = mapOf(url to "403"), busy = true) }
        app.onNodeWithText(preview.title).assertIsDisplayed()
        app.onNodeWithText("网站暂时限制访问").assertIsDisplayed()
        app.onNodeWithText("打开 HLTV 验证").performClick()
        app.runOnIdle { assertEquals(1, verification) }
        app.onNodeWithText("重试").performClick()
        app.onNodeWithText(preview.title).assertIsDisplayed()
        app.onNodeWithText("网站暂时限制访问").assertDoesNotExist()
        app.onNodeWithText("正在读取正文…").assertIsDisplayed()
        val article = NewsArticle(preview.title, url, "Reporter", preview.publishedAt, listOf(NewsBlock("paragraph", "Actual parsed article text")))
        app.runOnIdle { state = state.copy(loadingArticle = null, articles = mapOf(url to Sourced(article, SeedSnapshot.matches.stamp))) }
        app.onNodeWithText(preview.title).assertIsDisplayed()
        app.onNodeWithText("Actual parsed article text").assertIsDisplayed()
        app.onNodeWithText("正在读取正文…").assertDoesNotExist()
    }

    @Test fun profileHeadlinesMatchArticleIdentityWithoutMatchingAnotherNewsItem() {
        val player = PlayerProfile(PlayerRef("1", "Player"), content = ProfileContent(news = listOf(preview)))
        val state = ScopeState(players = mapOf("1" to Sourced(player, SeedSnapshot.matches.stamp)))
        assertEquals(preview, state.articlePreview(url))
        assertNull(state.articlePreview("https://www.hltv.org/news/43124/current-title"))
        app.setContent { MatchScopeTheme { NewsArticleContent(url, state) {} } }
        app.onNodeWithText(preview.title).assertIsDisplayed()
    }

    @Test fun rejectedVerificationShowsItsOutcomeWithoutOfferingTheSameVerificationLoop() {
        var retries = 0
        val state = ScopeState(news = Feed(listOf(preview), SeedSnapshot.matches.stamp), articleFailures = mapOf(url to "网站仍拒绝访问，请稍后再试。"))
        app.setContent { MatchScopeTheme { CompositionLocalProvider(LocalRecovery provides RecoveryActions(true, { retries++ }, { error("Verification must not repeat") })) {
            NewsArticleContent(url, state) {}
        } } }
        app.onNodeWithText(preview.title).assertIsDisplayed()
        app.onNodeWithText("网站仍拒绝访问").assertIsDisplayed()
        app.onNodeWithText("打开 HLTV 验证").assertDoesNotExist()
        app.onNodeWithText("浏览器打开原文 ↗").assertIsDisplayed()
        app.onNodeWithText("重试").performClick()
        app.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun browserNeverOffersCompletionForAChallengeOrBlockPage() {
        var state by mutableStateOf(BrowserPageState.READY)
        var finished = 0; var closed = 0
        app.setContent { MatchScopeTheme { SourceBrowserActions(state, false, true, {}, { finished++ }, { closed++ }) } }
        app.onNodeWithText("完成并重试").performClick()
        app.runOnIdle { assertEquals(1, finished); state = BrowserPageState.CHALLENGE }
        app.onNodeWithText("完成并重试").assertDoesNotExist()
        app.onNodeWithText("重新加载").assertIsDisplayed()
        app.runOnIdle { state = BrowserPageState.BLOCKED }
        app.onNodeWithText("完成并重试").assertDoesNotExist()
        app.onNodeWithText("重新加载").assertDoesNotExist()
        app.onNodeWithText("返回").performClick()
        app.runOnIdle { assertEquals(1, closed); assertEquals(1, finished) }
    }
}
