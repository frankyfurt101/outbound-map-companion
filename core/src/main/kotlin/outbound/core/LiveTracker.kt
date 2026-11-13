package outbound.core

/**
 * Change detection for livepos.bin, kept out of the UI. Not thread-safe: call from one thread (the main thread).
 *
 * A change is observed when the (valid) bytes differ from the previous valid read, or the provider's lastModified
 * advanced while THIS read parsed to a valid position. The first read of a session is not a change, so a stale file
 * at start is not shown as live. An invalid read (short, all zeros, NaN, out of range, no bytes) keeps the previous
 * good bytes and position and never counts as a change.
 */
class LiveTracker {
    /** Last valid position this session (null = none). */
    var position: Position? = null
        private set
    /** Provider lastModified of livepos.bin at the last poll that reported one. */
    var modified: Long? = null
        private set
    /** Wall-clock time of the last observed change (null = none yet). */
    var changedWallMs: Long? = null
        private set
    private var bytes: ByteArray? = null
    private var changedElapsedMs: Long? = null

    /** [bytes] null = no read happened (file missing / read failed); [lastModified] null = not reported. */
    fun observe(bytes: ByteArray?, lastModified: Long?, nowElapsed: Long, nowWall: Long) {
        var validRead = false
        if (bytes != null) {
            val head = bytes.copyOf(minOf(bytes.size, LivePosition.FULL_BYTES))
            val pos = LivePosition.parse(head)
            if (pos != null) {
                validRead = true
                val prev = this.bytes
                if (prev != null && !prev.contentEquals(head)) markChange(nowElapsed, nowWall)
                this.bytes = head
                position = pos
            }
        }
        if (lastModified != null) {
            val prev = modified
            if (prev != null && lastModified > prev && validRead) markChange(nowElapsed, nowWall)
            modified = lastModified
        }
    }

    /** Time since the last observed change; null = never observed this session. */
    fun changeAgeMs(nowElapsed: Long): Long? = changedElapsedMs?.let { nowElapsed - it }

    /** Forget everything (folder change, or the loaded save moved to another region). */
    fun reset() {
        position = null; bytes = null; modified = null; changedElapsedMs = null; changedWallMs = null
    }

    private fun markChange(nowElapsed: Long, nowWall: Long) {
        changedElapsedMs = nowElapsed
        changedWallMs = nowWall
    }
}
