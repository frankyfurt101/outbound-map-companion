package outbound.core

/** Screen-space hit testing for map icons. */
object HitTest {
    /**
     * Index (0 until [count]) of the point nearest to ([px], [py]) that lies within [radius] (inclusive), or -1.
     * [xs]/[ys] are screen coordinates; non-finite points are ignored. Ties go to the lower index. Allocation free.
     */
    fun nearest(xs: FloatArray, ys: FloatArray, count: Int, px: Float, py: Float, radius: Float): Int {
        var best = -1
        var bestD = radius * radius
        val n = minOf(count, xs.size, ys.size)
        for (i in 0 until n) {
            val dx = xs[i] - px
            val dy = ys[i] - py
            val d = dx * dx + dy * dy
            if (d <= bestD && !d.isNaN() && (best < 0 || d < bestD)) { best = i; bestD = d }
        }
        return best
    }
}
