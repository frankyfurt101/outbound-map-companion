package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewestSelectorTest {
    @Test fun picksNewest() {
        val s = NewestSelector.select(listOf(Candidate("a.save", 100, 1), Candidate("b.autosave", 300, 1), Candidate("c.save", 200, 1)))
        assertEquals("b.autosave", (s as Selection.Newest).candidate.name)
    }

    @Test fun ignoresOtherFiles() {
        val s = NewestSelector.select(listOf(Candidate("a.save", 100, 1), Candidate("notes.txt", 999, 1), Candidate("x.save.bak", 999, 1)))
        assertEquals("a.save", (s as Selection.Newest).candidate.name)
    }

    @Test fun noneWhenNoSaves() {
        assertTrue(NewestSelector.select(listOf(Candidate("a.txt", 1, 1))) is Selection.None)
        assertTrue(NewestSelector.select(emptyList()) is Selection.None)
    }

    @Test fun fallbackWhenTimestampMissing() {
        val s = NewestSelector.select(listOf(Candidate("a.save", 100, 1), Candidate("b.save", null, 1)))
        assertEquals(2, (s as Selection.NeedsTimePlayedFallback).candidates.size)
        assertTrue(NewestSelector.select(listOf(Candidate("a.save", 0, 1))) is Selection.NeedsTimePlayedFallback)
    }

    @Test fun nonSaveWithNullTimestampDoesNotForceFallback() {
        val s = NewestSelector.select(listOf(Candidate("a.save", 100, 1), Candidate("junk.tmp", null, 1)))
        assertTrue(s is Selection.Newest)
    }
}
