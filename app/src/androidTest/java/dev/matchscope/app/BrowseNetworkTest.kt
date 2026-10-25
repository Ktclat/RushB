package dev.matchscope.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.ScopeViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Explicit live acceptance: real phone browser, production repository, no imported payload/cookies. */
class BrowseNetworkTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    @Test fun rankingsRssAndArticleLoadThroughThePhone() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("browseNetwork") == "true")
        lateinit var vm: ScopeViewModel
        app.runOnUiThread {
            vm = ViewModelProvider(app.activity)[ScopeViewModel::class.java]
            vm.configureRelay(false, "")
        }
        suspend fun finish(stage: String) {
            withTimeout(110_000) { while (vm.state.value.busy) { app.mainClock.advanceTimeBy(50); delay(100) } }
            assertFalse("$stage: ${vm.state.value.message.orEmpty()}", vm.state.value.requestFailed)
        }
        val began = Instant.now()
        val newsOnly = InstrumentationRegistry.getArguments().getString("browseStage") == "news"
        if (!newsOnly) { app.runOnUiThread { vm.refreshRankings() }; finish("rankings") }
        val ranks = vm.state.value.rankings
        if (!newsOnly) {
            assertEquals("rankings", DataOrigin.NETWORK, ranks.stamp.origin); assertNull(ranks.warning)
            assertTrue(ranks.items.size >= 30)
            assertEquals(1, ranks.items.first().rank)
            assertEquals(ranks.items.size, ranks.items.distinctBy { it.team.id }.size)
        }
        app.runOnUiThread { vm.refreshNews() }; finish("rss")
        val news = vm.state.value.news!!
        assertEquals(DataOrigin.NETWORK, news.stamp.origin); assertNull(news.warning)
        assertTrue(news.items.size >= 5); assertTrue(news.items.any { it.imageUrl != null })
        val articleUrl = news.items.first().url
        app.runOnUiThread { vm.loadArticle(articleUrl, true) }; finish("article")
        val article = vm.state.value.articles.getValue(articleUrl)
        assertEquals(DataOrigin.NETWORK, article.stamp.origin); assertNull(article.warning)
        assertTrue(article.value.blocks.count { it.kind == "paragraph" } >= 2)
        assertTrue(article.value.blocks.any { it.kind == "image" })
        File(app.activity.filesDir, "browse-network-v070.json").writeText(JSONObject()
            .put("began", began.toString()).put("finished", Instant.now().toString()).put("relay", vm.state.value.relayEnabled).put("newsOnly", newsOnly)
            .put("rankings", JSONObject().put("count", ranks.items.size).put("stamp", Records.stamp(ranks.stamp))
                .put("leaders", JSONArray(ranks.items.take(5).map { JSONObject().put("rank", it.rank).put("teamId", it.team.id).put("name", it.team.name) })))
            .put("rss", JSONObject().put("count", news.items.size).put("images", news.items.count { it.imageUrl != null }).put("stamp", Records.stamp(news.stamp)))
            .put("article", JSONObject().put("stamp", Records.stamp(article.stamp)).put("blocks", article.value.blocks.size)
                .put("images", article.value.blocks.count { it.kind == "image" }).put("links", article.value.blocks.sumOf { it.links.size }))
            .toString(2))
        app.onNodeWithContentDescription("新闻资讯").performClick()
        finish("news navigation")
        app.onNodeWithText(news.items.first().title).performClick()
        finish("article navigation")
        app.onNodeWithTag("news-article").assertIsDisplayed()
        app.saveEvidence("v070-news-live")
    }

    @Test fun nativeRssProbe() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rssHttpsProbe") == "true")
        val report = JSONObject().put("began", Instant.now().toString()).put("transport", "HTTPS").put("relay", false)
        try {
            val raw = withContext(Dispatchers.IO) { fetchHttps("https://www.hltv.org/rss/news") }
            val news = NewsParser.rss(raw)
            assertTrue(news.isNotEmpty())
            report.put("success", true).put("count", news.size).put("images", news.count { it.imageUrl != null })
        } catch (failure: Exception) {
            report.put("success", false).put("error", failure.message)
            throw failure
        } finally {
            report.put("finished", Instant.now().toString())
            File(app.activity.filesDir, "rss-https-probe-v070.json").writeText(report.toString(2))
        }
    }
}
