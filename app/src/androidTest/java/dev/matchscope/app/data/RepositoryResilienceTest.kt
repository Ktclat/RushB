package dev.matchscope.app.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.time.Instant

class RepositoryResilienceTest {
    @Test fun cachedArticleMetadataSurvivesRestartWithoutChangingArchiveAgeOrClaimingUncachedBodies() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "repository-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = directory }
        val month = java.time.YearMonth.of(2026, 10)
        val archive = NewsParser.archiveUrl(month)
        val html = instrumentation.context.assets.open("hltv/news-archive-2026-10-24.html").bufferedReader().use { it.readText() }
        val headline = NewsParser.index(html, archive).first()
        val at = Instant.parse("2026-10-24T05:31:00Z")
        val image = "https://img-cdn.hltv.org/gallerypicture/example.jpg?s=signed"
        fun writePage(url: String, body: String) {
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            File(directory, "source-cache/$key.txt").apply { parentFile!!.mkdirs(); writeText(body); setLastModified(at.toEpochMilli()) }
        }
        try {
            writePage(archive, html)
            writePage(headline.url, """<meta property="article:published_time" content="2026-10-23T22:55:00Z"><article class="newsitem"><h1>Report</h1><div class="newstext-con"><p>Body.</p><img src="$image"></div></article>""")
            val repository = MatchRepository(context, BrowserSession()) { RelayConfig(false, "") }
            val restored = repository.restoredNewsMonth(month)!!
            assertEquals(at, restored.stamp.capturedAt)
            assertEquals(189, restored.items.size)
            assertEquals("2026-10-23T22:55:00Z", restored.items.first().publishedAt)
            assertEquals(image, restored.items.first().imageUrl)
            assertTrue(restored.items.first().hasCachedBody)
            assertFalse(restored.items[1].hasCachedBody)
            writePage(headline.url, "<html>Just a moment</html>")
            val rejected = MatchRepository(context, BrowserSession()) { RelayConfig(false, "") }.restoredNewsMonth(month)!!
            assertFalse(rejected.items.first().hasCachedBody)
            assertEquals(headline.publishedAt, rejected.items.first().publishedAt)
        } finally { directory.deleteRecursively() }
    }

    @Test fun verifiedNewsArchiveRestoresAllHeadlinesAndCannotBeReplacedByAChallenge() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "repository-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = directory }
        val browser = BrowserSession()
        val month = java.time.YearMonth.of(2026, 10)
        val url = NewsParser.archiveUrl(month)
        val html = instrumentation.context.assets.open("hltv/news-archive-2026-10-24.html").bufferedReader().use { it.readText() }
        suspend fun capture(body: String) = withContext(Dispatchers.Main) {
            val manual = async(start = CoroutineStart.UNDISPATCHED) { browser.browse(url) }
            browser.complete(browser.request.value!!.id, body)
            manual.await()
        }
        try {
            capture(html)
            val repository = MatchRepository(context, browser) { RelayConfig(false, "") }
            val feed = repository.newsMonth(month, true)
            assertEquals(189, feed.items.size)
            assertEquals(DataOrigin.NETWORK, feed.stamp.origin)
            assertNull(browser.request.value)
            val restored = MatchRepository(context, BrowserSession()) { RelayConfig(false, "") }.restoredNewsMonth(month)!!
            assertEquals(feed.items, restored.items)
            assertEquals(feed.stamp.capturedAt, restored.stamp.capturedAt)
            assertEquals(DataOrigin.CACHE, restored.stamp.origin)
            assertNull(repository.restoredNewsMonth(month.minusMonths(1)))
            capture("<html><body>Just a moment</body></html>")
            val rejected = repository.newsMonth(month, true)
            assertEquals(feed.items, rejected.items)
            assertEquals(feed.stamp.capturedAt, rejected.stamp.capturedAt)
            assertNotNull(rejected.warning)
        } finally { directory.deleteRecursively() }
    }

    @Test fun rssVerificationHandsXmlToBackgroundReaderAndRestoresAfterRestart() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "repository-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
        val browser = BrowserSession()
        val url = "https://www.hltv.org/rss/news"
        val xml = "<rss><channel><item><title>Verified story</title><link>https://www.hltv.org/news/43123/title</link></item></channel></rss>"
        try {
            withContext(Dispatchers.Main) {
                val manual = async(start = CoroutineStart.UNDISPATCHED) { browser.browse(url) }
                browser.complete(browser.request.value!!.id, xml)
                manual.await()
            }
            val repository = MatchRepository(context, browser) { RelayConfig(false, "") }
            val feed = repository.news(true)
            assertEquals("Verified story", feed.items.single().title)
            assertNull(browser.request.value)
            val restored = MatchRepository(context, BrowserSession()) { RelayConfig(false, "") }.restoredNews()!!
            assertEquals(feed.items, restored.items)
            assertEquals(feed.stamp.capturedAt, restored.stamp.capturedAt)
            assertEquals(DataOrigin.CACHE, restored.stamp.origin)
        } finally { directory.deleteRecursively() }
    }
    @Test fun verifiedWebsiteSessionDoesNotSwitchMobileRequestsAwayFromHttps() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "repository-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = directory }
        val browser = BrowserSession()
        var httpRequests = 0
        var pageRequests = 0
        val url = "https://www.hltv.org/news/43123/test"
        val repository = MatchRepository(context, browser, mobileMode = { MobileTransport.HTTPS }, pageMode = { MobileTransport.BROWSER }, mobileHttp = MobileFetcher { request ->
            httpRequests++
            val body = instrumentation.context.assets.open("mobile/player-news.json").bufferedReader().use { it.readText() }
            MobileResponse(request.url, 200, mapOf("Content-Type" to "application/json"), body)
        }) { RelayConfig(false, "") }
        val responses = launch(Dispatchers.Main) { browser.request.filterNotNull().collect { request ->
            pageRequests++
            assertNull(request.jsonUrl)
            browser.complete(request.id, "<article class='newsitem'><h1>News</h1><div class='newstext-con'><p>Body.</p></div></article>")
        } }
        try {
            assertEquals("News", repository.article(url, true).value.title)
            val news = repository.mobile.read(MobileRequest.create(MobileRoute.PLAYER_NEWS, "19230"), refresh = true, decode = MobileProfileParser::news)
            assertTrue(news.value.isNotEmpty())
            assertEquals(DataOrigin.NETWORK, news.stamp.origin)
            assertEquals(1, pageRequests)
            assertEquals(1, httpRequests)
        } finally { responses.cancelAndJoin(); directory.deleteRecursively() }
    }
    @Test fun rejectedBracketsKeepTheirOriginalTimeWithoutDiscardingTheNewEvent() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.cacheDir,"repository-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(instrumentation.targetContext) { override fun getFilesDir()=directory }
        fun fixture(name:String)=instrumentation.context.assets.open("mobile/$name.json").bufferedReader().use { it.readText() }
        val event=MobileProfileParser.event(JSONObject(fixture("event")),"8932")
        val oldStamp=SourceStamp("https://www.hltv.org/mobile/EventDetails2?eventId=8932",Instant.now().minusSeconds(3_600),DataOrigin.NETWORK)
        val brackets=listOf("5359","5360","5361","5362").map { EventContentParser.bracket(JSONObject(fixture("bracket-$it"))) }
        RecordStore(File(directory,"records")).write("event-8932",Records.eventProfile(Sourced(event.copy(content=event.content.copy(brackets=brackets)),oldStamp)))
        val browser=BrowserSession()
        var requests=0
        val replies=launch(Dispatchers.Main) {
            browser.request.filterNotNull().collect { request ->
                requests++
                if (request.jsonUrl!=null) browser.complete(request.id,JSONObject().put("state","json-response")
                    .put("jsonUrl",request.jsonUrl).put("responseUrl",request.jsonUrl).put("status",200)
                    .put("headers",JSONObject().put("content-type","application/json")).put("body",fixture("event")).toString())
                else browser.fail(request.id,"blocked",300_000,MobileFailure.BLOCKED)
            }
        }
        try {
            val repository=MatchRepository(context,browser,mobileMode={MobileTransport.BROWSER}) { RelayConfig(false,"") }
            val result=repository.event("8932",event.url,true)
            assertEquals(DataOrigin.NETWORK,result.stamp.origin)
            assertEquals("对阵更新失败",result.warning)
            assertEquals(14,result.value.content.matches.size)
            assertEquals(2,result.value.content.statistics.size)
            assertEquals(brackets,result.value.content.brackets)
            assertEquals(4,result.value.content.bracketStamps.size)
            assertTrue(result.value.content.bracketStamps.values.all { it.origin==DataOrigin.CACHE && it.capturedAt==oldStamp.capturedAt })
            assertEquals(2,requests)
            assertNull(browser.request.value)
        } finally { replies.cancelAndJoin(); directory.deleteRecursively() }
    }
    @Test fun refreshedProfileKeepsNewsAndItsOriginalTimeWhenNewsIsRejected() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val base=instrumentation.targetContext
        val directory=File(base.cacheDir,"repository-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(base) { override fun getFilesDir()=directory }
        var reject=false
        var newsCalls=0
        fun fixture(name:String)=instrumentation.context.assets.open("mobile/$name.json").bufferedReader().use { it.readText() }
        val repository=MatchRepository(context,BrowserSession(),mobileHttp=MobileFetcher { request ->
            when(request.route) {
                MobileRoute.PLAYER -> MobileResponse(request.url,200,mapOf("Content-Type" to "application/json"),fixture("player"))
                MobileRoute.PLAYER_NEWS -> { newsCalls++; if (reject) MobileResponse(request.url,403,emptyMap(),"blocked") else MobileResponse(request.url,200,mapOf("Content-Type" to "application/json"),fixture("player-news")) }
                else -> error("Unexpected request")
            }
        }) { RelayConfig(false,"") }
        try {
            val first=repository.player("19230","https://www.hltv.org/player/19230/-",true)
            reject=true
            val second=repository.player("19230","https://www.hltv.org/player/19230/-",true)
            assertEquals(DataOrigin.NETWORK,second.stamp.origin)
            assertEquals("新闻更新失败",second.warning)
            assertEquals(first.value.content.news,second.value.content.news)
            assertEquals(first.value.content.newsStamp!!.capturedAt,second.value.content.newsStamp!!.capturedAt)
            assertEquals(DataOrigin.CACHE,second.value.content.newsStamp!!.origin)
            assertEquals(2,newsCalls)
            assertEquals(43,repository.restoredPlayer("19230")!!.value.content.honors.size)
        } finally { directory.deleteRecursively() }
    }
}
