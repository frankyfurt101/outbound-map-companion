package outbound.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTrackerTest {
    private fun bytes(x: Float, y: Float = 1f, z: Float = 2f): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(x).putFloat(y).putFloat(z).array()

    @Test fun firstReadIsNotAChange() {
        val t = LiveTracker()
        t.observe(bytes(5f), 1000L, 100, 5000)
        assertNotNull(t.position); assertNull(t.changeAgeMs(200)); assertNull(t.changedWallMs)
    }

    @Test fun differentBytesAreAChange() {
        val t = LiveTracker()
        t.observe(bytes(5f), 1000L, 100, 5000)
        t.observe(bytes(6f), 1000L, 1100, 6000)
        assertEquals(400L, t.changeAgeMs(1500)); assertEquals(6000L, t.changedWallMs)
        assertEquals(6f, t.position!!.x, 0f)
    }

    @Test fun sameBytesMtimeAdvancingIsAChange() {
        val t = LiveTracker()
        t.observe(bytes(5f), 1000L, 100, 5000)
        t.observe(bytes(5f), 1000L, 200, 5100)
        assertNull(t.changeAgeMs(300))
        t.observe(bytes(5f), 2000L, 400, 5300)
        assertEquals(100L, t.changeAgeMs(500)); assertEquals(5300L, t.changedWallMs)
    }

    @Test fun zerosWithMtimeAdvancingIsNotAChangeAndKeepsPosition() {
        val t = LiveTracker()
        t.observe(bytes(5f), 1000L, 100, 5000)
        t.observe(ByteArray(16), 2000L, 200, 5100)
        assertNull(t.changeAgeMs(300))
        assertEquals(5f, t.position!!.x, 0f)
        assertEquals(2000L, t.modified)
    }

    @Test fun shortReadKeepsPreviousAndIsNotAChange() {
        val t = LiveTracker()
        t.observe(bytes(5f), 1000L, 100, 5000)
        t.observe(ByteArray(8) { 7 }, 3000L, 200, 5100)
        t.observe(null, null, 300, 5200)
        assertNull(t.changeAgeMs(400)); assertEquals(5f, t.position!!.x, 0f)
    }

    @Test fun recoveryAfterGarbageComparesAgainstLastGoodBytes() {
        val t = LiveTracker()
        t.observe(bytes(5f), 1000L, 100, 5000)
        t.observe(ByteArray(16), 1000L, 200, 5100) // garbage
        t.observe(bytes(5f), 1000L, 300, 5200)     // same as last good: no change
        assertNull(t.changeAgeMs(400))
        t.observe(bytes(9f), 1000L, 500, 5400)     // differs: change
        assertEquals(100L, t.changeAgeMs(600))
    }

    @Test fun resetForgetsEverything() {
        val t = LiveTracker()
        t.observe(bytes(5f), 1000L, 100, 5000)
        t.observe(bytes(6f), 1000L, 200, 5100)
        t.reset()
        assertNull(t.position); assertNull(t.changeAgeMs(300)); assertNull(t.changedWallMs); assertNull(t.modified)
        t.observe(bytes(7f), 1000L, 400, 5300) // first read again: not a change
        assertNull(t.changeAgeMs(500))
    }

    @Test fun stateChangeAloneIsAChangeAndPositionCarriesState() {
        fun withState(st: Int) = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(5f).putFloat(1f).putFloat(2f).putInt(st).array()
        val t = LiveTracker()
        t.observe(withState(0), 1000L, 100, 5000)
        t.observe(withState(1), 1000L, 200, 5100)
        assertEquals(100L, t.changeAgeMs(300)); assertEquals(true, t.position!!.inVehicle)
    }
}
