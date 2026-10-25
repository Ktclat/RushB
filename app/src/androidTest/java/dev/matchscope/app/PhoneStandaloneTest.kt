package dev.matchscope.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.ScopeViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Opt-in, real internet. Never injects HTML, cookies or a relay response. */
class PhoneStandaloneTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    @Test fun freshPhoneSessionReadsFeedMatchAndPlayer() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("standalone") == "true")
        val context = app.activity.applicationContext
        val directory = File(context.filesDir, "source-cache")
        directory.deleteRecursively()
        var browser: BrowserSession? = null
        app.runOnUiThread {
            val vm = ViewModelProvider(app.activity)[ScopeViewModel::class.java]
            vm.configureRelay(false, "")
            browser = vm.browser
        }
        val repository = MatchRepository(context, browser!!) { RelayConfig(false, "") }
        val start = Instant.now()
        val verification = async {
          withTimeout(480_000) {
            val feed = repository.matches(LocalDate.now(ZoneOffset.UTC), SeedSnapshot.matches)
            assertNull(feed.warning)
            assertEquals(DataOrigin.NETWORK, feed.stamp.origin)
            assertFalse(feed.stamp.capturedAt.isBefore(start))
            assertTrue(feed.items.size > SeedSnapshot.matches.items.size)
            val detail = repository.match("2399415", "https://www.hltv.org/matches/2399415/falcons-vs-betboom-thunderpick-world-championship-2026")
            assertEquals(DataOrigin.NETWORK, detail.stamp!!.origin)
            assertTrue(detail.detailed)
            assertEquals(12, detail.statGroups.size)
            assertTrue(detail.statGroups.all { it.rows.size == 10 })
            val ref = detail.players.values.flatten().firstOrNull() ?: detail.stats.firstNotNullOf { it.player }
            val profile = repository.player(ref.id, ref.url)
            assertEquals(DataOrigin.NETWORK, profile.stamp.origin)
            assertEquals(ref.id, profile.value.player.id)
            assertTrue(profile.value.content.complete)
            assertFalse(profile.value.content.matches.isEmpty())
            assertFalse(profile.value.content.events.isEmpty())
            assertFalse(profile.value.content.news.isEmpty())
            assertEquals(7, profile.value.content.roles.size)
            val team = repository.team("11283", "https://www.hltv.org/team/11283/falcons")
            assertEquals(DataOrigin.NETWORK, team.stamp.origin)
            assertTrue(team.value.content.complete)
            assertFalse(team.value.content.events.isEmpty())
            assertFalse(team.value.content.news.isEmpty())
            assertFalse(team.value.content.roster.isEmpty())
            assertFalse(team.value.content.maps.isEmpty())

            val restored = MatchRepository(context, browser!!) { error("Offline restore must not contact any transport") }
            assertEquals(DataOrigin.CACHE, restored.restoredMatches()!!.stamp.origin)
            assertEquals(detail.id, restored.restoredMatch(detail.id)!!.id)
            assertEquals(profile.stamp.capturedAt, restored.restoredPlayer(ref.id)!!.stamp.capturedAt)
            assertEquals(profile.value.content, restored.restoredPlayer(ref.id)!!.value.content)
            assertEquals(team.value.content, restored.restoredTeam("11283")!!.value.content)
            assertEquals(detail.statGroups, restored.restoredMatch(detail.id)!!.statGroups)

            val refreshed = repository.player(ref.id, ref.url, forceRefresh = true)
            assertEquals(DataOrigin.NETWORK, refreshed.stamp.origin)
            assertTrue(refreshed.stamp.capturedAt.isAfter(profile.stamp.capturedAt))

            File(context.filesDir, "standalone-proof.json").writeText(JSONObject()
                .put("startedAt", start.toString()).put("finishedAt", Instant.now().toString())
                .put("relayEnabled", false).put("engine", "GeckoView 158")
                .put("feedCount", feed.items.size).put("feedCapturedAt", feed.stamp.capturedAt.toString())
                .put("match", JSONObject().put("id", detail.id).put("url", detail.url).put("capturedAt", detail.stamp.capturedAt.toString()))
                .put("player", JSONObject().put("id", ref.id).put("name", profile.value.player.name).put("url", ref.url)
                    .put("capturedAt", profile.stamp.capturedAt.toString()).put("manualRefreshAt", refreshed.stamp.capturedAt.toString())
                    .put("matches", profile.value.content.matches.size).put("events", profile.value.content.events.size).put("news", profile.value.content.news.size).put("roles", profile.value.content.roles.size))
                .put("statGroups", detail.statGroups.size).put("team", JSONObject().put("id", "11283").put("capturedAt", team.stamp.capturedAt.toString())
                    .put("matches", team.value.content.matches.size).put("events", team.value.content.events.size).put("news", team.value.content.news.size).put("maps", team.value.content.maps.size))
                .toString(2))
            if (InstrumentationRegistry.getArguments().getString("captureSources") == "true") {
                listOf("player-events" to "https://www.hltv.org/stats/players/events/19230/m0nesy",
                    "team-events" to "https://www.hltv.org/stats/teams/events/11283/falcons",
                    "stats-map" to "https://www.hltv.org/stats/matches/mapstatsid/242012/betboom-vs-falcons").forEach { (name, url) ->
                    delay(1800)
                    File(context.filesDir, "$name.html").writeText(browser!!.load(url))
                }
            }
          }
        }
        // Network callbacks use real time; Compose's test clock must still render the dialog.
        while (!verification.isCompleted) { app.mainClock.advanceTimeBy(50); delay(50) }
        verification.await()
    }
}
