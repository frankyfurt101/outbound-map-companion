package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewMathTest {
    @Test fun clampZoomLimits() {
        assertEquals(1f, ZoomMath.clampZoom(0.3f), 0f)
        assertEquals(8f, ZoomMath.clampZoom(20f), 0f)
        assertEquals(3f, ZoomMath.clampZoom(3f), 0f)
        assertEquals(1f, ZoomMath.clampZoom(Float.NaN), 0f)
    }

    @Test fun fitScaleUsesSmallerSide() {
        assertEquals(0.5f, ZoomMath.fitScale(2048f, 2048f, 1024f, 1500f), 1e-6f)
        assertEquals(1f, ZoomMath.fitScale(0f, 10f, 5f, 5f), 0f)
    }

    @Test fun doubleTapDoublesThenResets() {
        assertEquals(2f, ZoomMath.doubleTapTarget(1f), 0f)
        assertEquals(8f, ZoomMath.doubleTapTarget(5f), 0f)
        assertEquals(1f, ZoomMath.doubleTapTarget(8f), 0f)
    }

    @Test fun zoomAroundKeepsFocusPoint() {
        val t = 40f; val s0 = 0.5f; val s1 = 1.5f; val focus = 300f
        val imgPoint = (focus - t) / s0
        val t1 = ZoomMath.zoomAround(t, s0, s1, focus)
        assertEquals(focus, imgPoint * s1 + t1, 1e-3f)
    }

    @Test fun centreMathRoundTrips() {
        val t = ZoomMath.translationToCenter(1000f, 0.8f, 600f)
        assertEquals(300f, 1000f * 0.8f + t, 1e-3f)
        assertEquals(1000f, ZoomMath.centerOf(t, 0.8f, 600f), 1e-3f)
    }

    @Test fun clampPanSmallImageIsCentred() {
        assertEquals(50f, ZoomMath.clampPan(-999f, 1f, 100f, 200f, false), 0f)
    }

    @Test fun clampPanLargeImageHasNoGap() {
        assertEquals(0f, ZoomMath.clampPan(30f, 1f, 500f, 200f, false), 0f)
        assertEquals(-300f, ZoomMath.clampPan(-900f, 1f, 500f, 200f, false), 0f)
        assertEquals(-100f, ZoomMath.clampPan(-100f, 1f, 500f, 200f, false), 0f)
    }

    @Test fun clampPanLooseAllowsCentringEdgePoints() {
        val s = 2f; val img = 500f; val view = 200f
        for (p in listOf(0f, img)) {
            val t = ZoomMath.translationToCenter(p, s, view)
            assertEquals(t, ZoomMath.clampPan(t, s, img, view, true), 1e-3f)
        }
        // also works for a small image where normal mode would force centring
        val t = ZoomMath.translationToCenter(0f, 0.1f, 200f)
        assertEquals(t, ZoomMath.clampPan(t, 0.1f, 500f, 200f, true), 1e-3f)
    }

    @Test fun destinationPrettified() {
        assertEquals("Bixby Bridge", Pretty.destination("bixby-bridge"))
        assertEquals("Big Sur Camp", Pretty.destination("big_sur  camp"))
        assertEquals("", Pretty.destination(""))
    }

    @Test fun savedAgoFormatting() {
        assertEquals("Saved just now", Pretty.savedAgo(20_000))
        assertEquals("Saved just now", Pretty.savedAgo(-5_000))
        assertEquals("Saved 4 min ago", Pretty.savedAgo(4 * 60_000L + 5))
        assertEquals("Saved 59 min ago", Pretty.savedAgo(59 * 60_000L))
        assertEquals("Saved 2 h ago", Pretty.savedAgo(2 * 3600_000L + 1))
        assertEquals("Saved 3 d ago", Pretty.savedAgo(3 * 86_400_000L))
        assertEquals("Saved (time unknown)", Pretty.savedAgo(null))
    }
}
