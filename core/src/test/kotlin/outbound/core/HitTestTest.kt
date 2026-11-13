package outbound.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HitTestTest {
    private val xs = floatArrayOf(100f, 110f, 300f)
    private val ys = floatArrayOf(100f, 100f, 300f)

    @Test fun nearestWins() = assertEquals(1, HitTest.nearest(xs, ys, 3, 108f, 100f, 24f))

    @Test fun outsideRadiusIsNone() = assertEquals(-1, HitTest.nearest(xs, ys, 3, 200f, 200f, 24f))

    @Test fun radiusIsInclusive() = assertEquals(0, HitTest.nearest(xs, ys, 1, 124f, 100f, 24f))

    @Test fun countLimitsTheSearch() = assertEquals(-1, HitTest.nearest(xs, ys, 2, 300f, 300f, 24f))

    @Test fun emptyAndNonFinite() {
        assertEquals(-1, HitTest.nearest(xs, ys, 0, 100f, 100f, 24f))
        assertEquals(-1, HitTest.nearest(floatArrayOf(Float.NaN), floatArrayOf(0f), 1, 0f, 0f, 24f))
    }

    @Test fun tieGoesToLowerIndex() = assertEquals(0, HitTest.nearest(floatArrayOf(90f, 110f), floatArrayOf(100f, 100f), 2, 100f, 100f, 24f))
}
