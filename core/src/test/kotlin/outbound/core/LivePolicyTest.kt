package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePolicyTest {
    private val pos = Position(1f, 2f, 3f)
    private val now = 1_000_000_000L
    private fun snap(p: Position? = pos, change: Long? = null, live: Long? = null, save: Long? = null, changedWall: Long? = null) =
        LiveSnapshot(p, change, live, save, now, changedWall)

    @Test fun freshLive() {
        val d = LivePolicy.decide(snap(change = 800, live = now, save = now - 600_000))
        assertEquals(PositionSource.LIVE, d.source); assertEquals(ChipState.LIVE, d.chip); assertEquals(pos, d.position)
    }

    @Test fun freshBoundaryIsLive() { assertEquals(ChipState.LIVE, LivePolicy.decide(snap(change = 5_000)).chip) }

    @Test fun staleSeenThisSessionKeepsLastPosition() {
        // last change 60 s ago, save is older than that change: keep the paused live position
        val d = LivePolicy.decide(snap(change = 60_000, live = now - 60_000, save = now - 120_000, changedWall = now - 60_000))
        assertEquals(PositionSource.LIVE_STALE, d.source); assertEquals(ChipState.PAUSED, d.chip)
        assertEquals(pos, d.position); assertEquals(60_000L, d.ageMs)
    }

    @Test fun staleSeenThisSessionButNewerSaveFallsBackToSave() {
        val d = LivePolicy.decide(snap(change = 60_000, live = now - 60_000, save = now - 10_000, changedWall = now - 60_000))
        assertEquals(PositionSource.SAVE, d.source); assertEquals(ChipState.SAVED, d.chip); assertNull(d.position)
    }

    @Test fun staleSeenWithoutWallTimeKeepsLastPosition() {
        val d = LivePolicy.decide(snap(change = 60_000, live = now - 60_000, save = now - 10_000))
        assertEquals(ChipState.PAUSED, d.chip)
    }

    @Test fun freshLiveBeatsSaveRegardlessOfSaveTime() {
        val d = LivePolicy.decide(snap(change = 1_000, live = now, save = now, changedWall = now - 1_000))
        assertEquals(ChipState.LIVE, d.chip)
    }

    @Test fun staleNewerThanSaveNotSeenYet() {
        val d = LivePolicy.decide(snap(change = null, live = now - 90_000, save = now - 22 * 60_000))
        assertEquals(ChipState.PAUSED, d.chip); assertEquals(pos, d.position); assertEquals(90_000L, d.ageMs)
    }

    @Test fun staleOlderThanSaveNotSeenFallsBackToSave() {
        val d = LivePolicy.decide(snap(change = null, live = now - 40 * 60_000, save = now - 22 * 60_000))
        assertEquals(PositionSource.SAVE, d.source); assertEquals(ChipState.SAVED, d.chip); assertNull(d.position)
    }

    @Test fun staleNoTimestampsNotSeenFallsBackToSave() {
        assertEquals(ChipState.SAVED, LivePolicy.decide(snap(change = null)).chip)
        assertEquals(ChipState.SAVED, LivePolicy.decide(snap(change = null, live = now, save = null)).chip)
    }

    @Test fun noLiveFile() {
        val d = LivePolicy.decide(snap(p = null))
        assertEquals(PositionSource.SAVE, d.source); assertEquals(ChipState.SAVED, d.chip); assertNull(d.position)
    }

    @Test fun allZeroFile() {
        val d = LivePolicy.decide(snap(p = LivePosition.parse(ByteArray(16)), change = 100, live = now))
        assertEquals(ChipState.SAVED, d.chip); assertNull(d.position)
    }

    @Test fun prettyPaused() {
        assertEquals("Paused · last seen just now", Pretty.pausedAgo(400))
        assertEquals("Paused · last seen 45 s ago", Pretty.pausedAgo(45_000))
        assertEquals("Paused · last seen 3 min ago", Pretty.pausedAgo(190_000))
        assertEquals("Paused · last seen 2 h ago", Pretty.pausedAgo(2 * 3600_000L + 5))
        assertEquals("Paused · last seen 3 d ago", Pretty.pausedAgo(3 * 86_400_000L))
        assertEquals("Paused · last seen earlier", Pretty.pausedAgo(null))
        assertEquals("Paused · last seen just now", Pretty.pausedAgo(-5))
    }
}
