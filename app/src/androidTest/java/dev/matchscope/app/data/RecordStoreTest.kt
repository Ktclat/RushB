package dev.matchscope.app.data

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class RecordStoreTest {
    private val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "record-test-${System.nanoTime()}").apply { mkdirs() }
    @After fun cleanup() { folder.deleteRecursively() }

    @Test fun completeBookmarkSurvivesNewStoreAndChangedFeed() {
        val source = SourceStamp("https://www.hltv.org/matches/2399415/-", Instant.parse("2026-10-18T04:03:04Z"), DataOrigin.NETWORK)
        val match = SeedSnapshot.matchItems.first().copy(
            stamp = source, detailed = true, sourceDate = null, startsAt = Instant.parse("2026-10-17T17:00:00Z"),
            resultAt = Instant.parse("2026-10-17T20:10:56Z"),
            players = mapOf("11283" to listOf(PlayerRef("3741", "NiKo"))),
            stats = listOf(PlayerStat("NiKo", "11283", "52-49", "76.6", "64.8%", "1.02", "3.0", PlayerRef("3741", "NiKo")))
        )
        val store = RecordStore(folder)
        store.write("saved", JSONObject().put("items", JSONArray(listOf(Records.match(match)))))
        store.write("match-feed", Records.feed(Feed(emptyList(), source)))
        val restarted = RecordStore(folder)
        val restored = Records.match(restarted.read("saved")!!.getJSONArray("items").getJSONObject(0), true)
        assertEquals(match.copy(stamp = source.copy(origin = DataOrigin.CACHE)), restored)
        val restoredFeed = Records.feed(restarted.read("match-feed")!!)
        assertTrue(restoredFeed.items.isEmpty())
        assertNull(restoredFeed.warning)
        assertEquals(source.capturedAt, restored.stamp!!.capturedAt)
    }

    @Test fun profileKeepsLabeledStatisticsAndOriginalCaptureTime() {
        val source = SourceStamp("https://www.hltv.org/player/3741/niko", Instant.parse("2026-10-18T04:03:09Z"), DataOrigin.NETWORK)
        val profile = PlayerProfile(PlayerRef("3741", "NiKo"), "Nikola Kovač", "Bosnia and Herzegovina", 29,
            Team("11283", "Falcons"), listOf("Rating 3.0" to "1.09", "Firepower" to "66/100"), "Past 3 months • 43 maps")
        RecordStore(folder).write("player-3741", Records.profile(Sourced(profile, source)))
        val restored = Records.profile(RecordStore(folder).read("player-3741")!!)
        assertEquals(profile, restored.value)
        assertEquals(source.copy(origin = DataOrigin.CACHE), restored.stamp)
        assertNull(restored.warning)
        val warning = "连接失败"
        RecordStore(folder).write("player-3741", Records.profile(Sourced(profile, source, warning)))
        val failed = Records.profile(RecordStore(folder).read("player-3741")!!)
        assertEquals(warning, failed.warning)
        assertEquals(source.capturedAt, failed.stamp.capturedAt)
    }

    @Test fun corruptAndUnknownSchemaRecordsFailWithoutReplacingOtherRecords() {
        val store = RecordStore(folder)
        store.write("valid", JSONObject().put("value", 1))
        File(folder, "broken.json").writeText("{incomplete")
        File(folder, "future.json").writeText("{\"schema\":999}")
        assertNull(store.read("broken"))
        assertNull(store.read("future"))
        assertEquals(1, store.read("valid")!!.getInt("value"))
    }

    @Test fun identityArtworkSurvivesRestartAndOldRecordsStillLoad() {
        val match = SeedSnapshot.matchItems.first()
        val store = RecordStore(folder)
        store.write("match", Records.match(match))
        assertEquals(match, Records.match(RecordStore(folder).read("match")!!))
        val old = Records.match(match)
        old.getJSONObject("left").remove("logo")
        old.getJSONObject("event").remove("logo")
        old.getJSONObject("players").getJSONArray(match.left.id).getJSONObject(0).remove("photo")
        val decoded = Records.match(old)
        assertNull(decoded.left.logoUrl)
        assertNull(decoded.event.logoUrl)
        assertNull(decoded.players[match.left.id]!!.first().photoUrl)
        assertEquals(match.score, decoded.score)
        val profile = TeamProfile(SeedSnapshot.falcons, match.players["11283"]!!, 5, "zonic")
        store.write("team-11283", Records.teamProfile(Sourced(profile, match.stamp!!)))
        val restoredTeam = Records.teamProfile(RecordStore(folder).read("team-11283")!!)
        assertEquals(profile, restoredTeam.value)
        assertNull(restoredTeam.warning)
        store.write("event-8932", Records.eventProfile(Sourced(match.event, match.stamp)))
        val restoredEvent = Records.eventProfile(RecordStore(folder).read("event-8932")!!)
        assertEquals(match.event, restoredEvent.value)
        assertNull(restoredEvent.warning)
        val failedStamp = match.stamp.copy(origin = DataOrigin.CACHE)
        store.write("team-11283", Records.teamProfile(Sourced(profile, failedStamp, "连接失败")))
        store.write("event-8932", Records.eventProfile(Sourced(match.event, failedStamp, "连接失败")))
        assertEquals("连接失败", Records.teamProfile(RecordStore(folder).read("team-11283")!!).warning)
        assertEquals("连接失败", Records.eventProfile(RecordStore(folder).read("event-8932")!!).warning)
    }
}
