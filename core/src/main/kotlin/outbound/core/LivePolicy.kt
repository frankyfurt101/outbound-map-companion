package outbound.core

enum class PositionSource { LIVE, LIVE_STALE, SAVE }

enum class ChipState { LIVE, PAUSED, SAVED }

/**
 * What the app knows about the live file right now.
 * [position]: last valid position read this session (null = no usable data: no file, short read, all zeros, NaN...).
 * [changeAgeMs]: time since a change (bytes differ, or lastModified advanced) was last observed this session; null = never observed.
 * [liveModified] / [saveModified]: provider lastModified of livepos.bin / the save in use, when reported.
 * [changedWallMs]: wall-clock time of that last change (null = never observed).
 */
data class LiveSnapshot(
    val position: Position?,
    val changeAgeMs: Long?,
    val liveModified: Long?,
    val saveModified: Long?,
    val nowWallMs: Long,
    val changedWallMs: Long? = null,
)

/** [ageMs] is the time since the live position last changed (null = unknown). */
data class PositionDecision(val source: PositionSource, val chip: ChipState, val position: Position?, val ageMs: Long?)

object LivePolicy {
    const val FRESH_MS = 5_000L

    fun decide(s: LiveSnapshot): PositionDecision {
        val pos = s.position ?: return PositionDecision(PositionSource.SAVE, ChipState.SAVED, null, null)
        val change = s.changeAgeMs
        if (change != null && change <= FRESH_MS) return PositionDecision(PositionSource.LIVE, ChipState.LIVE, pos, maxOf(0L, change))
        // Stale: keep it only if it was seen live this session (and no newer save exists), or the file is newer than the save in use.
        val lm = s.liveModified
        val sm = s.saveModified
        val newerThanSave = lm != null && sm != null && lm > sm
        // A save written after the last live change is newer information than the stale live position.
        val cw = s.changedWallMs
        if (change != null && cw != null && sm != null && sm > cw) return PositionDecision(PositionSource.SAVE, ChipState.SAVED, null, null)
        if (change == null && !newerThanSave) return PositionDecision(PositionSource.SAVE, ChipState.SAVED, null, null)
        val age = change ?: lm?.let { s.nowWallMs - it }
        return PositionDecision(PositionSource.LIVE_STALE, ChipState.PAUSED, pos, age?.let { maxOf(0L, it) })
    }
}
