package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveFallbackTest {
    private val a = Candidate("a.save", 100, 1)
    private val b = Candidate("b.autosave", 300, 1)
    private val c = Candidate("c.save", 200, 1)
    private val undated = Candidate("u.save", null, 1)
    private val junk = Candidate("notes.txt", 999, 1)

    @Test fun orderNewestFirstUndatedLastNonSavesDropped() {
        val o = SaveFallback.order(listOf(a, undated, junk, b, c)).map { it.name }
        assertEquals(listOf("b.autosave", "c.save", "a.save", "u.save"), o)
    }

    @Test fun newestReadableSkipsUndecodableAndFlagsIt() {
        val p = SaveFallback.newestReadable(listOf(a, b, c)) { cand -> if (cand == b) null else cand.name }!!
        assertEquals("c.save", p.candidate.name)
        assertTrue(p.skippedUnreadable)
    }

    @Test fun newestReadableNoSkipWhenNewestOk() {
        val p = SaveFallback.newestReadable(listOf(a, b)) { it.name }!!
        assertEquals("b.autosave", p.candidate.name)
        assertFalse(p.skippedUnreadable)
    }

    @Test fun highestTimePlayedPicksMaxAndSkipsFailures() {
        val times = mapOf("a.save" to 50L, "c.save" to 90L, "u.save" to 70L)
        val p = SaveFallback.highestTimePlayed(listOf(a, b, c, undated), { v: Long -> v }) { times[it.name] }!!
        assertEquals("c.save", p.candidate.name)
        assertEquals(90L, p.value)
        assertTrue(p.skippedUnreadable)
    }

    @Test fun allFailGivesNull() {
        assertNull(SaveFallback.newestReadable(listOf(a, b)) { null as String? })
        assertNull(SaveFallback.highestTimePlayed(listOf(a, b), { v: Long -> v }) { null })
        assertNull(SaveFallback.newestReadable(emptyList()) { "x" })
    }
}
