package dev.matchscope.app

import androidx.compose.foundation.lazy.LazyColumn
import dev.matchscope.app.ui.MatchScopeTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real captured response fixtures; this is UI regression, not a new network claim. */
class MobileDetailsUiTest {
    @get:Rule val app=createComposeRule()
    private fun json(name:String)=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("mobile/$name.json").bufferedReader().use { it.readText() })
    @Test fun eventStatisticsSwitchScopeAndOpenTheActualPlayer() {
        val event=MobileProfileParser.event(json("event"),"8932")
        var opened:String?=null
        app.setContent { MatchScopeTheme { EventStatisticsPanel(event.content.statistics) { opened=it } } }
        app.onNodeWithText("1.52").assertIsDisplayed()
        app.onNodeWithText("淘汰赛",substring=false).performClick()
        app.onNodeWithText("1.67").assertIsDisplayed()
        app.onNodeWithText("1.52").assertDoesNotExist()
        app.saveEvidence("v062-event-statistics")
        app.onNodeWithText("KSCERATO").performClick()
        app.runOnIdle { assertEquals("player|15631",opened) }
    }
    @Test fun honorsFilterSeparatesMvpFromPlacementAndKeepsNewsLinks() {
        val profile=MobileProfileParser.player(json("player"),"19230")
        var opened:String?=null
        app.setContent { MatchScopeTheme {
            var selected by remember { mutableStateOf("全部") }
            LazyColumn { profileHonors(profile.content.honors,selected,{ selected=it },50,{}, { opened=it }) }
        } }
        app.onNode(hasText("MVP",substring=false) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        app.onNodeWithText("IEM Cologne Major 2026").assertIsDisplayed().performClick()
        app.runOnIdle { assertEquals("event|8301",opened) }
        app.saveEvidence("v062-player-mvp")
        app.onNodeWithText("Top 20",substring=false).performScrollTo().performClick()
        app.onNodeWithText("2022 Top 20").assertIsDisplayed()
        app.onAllNodesWithText("查看报道 ↗").onFirst().assertIsDisplayed()
        app.saveEvidence("v062-player-top20")
    }
}
