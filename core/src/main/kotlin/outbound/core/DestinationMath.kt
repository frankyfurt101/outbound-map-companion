package outbound.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Edge arrow: where it sits on the rectangle and the direction (radians, screen space, 0 = right, y down) it points. */
data class EdgeArrow(val x: Float, val y: Float, val angle: Float)

object DestinationMath {
    /** Straight-line distance in the x/z plane, in world units (shown as metres). */
    fun distance(ax: Double, az: Double, bx: Double, bz: Double): Double = hypot(ax - bx, az - bz)

    /** "720 m"; "10.0 km" from 10 km up, decided on the rounded metre value (9 999.6 -> "10.0 km"). */
    fun distanceText(m: Double): String {
        if (m.isNaN() || m < 0) return ""
        val rounded = Math.round(m)
        return if (rounded >= 10_000) "%.1f km".format(java.util.Locale.US, m / 1000) else "$rounded m"
    }

    /**
     * Zoom to use when the view is centred on the destination and the player must be visible too.
     * Points are in image pixels; [scaleAtZoom1] = screen pixels per image pixel at zoom 1; [margin] keeps both off the edge.
     * Never zooms in: the result is at most [currentZoom], and at least [ZoomMath.MIN_ZOOM].
     */
    fun zoomToFitBoth(
        destX: Float, destY: Float, playerX: Float, playerY: Float,
        scaleAtZoom1: Float, viewW: Float, viewH: Float, margin: Float, currentZoom: Float,
    ): Float {
        val halfW = viewW / 2f - margin
        val halfH = viewH / 2f - margin
        if (scaleAtZoom1 <= 0f || halfW <= 0f || halfH <= 0f) return ZoomMath.clampZoom(currentZoom)
        val dx = abs(playerX - destX)
        val dy = abs(playerY - destY)
        var needed = Float.MAX_VALUE
        if (dx > 0f) needed = minOf(needed, halfW / (dx * scaleAtZoom1))
        if (dy > 0f) needed = minOf(needed, halfH / (dy * scaleAtZoom1))
        return ZoomMath.clampZoom(minOf(currentZoom, needed))
    }

    /**
     * Zoom so that two points both lie inside a [rectW] x [rectH] rectangle with [margin] on every side (the points
     * are not assumed to be centred). Points in image pixels; [scaleAtZoom1] = screen pixels per image pixel at zoom 1.
     * Never zooms in (at most [currentZoom]); at least [ZoomMath.MIN_ZOOM].
     */
    fun zoomToFitPair(
        ax: Float, ay: Float, bx: Float, by: Float,
        scaleAtZoom1: Float, rectW: Float, rectH: Float, margin: Float, currentZoom: Float,
    ): Float {
        val availW = rectW - 2f * margin
        val availH = rectH - 2f * margin
        if (scaleAtZoom1 <= 0f || availW <= 0f || availH <= 0f) return ZoomMath.clampZoom(currentZoom)
        val dx = abs(ax - bx)
        val dy = abs(ay - by)
        var needed = Float.MAX_VALUE
        if (dx > 0f) needed = minOf(needed, availW / (dx * scaleAtZoom1))
        if (dy > 0f) needed = minOf(needed, availH / (dy * scaleAtZoom1))
        return ZoomMath.clampZoom(minOf(currentZoom, needed))
    }

    /**
     * Arrow for a target outside the rectangle (left, top, right, bottom): the point where the line from the rectangle
     * centre to the target crosses the rectangle border, pointing at the target. Null when the target is inside.
     */
    fun edgeArrow(tx: Float, ty: Float, left: Float, top: Float, right: Float, bottom: Float): EdgeArrow? {
        if (right <= left || bottom <= top) return null
        if (tx in left..right && ty in top..bottom) return null
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val dx = tx - cx
        val dy = ty - cy
        if (dx == 0f && dy == 0f) return null
        val hw = (right - left) / 2f
        val hh = (bottom - top) / 2f
        val sx = if (dx != 0f) hw / abs(dx) else Float.MAX_VALUE
        val sy = if (dy != 0f) hh / abs(dy) else Float.MAX_VALUE
        val s = minOf(sx, sy)
        return EdgeArrow(cx + dx * s, cy + dy * s, atan2(dy, dx))
    }
}
