package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationMathTest {
    @Test fun distanceIsXZPlane() {
        assertEquals(5.0, DestinationMath.distance(0.0, 0.0, 3.0, 4.0), 1e-9)
        assertEquals("720 m", DestinationMath.distanceText(719.6))
        assertEquals("0 m", DestinationMath.distanceText(0.2))
        assertEquals("12.4 km", DestinationMath.distanceText(12_400.0))
    }

    @Test fun fitBothNeverZoomsIn() {
        // already close together: keep the current zoom
        assertEquals(2f, DestinationMath.zoomToFitBoth(100f, 100f, 110f, 110f, 0.5f, 1000f, 600f, 40f, 2f), 1e-6f)
    }

    @Test fun fitBothZoomsOutJustEnough() {
        // dx = 800 image px, scale at zoom 1 = 0.5 -> half-width 500-40 = 460 -> needed = 460 / (800*0.5) = 1.15
        val z = DestinationMath.zoomToFitBoth(1000f, 1000f, 1800f, 1000f, 0.5f, 1000f, 600f, 40f, 4f)
        assertEquals(1.15f, z, 1e-4f)
        // player is exactly at the limit afterwards
        assertEquals(460f, 800f * 0.5f * z, 0.1f)
    }

    @Test fun fitBothUsesTheTighterAxisAndClampsToWholeMap() {
        // vertical is tighter: dy=1500, half-height 300-40=260 -> 260/(1500*0.5)=0.35 -> clamped to 1
        assertEquals(1f, DestinationMath.zoomToFitBoth(100f, 100f, 200f, 1600f, 0.5f, 1000f, 600f, 40f, 3f), 0f)
        assertEquals(3f, DestinationMath.zoomToFitBoth(0f, 0f, 1f, 1f, 0f, 1000f, 600f, 40f, 3f), 0f) // bad scale -> keep current zoom
    }

    @Test fun distanceTextSwitchesToKmOnRoundedValue() {
        assertEquals("10.0 km", DestinationMath.distanceText(9_999.6))
        assertEquals("9999 m", DestinationMath.distanceText(9_999.4))
    }

    @Test fun fitPairKeepsMarginOnEverySide() {
        // dx = 800, scale 0.5, rect 1000 wide, margin 40 -> avail 920 -> needed 920 / 400 = 2.3
        val z = DestinationMath.zoomToFitPair(0f, 0f, 800f, 0f, 0.5f, 1000f, 600f, 40f, 4f)
        assertEquals(2.3f, z, 1e-4f)
        assertEquals(920f, 800f * 0.5f * z, 0.1f)
    }

    @Test fun fitPairNeverZoomsInAndClamps() {
        assertEquals(2f, DestinationMath.zoomToFitPair(0f, 0f, 10f, 10f, 0.5f, 1000f, 600f, 40f, 2f), 0f)
        assertEquals(1f, DestinationMath.zoomToFitPair(0f, 0f, 100f, 5000f, 0.5f, 1000f, 600f, 40f, 3f), 0f)
        assertEquals(3f, DestinationMath.zoomToFitPair(0f, 0f, 1f, 1f, 0f, 1000f, 600f, 40f, 3f), 0f)
        assertEquals(3f, DestinationMath.zoomToFitPair(0f, 0f, 1f, 1f, 0.5f, 60f, 600f, 40f, 3f), 0f) // margin eats the rect
    }

    @Test fun insideRectGivesNoArrow() {
        assertNull(DestinationMath.edgeArrow(500f, 300f, 100f, 100f, 900f, 500f))
        assertNull(DestinationMath.edgeArrow(100f, 100f, 100f, 100f, 900f, 500f)) // on the border counts as inside
    }

    @Test fun arrowOnRightEdge() {
        val a = DestinationMath.edgeArrow(2000f, 300f, 100f, 100f, 900f, 500f)!!
        assertEquals(900f, a.x, 1e-3f)
        assertEquals(300f, a.y, 1e-3f)
        assertEquals(0f, a.angle, 1e-6f)
    }

    @Test fun arrowOnTopEdgeKeepsMargin() {
        // target straight above: crossing at the top margin (top = 150, i.e. below the bar)
        val a = DestinationMath.edgeArrow(500f, -400f, 100f, 150f, 900f, 500f)!!
        assertEquals(150f, a.y, 1e-3f)
        assertEquals(500f, a.x, 1e-3f)
        assertEquals(-Math.PI.toFloat() / 2f, a.angle, 1e-5f)
    }

    @Test fun diagonalTargetHitsACornerRegionOnTheBorder() {
        val a = DestinationMath.edgeArrow(3000f, -2000f, 100f, 100f, 900f, 500f)!!
        assertTrue(a.x in 100f..900f && a.y in 100f..500f)
        assertTrue(a.x == 900f || a.y == 100f)
        assertTrue(a.angle < 0f) // up and to the right
        assertNotNull(a)
    }

    @Test fun degenerateRectGivesNoArrow() {
        assertNull(DestinationMath.edgeArrow(0f, 0f, 100f, 100f, 100f, 500f))
    }
}
