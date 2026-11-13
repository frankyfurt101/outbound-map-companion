package outbound.core

/**
 * World (x, z) -> pixel on a square map image. The default orientation is an UNVERIFIED assumption,
 * so all 8 variants (swapXZ x flipX x flipZ) stay selectable.
 */
data class MapParams(
    val worldSize: Double = 1500.0,
    val offsetX: Double = 0.0,
    val offsetZ: Double = 0.0,
    val flipX: Boolean = false,
    val flipZ: Boolean = false,
    val swapXZ: Boolean = false,
) {
    val variantName: String
        get() = listOfNotNull("swapXZ".takeIf { swapXZ }, "flipX".takeIf { flipX }, "flipZ".takeIf { flipZ })
            .ifEmpty { listOf("default") }.joinToString("+")
}

object MapCalibration {
    const val IMAGE_SIZE = 2048.0
    const val VARIANT_COUNT = 8

    fun defaultsFor(regionId: String): MapParams =
        if (regionId == Region.STARTING_LEVEL.id) MapParams(offsetX = -170.0, offsetZ = -421.0) else MapParams()

    /** Variant 0 is the default orientation; bit0 = flipX, bit1 = flipZ, bit2 = swapXZ. */
    fun variant(regionId: String, index: Int): MapParams {
        val i = ((index % VARIANT_COUNT) + VARIANT_COUNT) % VARIANT_COUNT
        return defaultsFor(regionId).copy(flipX = i and 1 != 0, flipZ = i and 2 != 0, swapXZ = i and 4 != 0)
    }

    fun project(x: Double, z: Double, p: MapParams): Pair<Double, Double> {
        val a = x - p.offsetX
        val b = z - p.offsetZ
        val u = (if (p.swapXZ) b else a) / p.worldSize
        val v = (if (p.swapXZ) a else b) / p.worldSize
        val px = (if (p.flipX) 1.0 - u else u) * IMAGE_SIZE
        val py = (if (p.flipZ) v else 1.0 - v) * IMAGE_SIZE
        return px to py
    }
}
