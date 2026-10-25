package dev.matchscope.app.data

import dev.matchscope.app.ui.ScopeState
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MatchRecordsTest {
    private val base = SeedSnapshot.matchItems.first()
    private fun at(second: Long) = SourceStamp(base.url, Instant.ofEpochSecond(second), DataOrigin.NETWORK)
    @Test fun `newer abbreviated schedule keeps exact event association but never borrows from different opponents`() {
        val known = base.copy(stamp = at(100), event = Tournament("1234", "Known event"), format = "BO3")
        val row = known.copy(stamp = at(200), event = Tournament("unknown:event", "—"), format = "赛制待确认")
        assertEquals("1234", preferredMatchRecords(listOf(known, row)).single().event.id)
        assertEquals("BO3", preferredMatchRecords(listOf(known, row)).single().format)
        val wrongTeams = row.copy(right = Team("999999", "Other"))
        assertEquals("unknown:event", preferredMatchRecords(listOf(known, wrongTeams)).single().event.id)
        assertEquals("赛制待确认", preferredMatchRecords(listOf(known, wrongTeams)).single().format)
    }
    @Test fun `unbookmarking a restored match keeps it searchable and can bookmark the complete record again`() {
        val record = base.copy(status = MatchStatus.UPCOMING, stamp = at(100), detailed = true)
        val restored = ScopeState(matches = SeedSnapshot.matches.copy(items = emptyList()),
            savedMatches = setOf(record.id), savedRecords = listOf(record))
        val removed = restored.toggleSavedMatch(record.id)
        assertFalse(record.id in removed.savedMatches)
        assertTrue(removed.savedRecords.isEmpty())
        assertEquals(record, removed.allMatches.single())
        val savedAgain = removed.toggleSavedMatch(record.id)
        assertEquals(setOf(record.id), savedAgain.savedMatches)
        assertEquals(listOf(record), savedAgain.savedRecords)
    }
    @Test fun `older live detail cannot override a newly finished result`() {
        val detail = base.copy(status = MatchStatus.LIVE, stamp = at(100), detailed = true, leftScore = 1, rightScore = 1)
        val result = base.copy(status = MatchStatus.FINISHED, stamp = at(200), leftScore = 1, rightScore = 2)
        assertEquals(result, preferredMatchRecords(listOf(detail, result)).single())
    }
    @Test fun `completed statistics retain capture time until a later result corrects score`() {
        val detail = base.copy(status = MatchStatus.FINISHED, stamp = at(100), detailed = true, leftScore = 1, rightScore = 2)
        val result = detail.copy(stamp = at(200), detailed = false, stats = emptyList(), maps = emptyList())
        assertEquals(detail, preferredMatchRecords(listOf(result, detail)).single())
        assertEquals(at(100), preferredMatchRecords(listOf(detail)).single().stamp)
        val correction = result.copy(leftScore = 2, rightScore = 1)
        assertEquals(correction, preferredMatchRecords(listOf(detail, correction)).single())
    }
}
