package dev.matchscope.app.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class LocalLibraryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun folder() = File(context.cacheDir, "library-${UUID.randomUUID()}").apply { mkdirs() }
    private fun match() = MobileMatchParser.detail(JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("mobile/match.json").bufferedReader().use { it.readText() }), "2399415")
        .copy(stamp = SourceStamp("https://www.hltv.org/mobile/MatchScreen?matchId=2399415", Instant.parse("2026-10-20T00:00:00Z"), DataOrigin.NETWORK))

    @Test fun restartRestoresUnbookmarkedDetailsProfilesAndEventLinksWithoutChangingTheirAge() {
        val dir = folder()
        try {
            val store = RecordStore(dir)
            val detail = match()
            store.write("match-${detail.id}", Records.match(detail))
            store.write("saved", JSONObject().put("items", JSONArray()))
            val missing = ProfileMatch(detail.left, detail.right, Tournament("unknown:event", "—"), MatchStatus.UPCOMING,
                detail.startsAt, matchUrl = detail.url)
            val teammate = PlayerRef("19230", "m0NESY")
            val news = NewsItem("Cached story", "https://www.hltv.org/news/43123/title", "2026-10-19T00:00:00Z")
            store.write("team-${detail.left.id}", Records.teamProfile(Sourced(TeamProfile(detail.left, listOf(teammate),
                worldRanking = 6, content = ProfileContent(matches = listOf(missing), news = listOf(news))), detail.stamp!!)))
            File(dir, "match-42.json").writeText("incomplete")
            val library = readLocalLibrary(RecordStore(dir))
            assertEquals(1, library.matches.size)
            assertEquals(detail.event.id, library.matches.single().event.id)
            assertEquals(detail.stamp!!.capturedAt, library.matches.single().stamp!!.capturedAt)
            assertEquals(DataOrigin.CACHE, library.matches.single().stamp!!.origin)
            assertFalse(library.matches.single().detailed)
            assertTrue(library.matches.single().stats.isEmpty())
            assertTrue(library.players.any { it.id == teammate.id })
            assertEquals(listOf(news), library.news)
            assertEquals(6, library.teamRanks[detail.left.id]?.value)
            assertEquals(detail.stamp!!.capturedAt, library.teamRanks[detail.left.id]?.stamp?.capturedAt)
            assertEquals(detail.event.id, resolveProfileMatch(missing, library.matches).event.id)
        } finally { dir.deleteRecursively() }
    }

    @Test fun rememberingAndRemovingBookmarkDoesNotDeleteTheIndependentSearchRecord() = runBlocking {
        val dir = folder()
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = dir }
        try {
            val repository = MatchRepository(isolated, BrowserSession()) { RelayConfig(false, "") }
            val detail = match()
            repository.rememberMatch(detail)
            repository.saveBookmarks(listOf(detail))
            repository.saveBookmarks(emptyList())
            val restarted = MatchRepository(isolated, BrowserSession()) { RelayConfig(false, "") }
            assertTrue(restarted.restoredSaved().isEmpty())
            assertEquals(detail.id, restarted.restoredLibrary().matches.single().id)
            assertEquals(detail.event.url, restarted.restoredLibrary().matches.single().event.url)
        } finally { dir.deleteRecursively() }
    }

    @Test fun upcomingEventUsesAnExactStoredMatchWithoutRequestingAgain() = runBlocking {
        val dir = folder()
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = dir }
        try {
            val detail = match()
            var calls = 0
            val repository = MatchRepository(isolated, BrowserSession(), mobileHttp = MobileFetcher {
                calls++; throw AssertionError("An existing event association needs no network")
            }) { RelayConfig(false, "") }
            repository.rememberMatch(detail)
            val row = ProfileMatch(detail.right, detail.left, Tournament("unknown:event", "—"), MatchStatus.UPCOMING,
                detail.startsAt, matchUrl = detail.url)
            assertEquals(detail.event.id, repository.scheduleMatch(row)!!.event.id)
            assertEquals(0, calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingEventIsFetchedByMatchIdAndNeverAppliedToDifferentOpponents() = runBlocking {
        val dir = folder()
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = dir }
        try {
            val detail = match()
            var calls = 0
            val repository = MatchRepository(isolated, BrowserSession(), mobileHttp = MobileFetcher { request ->
                assertEquals(MobileRoute.MATCH, request.route)
                assertEquals(detail.id, request.entityId)
                calls++
                MobileResponse(request.url, 200, mapOf("Content-Type" to "application/json"),
                    InstrumentationRegistry.getInstrumentation().context.assets.open("mobile/match.json").bufferedReader().use { it.readText() })
            }) { RelayConfig(false, "") }
            val row = ProfileMatch(detail.left, detail.right, Tournament("unknown:event", "—"), MatchStatus.UPCOMING,
                detail.startsAt, matchUrl = detail.url)
            assertEquals(detail.event.id, repository.scheduleMatch(row)!!.event.id)
            assertEquals(1, calls)
            assertEquals(detail.event.id, repository.restoredMatch(detail.id)!!.event.id)
            assertNull(repository.scheduleMatch(row.copy(right = Team("999999", "Other"))))
        } finally { dir.deleteRecursively() }
    }
}
