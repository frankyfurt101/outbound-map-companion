package outbound.core

enum class VanSource { SAVE, LIVE, LAST_SEEN }

/** Where the van was last seen with the player in a vehicle (wall-clock time of the sample). */
data class VanMemory(val x: Double, val y: Double, val z: Double, val region: String, val wallMs: Long)

/**
 * [saveVan]/[saveModifiedMs]: van position and provider lastModified of the save in use (null modified = unknown).
 * [live]/[liveFresh]: current live sample and whether a change was seen within the fresh window.
 * [memory]: the remembered "van last seen" (may be from a previous run or another region).
 */
data class VanInput(
    val region: String,
    val saveVan: Vec3?,
    val saveModifiedMs: Long?,
    val live: Position?,
    val liveFresh: Boolean,
    val memory: VanMemory?,
    val nowWallMs: Long,
)

/** [memory] is the memory to keep afterwards (null = none / cleared). [ageMs] is set for LIVE (0) and LAST_SEEN. */
data class VanDecision(val source: VanSource, val position: Vec3?, val memory: VanMemory?, val ageMs: Long?)

object VanPolicy {
    fun decide(i: VanInput): VanDecision {
        var mem = i.memory
        val sm = i.saveModifiedMs
        // Another region, or a save at least as new as the memory: the memory no longer applies. Unknown save time keeps it.
        if (mem != null && (mem.region != i.region || (sm != null && mem.wallMs <= sm))) mem = null
        val live = i.live
        if (live != null && i.liveFresh && live.inVehicle) {
            val pos = Vec3(live.x.toDouble(), live.y.toDouble(), live.z.toDouble())
            return VanDecision(VanSource.LIVE, pos, VanMemory(pos.x, pos.y, pos.z, i.region, i.nowWallMs), 0L)
        }
        if (mem != null) {
            return VanDecision(VanSource.LAST_SEEN, Vec3(mem.x, mem.y, mem.z), mem, maxOf(0L, i.nowWallMs - mem.wallMs))
        }
        return VanDecision(VanSource.SAVE, i.saveVan, null, null)
    }
}
