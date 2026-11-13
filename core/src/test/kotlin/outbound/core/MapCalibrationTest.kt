package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCalibrationTest {
    @Test fun defaultFormula() {
        val (px, py) = MapCalibration.project(750.0, 750.0, MapCalibration.variant("canyons", 0))
        assertEquals(1024.0, px, 1e-9); assertEquals(1024.0, py, 1e-9)
        val (qx, qy) = MapCalibration.project(0.0, 0.0, MapCalibration.variant("canyons", 0))
        assertEquals(0.0, qx, 1e-9); assertEquals(2048.0, qy, 1e-9)
    }

    @Test fun startingLevelOffset() {
        val p = MapCalibration.variant("startingLevel", 0)
        assertEquals(-170.0, p.offsetX, 0.0); assertEquals(-421.0, p.offsetZ, 0.0)
        val (px, py) = MapCalibration.project(-170.0, -421.0, p)
        assertEquals(0.0, px, 1e-9); assertEquals(2048.0, py, 1e-9)
    }

    @Test fun eightVariantsAreDistinct() {
        val pts = (0 until 8).map { MapCalibration.project(300.0, 700.0, MapCalibration.variant("pacificCoast", it)) }
        assertEquals(8, pts.map { "%.3f,%.3f".format(it.first, it.second) }.toSet().size)
        assertEquals(8, (0 until 8).map { MapCalibration.variant("pacificCoast", it).variantName }.toSet().size)
        assertEquals("default", MapCalibration.variant("pacificCoast", 0).variantName)
        assertTrue(MapCalibration.variant("pacificCoast", 7).swapXZ)
    }

    @Test fun variantIndexWraps() {
        assertEquals(MapCalibration.variant("canyons", 1), MapCalibration.variant("canyons", 9))
    }
}
