package dev.matchscope.app

import androidx.compose.foundation.lazy.LazyColumn
import dev.matchscope.app.ui.MatchScopeTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.MatchStatistics
import dev.matchscope.app.ui.profileEvents
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class ProfileUiTest {
    @get:Rule val app = createComposeRule()

    @Test fun economySelectionCannotBlankAMapThatOnlyHasBaseStatistics() {
        val player = PlayerRef("19230", "m0NESY")
        val left = Team("11283", "Falcons")
        val groups = listOf(stats("all", null, MatchSide.BOTH, player, "41-25", "91.1", "39-24", "87.1"),
            MatchStatGroup("nuke", "Nuke", MatchSide.BOTH, listOf(PlayerStat(player.name, left.id, "20-10", "90.0", "80%", "1.25", "3.0", player))))
        val match = Match("1", left, Team("2", "B"), Tournament("1", "Cup"), MatchStatus.FINISHED, statGroups = groups)
        app.setContent { MatchScopeTheme { MatchStatistics(match) {} } }
        app.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        assertStats("39-24", "87.1")
        app.onNodeWithText("Nuke", substring = false).performClick()
        assertStats("20-10", "90.0")
        app.onNodeWithText("80%", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun mapSideAndEconomySelectionShowMatchingStatsAndOpenPlayer() {
        val player = PlayerRef("19230", "m0NESY")
        val left = Team("11283", "Falcons")
        val right = Team("12394", "BetBoom")
        val groups = listOf(
            stats("all", null, MatchSide.BOTH, player, "41-25", "91.1", "39-24", "87.1"),
            stats("all", null, MatchSide.T, player, "19-13", "84.2", "18-12", "81.2"),
            stats("all", null, MatchSide.CT, player, "22-12", "98.3", "21-11", "94.3"),
            stats("anubis", "Anubis", MatchSide.BOTH, player, "24-15", "96.4", "23-14", "90.4"),
            stats("anubis", "Anubis", MatchSide.T, player, "10-8", "85.5", "9-7", "80.5"),
            stats("anubis", "Anubis", MatchSide.CT, player, "14-7", "107.6", "13-6", "100.6"),
        )
        val match = Match("2399415", left, right, Tournament("8932", "Test Event"), MatchStatus.FINISHED, statGroups = groups)
        var opened: String? = null
        app.setContent { MatchScopeTheme { MatchStatistics(match) { opened = it } } }

        assertStats("41-25", "91.1")
        app.onNodeWithText("Anubis").performClick()
        assertStats("24-15", "96.4")
        app.onNodeWithText("T", substring = false).performClick()
        assertStats("10-8", "85.5")
        app.onNodeWithText("CT", substring = false).performClick()
        assertStats("14-7", "107.6")

        app.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        assertStats("13-6", "100.6")
        app.onNodeWithText("14-7", useUnmergedTree = true).assertDoesNotExist()
        app.onNodeWithText("全部地图").performClick()
        assertStats("21-11", "94.3")
        app.onNodeWithText("Both", substring = false).performClick()
        assertStats("39-24", "87.1")
        app.onNodeWithText("m0NESY").performClick()
        app.runOnIdle { assertEquals("player|19230", opened) }
    }

    @Test fun duplicateEventHonorsShareOneCardAndFiltersKeepCorrectRoutes() {
        val past = Tournament("8932", "Finished Test Event", LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-18"))
        val future = Tournament("9991", "Upcoming Test Event", LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-15"))
        val content = ProfileContent(events = listOf(
            ProfileEvent(past, "1st", category = "参赛", statistics = listOf("Rating 3.0" to "1.12")),
            ProfileEvent(past.copy(startDate = null, endDate = null), category = "MVP"),
            ProfileEvent(future, upcoming = true, category = "邀请"),
        ))
        var opened: String? = null
        app.setContent {
            MatchScopeTheme {
                var filter by remember { mutableStateOf("全部") }
                LazyColumn {
                    profileEvents(content, { opened = it }, filter, { filter = it }, limit = 20, more = {})
                }
            }
        }

        app.onAllNodesWithText(past.name).assertCountEquals(1)
        app.onNodeWithText("MVP", substring = true).assertExists()
        app.onNodeWithText(future.name).assertIsDisplayed()
        app.onNodeWithText("进行中与未来").performClick()
        app.onNodeWithText(past.name).assertDoesNotExist()
        app.onNodeWithText(future.name).performClick()
        app.runOnIdle { assertEquals("event|9991", opened) }

        app.onNodeWithText("已结束", substring = false).performClick()
        app.onNodeWithText(future.name).assertDoesNotExist()
        app.onAllNodesWithText(past.name).assertCountEquals(1)
        app.onNodeWithText(past.name).performClick()
        app.runOnIdle { assertEquals("event|8932", opened) }
    }

    private fun assertStats(kd: String, adr: String) {
        app.onNodeWithText(kd, useUnmergedTree = true).assertIsDisplayed()
        app.onNodeWithText(adr, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun stats(mapKey: String, mapName: String?, side: MatchSide, player: PlayerRef, kd: String, adr: String, ecoKd: String, ecoAdr: String) =
        MatchStatGroup(mapKey, mapName, side, listOf(PlayerStat(
            player.name, "11283", kd, adr, "78.0%", "1.31", "3.0", player,
            swing = "+3.2%", ecoKd = ecoKd, ecoAdr = ecoAdr, ecoKast = "74.0%",
        )))
}
