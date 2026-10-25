package dev.matchscope.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.ScopeViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Opt-in acceptance against the public website; no injected documents or desktop relay. */
class WebsiteNewsNetworkTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    @Test fun monthlyNewsAndArticleUseThePhoneReader() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("websiteNews") == "true")
        lateinit var vm: ScopeViewModel
        app.runOnUiThread {
            vm = ViewModelProvider(app.activity)[ScopeViewModel::class.java]
            vm.configureRelay(false, "")
        }
        suspend fun finish(started: () -> Boolean = { true }) {
            app.waitForIdle()
            withTimeout(110_000) {
                while (!started() || vm.state.value.busy || vm.browser.request.value != null) {
                    app.mainClock.advanceTimeBy(50); delay(100)
                }
            }
            app.waitForIdle()
        }
        val report = JSONObject().put("began", Instant.now().toString()).put("version", BuildConfig.VERSION_NAME).put("relayEnabled", false)
        try {
            app.runOnUiThread { vm.refreshNews() }; finish()
            val feed = vm.state.value.news ?: error(vm.state.value.newsFailure ?: "No news")
            report.put("news", JSONObject().put("stamp", Records.stamp(feed.stamp)).put("count", feed.items.size)
                .put("warning", feed.warning).put("first", feed.items.first().title).put("firstUrl", feed.items.first().url)
                .put("last", feed.items.last().title))
            assertEquals(DataOrigin.NETWORK, feed.stamp.origin)
            assertNull(feed.warning)
            assertTrue(feed.items.size > 30)
            assertEquals(vm.state.value.newsMonth, NewsParser.archiveMonth(feed.stamp.url))
            app.onNodeWithContentDescription("新闻资讯").performClick(); finish()
            app.onNodeWithText(feed.items.first().title).assertIsDisplayed()
            app.saveEvidence("v0102-news-live")
            app.onNodeWithText(feed.items.first().title).performClick()
            finish { vm.state.value.loadingArticle != null || feed.items.first().url in vm.state.value.articles || feed.items.first().url in vm.state.value.articleFailures }
            val article = vm.state.value.articles[feed.items.first().url]
            report.put("article", JSONObject().put("url", feed.items.first().url)
                .put("stamp", article?.stamp?.let(Records::stamp)).put("blocks", article?.value?.blocks?.size)
                .put("warning", article?.warning ?: vm.state.value.articleFailures[feed.items.first().url]))
            app.saveEvidence("v0102-news-article-live")
            assertNotNull("Article: ${vm.state.value.articleFailures}", article)
            assertEquals(DataOrigin.NETWORK, article!!.stamp.origin)
            assertNull(article.warning)
            assertTrue(article.value.blocks.any { it.kind == "paragraph" })
            report.put("success", true)
        } catch (failure: Throwable) {
            report.put("success", false).put("error", failure.message)
            throw failure
        } finally {
            report.put("finished", Instant.now().toString())
            File(app.activity.filesDir, "website-news-v0102.json").writeText(report.toString(2))
        }
    }
}
