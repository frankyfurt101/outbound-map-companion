package outbound.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** [playerState]: 0 = on foot (also "unknown" and the old 12-byte patch), 1 = driving the van, 6 = passenger seat. */
data class Position(val x: Float, val y: Float, val z: Float, val playerState: Int = 0) {
    val inVehicle: Boolean get() = playerState == LivePosition.STATE_DRIVING || playerState == LivePosition.STATE_PASSENGER
}

/**
 * Parses livepos.bin: three little-endian IEEE-754 floats (x, y, z) and, in the 16-byte layout, an int32 playerState.
 * Files of 12..15 bytes (old patch) parse with playerState 0.
 */
object LivePosition {
    const val BYTES = 12
    const val FULL_BYTES = 16
    const val STATE_DRIVING = 1
    const val STATE_PASSENGER = 6
    const val MAX_STATE = 63
    const val MAX_ABS_XZ = 100_000f

    /** Null for too-short input, all-zero x/y/z ("no data yet"), NaN/Infinity, or |x| / |z| beyond [MAX_ABS_XZ]. */
    fun parse(bytes: ByteArray): Position? {
        if (bytes.size < BYTES) return null
        if ((0 until BYTES).all { bytes[it].toInt() == 0 }) return null
        val b = ByteBuffer.wrap(bytes, 0, BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val x = b.getFloat(0)
        val y = b.getFloat(4)
        val z = b.getFloat(8)
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        if (kotlin.math.abs(x) > MAX_ABS_XZ || kotlin.math.abs(z) > MAX_ABS_XZ) return null
        var state = 0
        if (bytes.size >= FULL_BYTES) {
            state = ByteBuffer.wrap(bytes, 0, FULL_BYTES).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
            if (state !in 0..MAX_STATE) state = 0 // unknown, not an invalid read
        }
        return Position(x, y, z, state)
    }
}
