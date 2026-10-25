package dev.matchscope.app.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class ProfileRecordsTest {
    private val folder = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "profile-record-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    private val capturedAt = Instant.parse("2026-10-18T08:12:34.567Z")
    private val player = PlayerRef("101", "Orbit", photoUrl = "https://img-cdn.hltv.org/playerbodyshot/test-orbit.png")
    private val teammate = PlayerRef("102", "Comet", photoUrl = "https://img-cdn.hltv.org/playerprofile/test-comet.png")
    private val alpha = Team("1", "Alpha", "Denmark", listOf("Orbit", "Comet"), logoUrl = "https://img-cdn.hltv.org/teamlogo/test-alpha.svg")
    private val beta = Team("2", "Beta", "Sweden", logoUrl = "https://img-cdn.hltv.org/teamlogo/test-beta.png")
    private val event = Tournament(
        "9001", "Test Open", LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-18"),
        "Copenhagen", "\$100,000", 16, logoUrl = "https://img-cdn.hltv.org/eventlogo/test-open.png",
    )

    @After fun cleanup() { folder.deleteRecursively() }

    @Test fun completePlayerAndTeamProfilesSurviveStoreRecreation() {
        val content = completeContent()
        val playerProfile = PlayerProfile(
            player, "Test Player", "Denmark", 24, alpha,
            listOf("Rating 3.0" to "1.21", "ADR" to "84.7"), "Past 3 months · 38 maps", content,
        )
        val teamProfile = TeamProfile(alpha, listOf(player, teammate), 7, "Test Coach", content)
        val playerStamp = SourceStamp(player.url, capturedAt, DataOrigin.NETWORK, "2026-10-18")
        val teamStamp = SourceStamp(alpha.url, capturedAt.minusSeconds(9), DataOrigin.NETWORK)
        val store = RecordStore(folder)
        store.write("player-101", Records.profile(Sourced(playerProfile, playerStamp)))
        store.write("team-1", Records.teamProfile(Sourced(teamProfile, teamStamp, "连接失败")))

        val restarted = RecordStore(folder)
        val restoredPlayer = Records.profile(restarted.read("player-101")!!)
        val restoredTeam = Records.teamProfile(restarted.read("team-1")!!)
        assertEquals(playerProfile, restoredPlayer.value)
        assertEquals(teamProfile, restoredTeam.value)
        assertEquals(playerStamp.copy(origin = DataOrigin.CACHE), restoredPlayer.stamp)
        assertEquals(teamStamp.copy(origin = DataOrigin.CACHE), restoredTeam.stamp)
        assertNull(restoredPlayer.warning)
        assertEquals("连接失败", restoredTeam.warning)
        assertTrue(restoredPlayer.value.content.complete)
        assertTrue(restoredTeam.value.content.complete)
        assertEquals(listOf(false, true), restoredPlayer.value.content.events.map { it.upcoming })
        assertNull(restoredTeam.value.content.career.last().endDate)
        assertEquals(7, restoredPlayer.value.content.roles.size)
    }

    @Test fun seriesAndMapSideStatisticsKeepEveryValueAndOriginalCaptureTime() {
        val mapKeys = listOf("all" to null, "dust2" to "Dust2", "mirage" to "Mirage")
        val groups = mapKeys.flatMapIndexed { mapIndex, (key, name) ->
            MatchSide.entries.mapIndexed { sideIndex, side ->
                val serial = mapIndex * 3 + sideIndex
                MatchStatGroup(
                    mapKey = key,
                    mapName = name,
                    side = side,
                    rows = listOf(
                        PlayerStat(
                            "Orbit", alpha.id, "${20 + serial}-${12 + serial}", "${80 + serial}.4", "${70 + serial}.2%",
                            "1.${20 + serial}", "3.0", player, "+${2 + serial}.1%", "${18 + serial}-${11 + serial}",
                            "${76 + serial}.3", "${68 + serial}.4%",
                        ),
                        PlayerStat("Comet", beta.id, "12-20", null, null, "0.89", "2.1", teammate),
                    ),
                    statsUrl = name?.let { "https://www.hltv.org/stats/matches/mapstatsid/${300 + mapIndex}/test" },
                )
            }
        }
        val stamp = SourceStamp("https://www.hltv.org/matches/7001/test", capturedAt, DataOrigin.NETWORK)
        val match = Match(
            "7001", alpha, beta, event, MatchStatus.FINISHED, "BO3",
            startsAt = capturedAt.minusSeconds(7200), resultAt = capturedAt.minusSeconds(300), leftScore = 2, rightScore = 0,
            maps = listOf(
                MapScore("Dust2", 13, 8, "8:4;5:4", groups.first { it.mapKey == "dust2" }.statsUrl),
                MapScore("Mirage", 13, 10, "6:6;7:4", groups.first { it.mapKey == "mirage" }.statsUrl),
            ),
            veto = listOf("Alpha picked Dust2", "Beta picked Mirage"), stats = groups.first().rows,
            url = stamp.url, players = mapOf(alpha.id to listOf(player), beta.id to listOf(teammate)),
            stamp = stamp, detailed = true, statGroups = groups,
        )
        RecordStore(folder).write("match-7001", Records.match(match))

        val restored = Records.match(RecordStore(folder).read("match-7001")!!, cached = true)
        assertEquals(match.copy(stamp = stamp.copy(origin = DataOrigin.CACHE)), restored)
        assertEquals(9, restored.statGroups.size)
        assertEquals(MatchSide.entries.toSet(), restored.statGroups.map { it.side }.toSet())
        assertEquals(groups.first().rows, restored.stats)
        assertNull(restored.statGroups.first().statsUrl)
        assertNull(restored.statGroups.first().rows.last().ecoKd)

        RecordStore(folder).write("match-7001", Records.match(restored))
        val restoredAgain = Records.match(RecordStore(folder).read("match-7001")!!, cached = true)
        assertEquals(restored, restoredAgain)
        assertEquals(capturedAt, restoredAgain.stamp!!.capturedAt)
    }

    @Test fun legacyFilesWithoutProfileContentOrStatGroupsRemainReadable() {
        // Handwritten pre-expansion files catch compatibility regressions in both encoders and decoders.
        File(folder, "player-101.json").writeText(
            """{"schema":1,"stamp":{"url":"https://www.hltv.org/player/101/orbit","at":"2026-10-17T01:02:03Z","origin":"NETWORK"},
                "profile":{"player":{"id":"101","name":"Orbit","url":"https://www.hltv.org/player/101/orbit"},
                "realName":"Test Player","country":"Denmark","age":24,"period":"Past 3 months",
                "stats":[{"label":"Rating 3.0","value":"1.21"}]}}""",
        )
        File(folder, "team-1.json").writeText(
            """{"schema":1,"stamp":{"url":"https://www.hltv.org/team/1/alpha","at":"2026-10-17T01:02:04Z","origin":"SNAPSHOT"},
                "team":{"id":"1","name":"Alpha","url":"https://www.hltv.org/team/1/alpha"},
                "players":[{"id":"101","name":"Orbit","url":"https://www.hltv.org/player/101/orbit"}],"rank":7,"coach":"Test Coach"}""",
        )
        File(folder, "match-7001.json").writeText(
            """{"schema":1,"id":"7001","left":{"id":"1","name":"Alpha","url":"https://www.hltv.org/team/1/alpha"},
                "right":{"id":"2","name":"Beta","url":"https://www.hltv.org/team/2/beta"},
                "event":{"id":"9001","name":"Test Open","url":"https://www.hltv.org/events/9001/test"},
                "status":"FINISHED","format":"BO3","leftScore":2,"rightScore":0,"url":"https://www.hltv.org/matches/7001/test","detailed":true,
                "stamp":{"url":"https://www.hltv.org/matches/7001/test","at":"2026-10-17T01:02:05Z","origin":"NETWORK"},
                "maps":[{"name":"Dust2","left":13,"right":8}],
                "stats":[{"name":"Orbit","team":"1","kd":"41-28","adr":"84.7","kast":"78.2%","rating":"1.21","version":"3.0"}]}""",
        )

        val restarted = RecordStore(folder)
        val oldPlayer = Records.profile(restarted.read("player-101")!!)
        val oldTeam = Records.teamProfile(restarted.read("team-1")!!)
        val oldMatch = Records.match(restarted.read("match-7001")!!, cached = true)
        assertEquals(ProfileContent(complete = false), oldPlayer.value.content)
        assertEquals(ProfileContent(complete = false), oldTeam.value.content)
        assertEquals(listOf("Rating 3.0" to "1.21"), oldPlayer.value.statistics)
        assertEquals(DataOrigin.CACHE, oldPlayer.stamp.origin)
        assertEquals(DataOrigin.SNAPSHOT, oldTeam.stamp.origin)
        assertEquals(Instant.parse("2026-10-17T01:02:03Z"), oldPlayer.stamp.capturedAt)
        assertEquals(Instant.parse("2026-10-17T01:02:04Z"), oldTeam.stamp.capturedAt)
        assertTrue(oldMatch.statGroups.isEmpty())
        assertEquals("41-28", oldMatch.stats.single().kd)
        assertEquals("78.2%", oldMatch.stats.single().kast)
        assertEquals("3.0", oldMatch.stats.single().ratingVersion)
        with(oldMatch.stats.single()) {
            assertNull(swing)
            assertNull(ecoKd)
            assertNull(ecoAdr)
            assertNull(ecoKast)
        }
        assertNull(oldMatch.maps.single().statsUrl)
        assertEquals(DataOrigin.CACHE, oldMatch.stamp!!.origin)
        assertEquals(Instant.parse("2026-10-17T01:02:05Z"), oldMatch.stamp!!.capturedAt)
    }

    private fun completeContent(): ProfileContent {
        val nextEvent = event.copy(id = "9002", name = "Next Open", startDate = LocalDate.parse("2026-11-01"), endDate = LocalDate.parse("2026-11-04"), url = "https://www.hltv.org/events/9002/next")
        return ProfileContent(
            matches = listOf(
                ProfileMatch(alpha, beta, event, MatchStatus.FINISHED, capturedAt.minusSeconds(3600), 2, 1,
                    "https://www.hltv.org/matches/7001/test", "https://www.hltv.org/stats/matches/7001/test"),
                ProfileMatch(beta, alpha, nextEvent, MatchStatus.UPCOMING, capturedAt.plusSeconds(86400), matchUrl = "https://www.hltv.org/matches/7002/next"),
                ProfileMatch(alpha, beta, event, MatchStatus.FINISHED, statisticsUrl = "https://www.hltv.org/stats/matches/6999/past"),
            ),
            events = listOf(ProfileEvent(event, "1st", alpha, category = "LAN", statistics = listOf("Maps" to "9", "Rating 3.0" to "1.21")), ProfileEvent(nextEvent, team = alpha, upcoming = true, category = "Online")),
            news = listOf(NewsItem("Test roster update", "https://www.hltv.org/news/9001/test", "2026-10-18")),
            career = listOf(
                CareerTeam(beta, LocalDate.parse("2024-01-01"), LocalDate.parse("2025-06-30"), "2024–2025", listOf(event)),
                CareerTeam(alpha, LocalDate.parse("2025-07-01"), null, "2025–至今"),
            ),
            roles = listOf("Firepower", "Entrying", "Opening", "Utility", "Sniping", "Clutching", "Trading").mapIndexed { index, name -> RoleStat(name, 40 + index * 7) },
            roster = listOf(RosterMember(player, "Denmark", "Active", "1 year", 78, "1.21"), RosterMember(teammate, "Sweden", "Benched", "3 months")),
            maps = listOf(MapPerformance("Dust2", "75.0%", 9, 0, 3, listOf("Rounds won" to "154", "Opening kills" to "57.1%"))),
            summary = listOf("HLTV rank" to "#7", "VRS rank" to "#9", "Win rate" to "70.5%"),
            moreMatchesUrl = "https://www.hltv.org/stats/players/matches/101/orbit",
            moreEventsUrl = "https://www.hltv.org/stats/players/events/101/orbit",
            complete = true,
        )
    }
}
