package dev.matchscope.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Layout fixtures exercise density and interaction; they are not a live data acceptance test. */
class ToolUiTest {
    @get:Rule val app = createComposeRule()
    private val day = LocalDate.parse("2026-10-19")
    private val event = Tournament("fixture-cup", "CS2 TOOL UI FIXTURE", tier = EventTier.TIER_ONE)
    private val matches = (1..6).map { index ->
        Match("fixture-$index", Team("a$index", "Alpha $index"), Team("b$index", "Bravo $index"), event,
            if (index == 1) MatchStatus.LIVE else MatchStatus.UPCOMING, "BO3",
            startsAt = Instant.parse("2026-10-19T09:00:00Z").plusSeconds(index * 3600L),
            leftScore = if (index == 1) 0 else null, rightScore = if (index == 1) 1 else null)
    }
    private fun state() = ScopeState(matches = Feed(matches, SourceStamp("https://www.hltv.org/mobile/MatchesV4", Instant.now(), DataOrigin.NETWORK)), timezone = "UTC")
    @Before fun images() { MediaLoader.install() }

    @Test fun sixMatchesFitPhoneViewportAndBookmarkDoesNotNavigate() {
        var opened = ""
        var saved = ""
        app.setContent { MatchScopeTheme {
            Column(Modifier.width(360.dp).height(760.dp).background(Background)) {
                Box(Modifier.height(52.dp).padding(12.dp)) { Text("赛事", Modifier.testTag("screen-title")) }
                Box(Modifier.weight(1f).testTag("viewport")) {
                    MatchesScreen(state(), day, {}, {}, { saved = it }, { opened = it })
                }
                ToolNavigation(RootTab.MATCHES) {}
            }
        } }
        val viewport = app.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        matches.forEach { match ->
            val bounds = app.onNodeWithTag("match-row-${match.id}").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("Entire row must fit: ${match.id}", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        app.onNode(hasText("10:00") and hasAnyAncestor(hasTestTag("match-row-fixture-1")), useUnmergedTree = true).assertIsDisplayed()
        val title = app.onNodeWithTag("screen-title").captureToImage().toPixelMap()
        assertTrue("Unwrapped title must remain legible on the dark background",
            (0 until title.width).any { x -> (0 until title.height).any { y -> title[x, y].luminance() > .5f } })
        app.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(0)
        app.onAllNodesWithContentDescription("收藏比赛").onFirst().performClick()
        app.runOnIdle { assertEquals("fixture-1", saved); assertEquals("", opened) }
        app.onNodeWithTag("match-row-fixture-1").performClick()
        app.runOnIdle { assertEquals("match|fixture-1", opened) }
        app.saveEvidence("v080-dense-home-fixture")
    }

    @Test fun liveZeroIsVisibleUpcomingScoreIsUnknownAndEventCanCollapse() {
        var opened = ""
        app.setContent { MatchScopeTheme { Box(Modifier.width(320.dp).background(Background)) {
            MatchesScreen(state(), day, {}, {}, {}, { opened = it })
        } } }
        app.onNodeWithTag("match-row-fixture-1").assert(hasText("0") and hasText("1") and hasText("LIVE"))
        app.onNodeWithTag("match-row-fixture-2").assert(hasText("—") and !hasText("0"))
        app.onNodeWithContentDescription("收起 ${event.name}").performClick()
        app.onNodeWithTag("match-row-fixture-1").assertDoesNotExist()
        app.onNodeWithContentDescription("展开 ${event.name}").performClick()
        app.onNodeWithTag("match-row-fixture-1").assertIsDisplayed()
        app.onNodeWithText(event.name).performClick()
        app.runOnIdle { assertEquals("event|${event.id}", opened) }
    }

    @Test fun missingLiveScoreShowsCaptureTimeButRealZeroNeedsNoMissingDataLabel() {
        var match by mutableStateOf(matches.first().copy(leftScore = null, rightScore = null))
        val stamp = SourceStamp("https://www.hltv.org/mobile/MatchesV4", Instant.parse("2026-10-20T10:32:00Z"), DataOrigin.NETWORK)
        val state = state().copy(matches = Feed(listOf(match), stamp))
        app.setContent { MatchScopeTheme { Box(Modifier.width(320.dp)) { MatchRow(match, state, {}, {}) } } }
        app.onNodeWithText("比分待更新 · 上次更新 10.20 10:32").assertIsDisplayed()
        app.onNodeWithText("待更新").assertIsDisplayed()
        app.onNodeWithText("LIVE").assertDoesNotExist()
        app.onAllNodesWithText("0", useUnmergedTree = true).assertCountEquals(0)
        app.runOnIdle { match = match.copy(leftScore = 0, rightScore = 0) }
        app.onNodeWithText("比分待更新 · 上次更新 10.20 10:32").assertDoesNotExist()
        app.onNodeWithText("上次更新 10.20 10:32").assertIsDisplayed()
        app.onAllNodesWithText("0", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test fun pendingStatusHasItsOwnFilterInsteadOfCountingAsConfirmedLive() {
        val now = Instant.now()
        val stamp = SourceStamp("https://www.hltv.org/mobile/MatchesV4", now, DataOrigin.NETWORK)
        val stale = matches[0].copy(stamp = stamp.copy(capturedAt = now.minusSeconds(120)))
        val current = matches[1].copy(status = MatchStatus.LIVE, stamp = stamp)
        val state = state().copy(matches = Feed(listOf(stale, current), stamp))
        app.setContent { MatchScopeTheme { Box(Modifier.width(320.dp)) { MatchesScreen(state, day, {}, {}, {}, {}) } } }
        app.onNodeWithText("进行中").performClick()
        app.onNodeWithTag("match-row-${current.id}").assertIsDisplayed()
        app.onNodeWithTag("match-row-${stale.id}").assertDoesNotExist()
        app.onNodeWithText("待更新").performClick()
        app.onNodeWithTag("match-row-${stale.id}").assertIsDisplayed()
        app.onNodeWithTag("match-row-${current.id}").assertDoesNotExist()
        app.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(0)
    }

    @Test fun noConfirmedLiveStatusOffersRefreshAndAccessToPendingMatches() {
        val stamp = SourceStamp("https://www.hltv.org/mobile/MatchesV4", Instant.now().minusSeconds(120), DataOrigin.CACHE)
        val state = state().copy(matches = Feed(listOf(matches.first()), stamp))
        var refreshes = 0
        app.setContent { MatchScopeTheme { Box(Modifier.width(320.dp)) { MatchesScreen(state, day, {}, { refreshes++ }, {}, {}) } } }
        app.onNodeWithText("进行中").performClick()
        app.onNodeWithText("比赛状态待更新").assertIsDisplayed()
        app.onNodeWithText("没有符合条件的比赛").assertDoesNotExist()
        app.onNodeWithText("刷新比赛").performClick()
        app.runOnIdle { assertEquals(1, refreshes) }
        app.onNodeWithText("查看待更新").performClick()
        app.onNodeWithTag("match-row-${matches.first().id}").assertIsDisplayed()
        app.onNodeWithText("比赛状态待更新").assertDoesNotExist()
    }
}
