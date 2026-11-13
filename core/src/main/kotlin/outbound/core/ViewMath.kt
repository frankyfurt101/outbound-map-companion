package outbound.core

/** Persisted map view: zoom relative to "whole map fits", centre in image pixel space, follow flag. */
data class MapViewState(val zoom: Float, val centerX: Float, val centerY: Float, val follow: Boolean)

/** Pure zoom / pan / follow maths for the map view (screen = image * scale + translation). */
object ZoomMath {
    const val MIN_ZOOM = 1f // whole map fits
    const val MAX_ZOOM = 8f
    const val STEP = 1.5f // zoom buttons

    fun clampZoom(z: Float): Float = if (z.isNaN()) MIN_ZOOM else z.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** Scale at which the whole image fits inside the view. */
    fun fitScale(imgW: Float, imgH: Float, viewW: Float, viewH: Float): Float =
        if (imgW <= 0f || imgH <= 0f || viewW <= 0f || viewH <= 0f) 1f else minOf(viewW / imgW, viewH / imgH)

    /** Double-tap: zoom in x2; at (nearly) max zoom go back to the whole map. */
    fun doubleTapTarget(zoom: Float): Float = if (zoom >= MAX_ZOOM * 0.98f) MIN_ZOOM else clampZoom(zoom * 2f)

    /** New translation on one axis so the screen point [focus] keeps showing the same image point. */
    fun zoomAround(t: Float, oldScale: Float, newScale: Float, focus: Float): Float =
        focus - (focus - t) * newScale / oldScale

    /** Translation on one axis that puts image coordinate [imgPoint] at the middle of the view. */
    fun translationToCenter(imgPoint: Float, scale: Float, viewSize: Float): Float = viewSize / 2f - imgPoint * scale

    /** Image coordinate currently at the middle of the view. */
    fun centerOf(t: Float, scale: Float, viewSize: Float): Float = (viewSize / 2f - t) / scale

    /**
     * Keeps the image on screen. Normal: image smaller than the view is centred, larger one cannot leave a gap.
     * [loose] (follow mode): any image point may reach the view centre.
     */
    fun clampPan(t: Float, scale: Float, imgSize: Float, viewSize: Float, loose: Boolean): Float {
        val size = imgSize * scale
        return if (loose) t.coerceIn(viewSize / 2f - size, viewSize / 2f)
        else if (size <= viewSize) (viewSize - size) / 2f
        else t.coerceIn(viewSize - size, 0f)
    }
}

/** Human-friendly text helpers. */
object Pretty {
    /** "bixby-bridge" -> "Bixby Bridge". */
    fun destination(id: String): String =
        id.split(Regex("[-_\\s]+")).filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    /** Age of the save in plain words: "Saved just now", "Saved 4 min ago", "Saved 2 h ago", "Saved 3 d ago". */
    fun savedAgo(ageMs: Long?): String {
        if (ageMs == null) return "Saved (time unknown)"
        val min = maxOf(0L, ageMs) / 60_000
        return when {
            min < 1 -> "Saved just now"
            min < 60 -> "Saved $min min ago"
            min < 24 * 60 -> "Saved ${min / 60} h ago"
            else -> "Saved ${min / (24 * 60)} d ago"
        }
    }

    /** Chip text for a live position that went quiet: "Paused · last seen 45 s ago". */
    fun pausedAgo(ageMs: Long?): String {
        if (ageMs == null) return "Paused · last seen earlier"
        val sec = maxOf(0L, ageMs) / 1000
        val ago = when {
            sec < 1 -> "just now"
            sec < 60 -> "$sec s ago"
            sec < 3600 -> "${sec / 60} min ago"
            sec < 24 * 3600 -> "${sec / 3600} h ago"
            else -> "${sec / (24 * 3600)} d ago"
        }
        return "Paused · last seen $ago"
    }
}
