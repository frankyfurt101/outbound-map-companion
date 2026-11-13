package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LivePositionTest {
    private fun bytes(x: Float, y: Float, z: Float, size: Int = 16, state: Int = 0): ByteArray {
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).putFloat(x).putFloat(y).putFloat(z)
        if (size >= 16) b.putInt(state)
        return b.array()
    }

    @Test fun knownGoodSample() {
        val p = LivePosition.parse(bytes(1310.4f, 61.39f, 798.03f))!!
        assertEquals(1310.4f, p.x, 0f); assertEquals(61.39f, p.y, 0f); assertEquals(798.03f, p.z, 0f)
    }

    @Test fun exactly12BytesOk() { assertNotNull(LivePosition.parse(bytes(1f, 2f, 3f, 12))) }

    @Test fun tooShort() { assertNull(LivePosition.parse(bytes(1f, 2f, 3f, 12).copyOf(11))) }

    @Test fun empty() { assertNull(LivePosition.parse(ByteArray(0))) }

    @Test fun allZero() { assertNull(LivePosition.parse(ByteArray(16))) }

    @Test fun nanRejected() {
        assertNull(LivePosition.parse(bytes(Float.NaN, 1f, 1f)))
        assertNull(LivePosition.parse(bytes(1f, Float.NaN, 1f)))
        assertNull(LivePosition.parse(bytes(1f, 1f, Float.NaN)))
    }

    @Test fun infinityRejected() {
        assertNull(LivePosition.parse(bytes(Float.POSITIVE_INFINITY, 1f, 1f)))
        assertNull(LivePosition.parse(bytes(1f, Float.NEGATIVE_INFINITY, 1f)))
    }

    @Test fun outOfRangeRejected() {
        assertNull(LivePosition.parse(bytes(100001f, 1f, 1f)))
        assertNull(LivePosition.parse(bytes(1f, 1f, -100001f)))
    }

    @Test fun boundaryAccepted() { assertNotNull(LivePosition.parse(bytes(100000f, 5f, -100000f))) }

    @Test fun stateParsed() {
        val d = LivePosition.parse(bytes(1f, 2f, 3f, state = 1))!!
        assertEquals(1, d.playerState); assertEquals(true, d.inVehicle)
        assertEquals(true, LivePosition.parse(bytes(1f, 2f, 3f, state = 6))!!.inVehicle)
        assertEquals(false, LivePosition.parse(bytes(1f, 2f, 3f, state = 0))!!.inVehicle)
        val other = LivePosition.parse(bytes(1f, 2f, 3f, state = 3))!!
        assertEquals(3, other.playerState); assertEquals(false, other.inVehicle)
    }

    @Test fun oldPatchSizesGiveStateZero() {
        for (n in 12..15) {
            val p = LivePosition.parse(bytes(1f, 2f, 3f, 12).copyOf(n))!!
            assertEquals(0, p.playerState); assertEquals(false, p.inVehicle)
        }
    }

    @Test fun outOfRangeStateIsUnknownNotInvalid() {
        assertEquals(0, LivePosition.parse(bytes(1f, 2f, 3f, state = 64))!!.playerState)
        assertEquals(0, LivePosition.parse(bytes(1f, 2f, 3f, state = -1))!!.playerState)
        assertEquals(0, LivePosition.parse(bytes(1f, 2f, 3f, state = Int.MAX_VALUE))!!.playerState)
        assertEquals(63, LivePosition.parse(bytes(1f, 2f, 3f, state = 63))!!.playerState)
    }

    @Test fun zeroPositionWithStateStillNoData() { assertNull(LivePosition.parse(bytes(0f, 0f, 0f, state = 1))) }
}
