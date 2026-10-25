package dev.matchscope.app.data

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class MatchInsightRecordsTest {
    private val folder = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "match-insight-record-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    private val capturedAt = Instant.parse("2026-10-18T10:12:34.567890Z")
    private val alpha = Team("1", "Alpha", "Denmark", listOf("Orbit", "Comet"), logoUrl = "https://img-cdn.hltv.org/teamlogo/test-alpha.svg")
    private val beta = Team("2", "Beta", "Sweden", listOf("Nova", "Pulse"), logoUrl = "https://img-cdn.hltv.org/teamlogo/test-beta.png")
    private val event = Tournament(
        "9001", "Test Open", LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-18"),
        "Copenhagen", "\$100,000", 16, logoUrl = "https://img-cdn.hltv.org/eventlogo/test-open.png",
    )

    @After fun cleanup() { folder.deleteRecursively() }

    @Test fun analysisSectionsSurviveRestartWithoutMixingForecastResultsOrSeriesAndMaps() {
        val forecast = completeInsights()
        val result = forecast.copy(
            vrs = MatchVrs(MatchVrsMode.RESULT, listOf(
                MatchVrsTeam(alpha.id, 1880, 4, result = MatchVrsImpact(-19, 5, -1)),
                MatchVrsTeam(beta.id, 1621, 10, result = MatchVrsImpact(106, 8, 2)),
            )),
            complete = false,
        )
        val stamp = SourceStamp("https://www.hltv.org/matches/7001/test", capturedAt, DataOrigin.NETWORK, "2026-10-18")
        val store = RecordStore(folder)
        listOf("forecast" to forecast, "result" to result).forEach { (name, insights) ->
            store.write(name, JSONObject().put("stamp", Records.stamp(stamp)).put("insights", MatchInsightRecords.insights(insights)))
        }

        val restarted = RecordStore(folder)
        listOf("forecast" to forecast, "result" to result).forEach { (name, expected) ->
            val record = restarted.read(name)!!
            val restored = MatchInsightRecords.insights(record.optJSONObject("insights"))
            assertEquals(expected, restored)
            assertEquals(stamp.copy(origin = DataOrigin.CACHE), Records.stamp(record.getJSONObject("stamp"), cached = true))
            assertEquals(MatchResultUnit.MAP, restored.headToHead!!.results.first().unit)
            assertEquals(MatchResultUnit.SERIES, restored.recentForm.first().results.first().unit)
            assertEquals(listOf(MatchHistoryScope.CORE, MatchHistoryScope.TEAM), restored.recentForm.map { it.scope })
            assertEquals(MatchMapMetric.entries.toSet(), restored.mapPools.map { it.metric }.toSet())
            assertNull(restored.mapPools.first().maps.last().left.percentage)
            assertEquals(0.0, restored.mapPools.first().maps.last().right.percentage!!, 0.0)
            assertEquals(0, restored.mapPools.first().maps.last().right.sampleSize)
        }
        val restoredForecast = MatchInsightRecords.insights(restarted.read("forecast")!!.optJSONObject("insights"))
        assertEquals(0, restoredForecast.vrs!!.teams.first().ifWin!!.rankChange)
        assertNull(restoredForecast.vrs!!.teams.first().result)
        val restoredResult = MatchInsightRecords.insights(restarted.read("result")!!.optJSONObject("insights"))
        assertNull(restoredResult.vrs!!.teams.first().ifWin)
        assertEquals(-19, restoredResult.vrs!!.teams.first().result!!.pointsChange)
        assertFalse(restoredResult.complete)
    }

    @Test fun sparseDuelMatricesKeepAxisOrderCountsAndCaptureTimeAcrossRepeatedRestarts() {
        val orbit = PlayerRef("101", "Orbit", photoUrl = "https://img-cdn.hltv.org/playerbodyshot/test-orbit.png")
        val comet = PlayerRef("102", "Comet", photoUrl = "https://img-cdn.hltv.org/playerprofile/test-comet.png")
        val nova = PlayerRef("201", "Nova", photoUrl = "https://img-cdn.hltv.org/playerbodyshot/test-nova.webp")
        val pulse = PlayerRef("202", "Pulse")
        val sourceUrl = "https://www.hltv.org/stats/matches/performance/mapstatsid/3001/test"
        val data = MatchDuels(sourceUrl, DuelMetric.entries.mapIndexed { index, metric ->
            DuelMatrix(
                metric = metric,
                rowPlayers = listOf(pulse, nova),
                columnPlayers = listOf(comet, orbit),
                cells = listOf(
                    DuelCell(pulse.id, orbit.id, 7 + index, 2 + index),
                    DuelCell(nova.id, comet.id, 0, 0),
                ),
            )
        })
        val stamp = SourceStamp(sourceUrl, capturedAt, DataOrigin.NETWORK, "2026-10-18")
        val original = Sourced(data, stamp, "连接失败")
        RecordStore(folder).write("duels-3001", MatchInsightRecords.duels(original))

        val restored = MatchInsightRecords.duels(RecordStore(folder).read("duels-3001")!!)
        assertEquals(original.copy(stamp = stamp.copy(origin = DataOrigin.CACHE)), restored)
        restored.value.matrices.forEachIndexed { index, matrix ->
            assertEquals(listOf("202", "201"), matrix.rowPlayers.map { it.id })
            assertEquals(listOf("102", "101"), matrix.columnPlayers.map { it.id })
            assertEquals(DuelCell("202", "101", 7 + index, 2 + index), matrix.cells.first())
            assertEquals(DuelCell("201", "102", 0, 0), matrix.cells.last())
            assertEquals(2, matrix.cells.size)
            assertFalse(matrix.cells.any { it.rowPlayerId == "202" && it.columnPlayerId == "102" })
        }

        RecordStore(folder).write("duels-3001", MatchInsightRecords.duels(restored))
        val restoredAgain = MatchInsightRecords.duels(RecordStore(folder).read("duels-3001")!!)
        assertEquals(restored, restoredAgain)
        assertEquals(capturedAt, restoredAgain.stamp.capturedAt)

        val snapshot = original.copy(stamp = stamp.copy(origin = DataOrigin.SNAPSHOT), warning = null)
        RecordStore(folder).write("duels-snapshot", MatchInsightRecords.duels(snapshot))
        assertEquals(snapshot, MatchInsightRecords.duels(RecordStore(folder).read("duels-snapshot")!!))
    }

    @Test fun absentOrPartialAnalysisRemainsIncompleteAndMissingNumbersDoNotBecomeZero() {
        // Handwritten records exercise compatibility independently from the current encoder.
        File(folder, "old-match.json").writeText("""{"schema":1,"id":"7001","detailed":true}""")
        File(folder, "partial-analysis.json").writeText(
            """{"schema":1,"insights":{
                "vrs":{"mode":"FORECAST","teams":[{"teamId":"1","points":0,"ifWin":{"pointsChange":0,"rank":null}}]},
                "mapPools":[{"metric":"WIN","maps":[{"mapKey":"anubis","name":"Anubis","left":{},"right":{"percentage":0,"sampleSize":0}}]}],
                "comparisons":[{"label":"World rank","right":"#10"}],
                "headToHead":{"leftTeamId":"1","rightTeamId":"2","leftMapWins":0},
                "recentForm":[{"team":{"id":"1","name":"Alpha","url":"https://www.hltv.org/team/1/alpha"},"scope":"CORE"}]
            }}""",
        )
        File(folder, "old-duels.json").writeText(
            """{"schema":1,"stamp":{"url":"https://www.hltv.org/stats/matches/performance/mapstatsid/3001/test","at":"2026-10-17T01:02:03Z","origin":"NETWORK"},
                "sourceUrl":"https://www.hltv.org/stats/matches/performance/mapstatsid/3001/test",
                "matrices":[{"metric":"AWP_KILLS","rowPlayers":[{"id":"201","name":"Nova","url":"https://www.hltv.org/player/201/nova"}],
                "columnPlayers":[{"id":"101","name":"Orbit","url":"https://www.hltv.org/player/101/orbit"}],
                "cells":[{"rowPlayerId":"201","columnPlayerId":"101","rowKills":0,"columnKills":3}]}]}""",
        )

        val restarted = RecordStore(folder)
        assertEquals(MatchInsights(complete = false), MatchInsightRecords.insights(restarted.read("old-match")!!.optJSONObject("insights")))
        assertEquals(MatchInsights(complete = false), MatchInsightRecords.insights(JSONObject()))
        val partial = MatchInsightRecords.insights(restarted.read("partial-analysis")!!.optJSONObject("insights"))
        assertFalse(partial.complete)
        with(partial.vrs!!.teams.single()) {
            assertEquals(0, points)
            assertNull(rank)
            assertEquals(0, ifWin!!.pointsChange)
            assertNull(ifWin!!.rank)
            assertNull(ifWin!!.rankChange)
            assertNull(ifLoss)
            assertNull(result)
        }
        with(partial.mapPools.single().maps.single()) {
            assertEquals(MatchMapRate(), left)
            assertEquals(MatchMapRate(0.0, 0), right)
        }
        assertNull(partial.comparisons.single().left)
        assertEquals(0, partial.headToHead!!.leftMapWins)
        assertNull(partial.headToHead!!.rightMapWins)
        assertNull(partial.headToHead!!.overtimes)
        assertTrue(partial.headToHead!!.results.isEmpty())
        assertNull(partial.recentForm.single().period)
        assertTrue(partial.recentForm.single().results.isEmpty())

        val oldDuels = MatchInsightRecords.duels(restarted.read("old-duels")!!)
        assertNull(oldDuels.warning)
        assertNull(oldDuels.value.matrices.single().rowPlayers.single().photoUrl)
        assertEquals(DuelCell("201", "101", 0, 3), oldDuels.value.matrices.single().cells.single())
        assertEquals(DataOrigin.CACHE, oldDuels.stamp.origin)
        assertEquals(Instant.parse("2026-10-17T01:02:03Z"), oldDuels.stamp.capturedAt)
    }

    private fun completeInsights(): MatchInsights {
        val map = MatchHistoryResult(
            "7001", "https://www.hltv.org/matches/7001/test", alpha, beta, MatchResultUnit.MAP,
            "BO3", "Anubis", 13, 16, capturedAt.minusSeconds(3600), "18/10/2026", event,
        )
        val series = MatchHistoryResult(
            "7002", "https://www.hltv.org/matches/7002/test", beta, alpha, MatchResultUnit.SERIES,
            format = "BO3", leftScore = 0, rightScore = 2, playedAt = capturedAt.minusSeconds(86400),
            dateLabel = "17/10/2026", event = event,
        )
        val unknownScore = MatchHistoryResult("7003", "https://www.hltv.org/matches/7003/test", alpha, beta, MatchResultUnit.SERIES)
        return MatchInsights(
            vrs = MatchVrs(MatchVrsMode.FORECAST, listOf(
                MatchVrsTeam(alpha.id, 1880, 4, MatchVrsImpact(12, 4, 0), MatchVrsImpact(-19, 5, -1)),
                MatchVrsTeam(beta.id, 1621, 10, MatchVrsImpact(106, 8, 2), MatchVrsImpact(-7, 11)),
            )),
            mapPools = MatchMapMetric.entries.mapIndexed { index, metric ->
                MatchMapPool(
                    metric = metric, description = "Past 3 months · ${index + 3} maps minimum",
                    maps = listOf(
                        MatchMapComparison("anubis", "Anubis",
                            MatchMapRate(62.5 + index, 8 + index, "https://www.hltv.org/stats/teams/map/48/1/alpha"),
                            MatchMapRate(37.5 + index, 6 + index, "https://www.hltv.org/stats/teams/map/48/2/beta")),
                        MatchMapComparison("dust2", "Dust2", MatchMapRate(), MatchMapRate(0.0, 0)),
                    ),
                    leftStatisticsUrl = "https://www.hltv.org/stats/teams/maps/1/alpha",
                    rightStatisticsUrl = "https://www.hltv.org/stats/teams/maps/2/beta",
                )
            },
            comparisons = listOf(MatchComparison("World rank", "#4", null), MatchComparison("Average age", "24.3", "22.7")),
            headToHead = MatchHeadToHead(alpha.id, beta.id, 0, 2, 1, listOf(map), "https://www.hltv.org/results?team=1&team=2"),
            recentForm = listOf(
                MatchRecentForm(alpha, MatchHistoryScope.CORE, "Past 3 months", listOf(series, unknownScore), "https://www.hltv.org/stats/lineup/matches?lineup=101&lineup=102"),
                MatchRecentForm(beta, MatchHistoryScope.TEAM, results = listOf(series.copy(leftScore = 2, rightScore = 1))),
            ),
        )
    }
}
