package dev.local.outboundcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import outbound.core.DestinationMath
import outbound.core.HitTest
import outbound.core.MapViewState
import outbound.core.ZoomMath
import kotlin.math.hypot

/**
 * Region bitmap with pinch-zoom, drag, double-tap zoom, zoom buttons and follow mode, plus the player and van markers.
 * State: zoom (1 = whole map fits, max 8), translation (tx, ty); screen = image * scale + translation, scale = fit * zoom.
 */
class MapView(context: Context) : View(context) {

    /** Called when a gesture / button action ended and the view state should be saved. */
    var onViewSettled: (() -> Unit)? = null
    var onFollowChanged: ((Boolean) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var bitmapScale = 1f // image pixels (2048 space) per bitmap pixel
    private var imgW = 0f
    private var imgH = 0f
    private var fit = 1f
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private var laidOut = false
    private var pending: MapViewState? = null

    var follow = false
        private set

    private var player: PointF? = null
    private var van: PointF? = null
    private var playerStyle = PlayerStyle.SAVE

    enum class PlayerStyle { LIVE, STALE, SAVE }

    /** A named point in 2048x2048 image pixel space. */
    class Pin(val id: String, val at: PointF, val name: String)

    private var dest: Pin? = null
    private var icons: List<MapIcon> = emptyList()
    private var iconXs = FloatArray(0)
    private var iconYs = FloatArray(0)
    private val painter = MapIconPainter(resources.displayMetrics.density)

    /** Called on a single tap: the icon within reach of the tap (nearest wins), or null for empty map. */
    var onIconTapped: ((MapIcon?) -> Unit)? = null

    /** Display settings (applied immediately). */
    var markerScale = 1f
        set(v) { field = v; invalidate() }
    var showLabels = true
        set(v) { field = v; invalidate() }
    var showVan = true
        set(v) { field = v; invalidate() }

    /** Screen margins (px) the destination edge arrow keeps clear: top bar, button column. */
    var arrowInsetLeft = 0f
    var arrowInsetTop = 0f
    var arrowInsetRight = 0f
    var arrowInsetBottom = 0f

    private val dp = resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 3f * dp }
    private val liveRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * dp }
    private val destPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.DEST }
    private val destOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 2.5f * dp; strokeJoin = Paint.Join.ROUND }
    private val shape = Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f * dp; color = Color.WHITE; setShadowLayer(4f * dp, 0f, 0f, Color.BLACK) }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var activeId = -1

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (!laidOut) return true
            zoomTo(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { onViewSettled?.invoke() }
    }).apply { isQuickScaleEnabled = false }

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!dragging && laidOut) onIconTapped?.invoke(iconAt(e.x, e.y))
            return true
        }
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && !dragging && laidOut) {
                zoomTo(ZoomMath.doubleTapTarget(zoom), e.x, e.y)
                onViewSettled?.invoke()
            }
            return true
        }
    })

    init {
        setBackgroundColor(Theme.BG)
    }

    // ---------------------------------------------------------------- state

    /** Sets the region image; [state] (zoom, centre, follow) is restored once the view has a size. */
    fun setImage(bmp: Bitmap?, imageScale: Float, state: MapViewState? = null) {
        bitmap = bmp
        bitmapScale = imageScale
        imgW = (bmp?.width ?: 0) * imageScale
        imgH = (bmp?.height ?: 0) * imageScale
        pending = state
        follow = state?.follow ?: false
        laidOut = false
        layoutImage()
        onFollowChanged?.invoke(follow)
        invalidate()
    }

    fun viewState(): MapViewState? {
        if (!laidOut) return null
        val s = scale()
        return MapViewState(zoom, ZoomMath.centerOf(tx, s, width.toFloat()), ZoomMath.centerOf(ty, s, height.toFloat()), follow)
    }

    /** Marker positions in 2048x2048 image pixel space (null = not shown). */
    fun setMarkers(player: PointF?, van: PointF?, style: PlayerStyle = PlayerStyle.SAVE) {
        this.player = player
        this.playerStyle = style
        this.van = van
        if (follow) applyFollow()
        invalidate()
    }

    /** Destination pin in image pixel space; null = nothing drawn. */
    fun setDestination(pin: Pin?) {
        dest = pin
        invalidate()
    }

    /** Map icons (POIs, campfires, towers, caches) in draw order; built once per region / save change by the caller. */
    fun setIcons(list: List<MapIcon>) {
        icons = list
        if (iconXs.size < list.size) { iconXs = FloatArray(list.size); iconYs = FloatArray(list.size) }
        invalidate()
    }

    /**
     * Centres the map on the destination (follow off) and zooms out only as far as needed to keep the player visible.
     * Returns false when there is no destination drawn.
     */
    fun showDestination(): Boolean {
        val d = dest ?: return false
        if (!laidOut) return false
        if (follow) setFollow(false)
        // Frame the player and the destination inside the safe rectangle (clear of the top bar, chip and button column).
        val left = arrowInsetLeft
        val top = arrowInsetTop
        val safeW = width - arrowInsetRight - left
        val safeH = height - arrowInsetBottom - top
        val p = player
        var cx = d.at.x
        var cy = d.at.y
        if (p != null) {
            zoom = DestinationMath.zoomToFitPair(
                d.at.x, d.at.y, p.x, p.y, fit, safeW, safeH, 96f * dp, zoom,
            )
            cx = (d.at.x + p.x) / 2f
            cy = (d.at.y + p.y) / 2f
        }
        val s = scale()
        tx = ZoomMath.translationToCenter(cx, s, width.toFloat()) + (left + safeW / 2f - width / 2f)
        ty = ZoomMath.translationToCenter(cy, s, height.toFloat()) + (top + safeH / 2f - height / 2f)
        clampPan()
        invalidate()
        onViewSettled?.invoke()
        return true
    }

    /** Centres on a point in image pixel space (follow off, zoom unchanged). False when the view is not laid out. */
    fun centerOn(p: PointF): Boolean {
        if (!laidOut) return false
        if (follow) setFollow(false)
        val s = scale()
        tx = ZoomMath.translationToCenter(p.x, s, width.toFloat())
        ty = ZoomMath.translationToCenter(p.y, s, height.toFloat())
        clampPan()
        invalidate()
        onViewSettled?.invoke()
        return true
    }

    fun setFollow(on: Boolean) {
        if (follow == on) return
        follow = on
        if (on) applyFollow() else clampPan()
        onFollowChanged?.invoke(on)
        invalidate()
        onViewSettled?.invoke()
    }

    fun zoomIn() = zoomButton(ZoomMath.STEP)
    fun zoomOut() = zoomButton(1f / ZoomMath.STEP)

    private fun zoomButton(factor: Float) {
        if (!laidOut) return
        // Around the screen centre; with follow on the player sits at the centre, so that is "around the player".
        zoomTo(zoom * factor, width / 2f, height / 2f)
        onViewSettled?.invoke()
    }

    private fun scale() = fit * zoom

    private fun layoutImage() {
        if (bitmap == null || width == 0 || height == 0) return
        fit = ZoomMath.fitScale(imgW, imgH, width.toFloat(), height.toFloat())
        val st = pending
        zoom = ZoomMath.clampZoom(st?.zoom ?: 1f)
        val s = scale()
        tx = ZoomMath.translationToCenter(st?.centerX ?: imgW / 2f, s, width.toFloat())
        ty = ZoomMath.translationToCenter(st?.centerY ?: imgH / 2f, s, height.toFloat())
        pending = null
        laidOut = true
        if (follow) applyFollow() else clampPan()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (laidOut) pending = viewState()
        laidOut = false
        layoutImage()
    }

    private fun clampPan() {
        val s = scale()
        tx = ZoomMath.clampPan(tx, s, imgW, width.toFloat(), follow)
        ty = ZoomMath.clampPan(ty, s, imgH, height.toFloat(), follow)
    }

    private fun applyFollow() {
        val p = player
        if (p == null || !laidOut) { clampPan(); return }
        val s = scale()
        tx = ZoomMath.translationToCenter(p.x, s, width.toFloat())
        ty = ZoomMath.translationToCenter(p.y, s, height.toFloat())
        clampPan()
    }

    private fun zoomTo(newZoom: Float, fx: Float, fy: Float) {
        val old = scale()
        zoom = ZoomMath.clampZoom(newZoom)
        val s = scale()
        tx = ZoomMath.zoomAround(tx, old, s, if (follow) width / 2f else fx)
        ty = ZoomMath.zoomAround(ty, old, s, if (follow) height / 2f else fy)
        if (follow) applyFollow() else clampPan()
        invalidate()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (!laidOut) return
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale() * bitmapScale, scale() * bitmapScale)
        canvas.drawBitmap(b, 0f, 0f, bmpPaint)
        canvas.restore()
        drawIcons(canvas)
        val v = if (showVan) van?.let { screen(it) } else null
        val p = player?.let { screen(it) }
        if (v != null && p != null && hypot(v[0] - p[0], v[1] - p[1]) < 24f * dp * markerScale) {
            drawMarker(canvas, p, playerColor(), "You + Van", false, playerStyle)
        } else {
            // Each label sits right beside its own marker: Van on the right, You on the left, so they never collide.
            v?.let { drawMarker(canvas, it, Color.rgb(255, 170, 0), "Van", false) }
            p?.let { drawMarker(canvas, it, playerColor(), "You", true, playerStyle) }
        }
        drawDestination(canvas)
    }

    // Map icons: constant screen size, culled off-screen, no allocations. POI names only from 2x zoom (and when labels are on).
    private fun drawIcons(canvas: Canvas) {
        if (icons.isEmpty()) return
        val skip = dest
        val s = scale()
        val w = width.toFloat()
        val h = height.toFloat()
        val names = showLabels && zoom >= 2f
        for (i in icons.indices) {
            val ic = icons[i]
            if (skip != null && ic.kind == IconKind.POI && ic.id == skip.id) continue
            val r = (if (ic.kind == IconKind.POI) POI_HALF_DP else FEATURE_HALF_DP) * dp * markerScale
            val x = ic.at.x * s + tx
            val y = ic.at.y * s + ty
            if (x < -r || y < -r || x > w + r || y > h + r) continue
            painter.draw(canvas, ic.kind, ic.state, x, y, r)
            if (names && ic.kind == IconKind.POI) {
                textPaint.textSize = 14f * dp
                canvas.drawText(ic.title, x + r + 4f * dp, y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
                textPaint.textSize = 18f * dp
            }
        }
    }

    /** Nearest icon within 24 dp of a screen point (visible icons only; topmost kind wins ties), or null. */
    private fun iconAt(sx: Float, sy: Float): MapIcon? {
        val s = scale()
        val skip = dest
        for (i in icons.indices) {
            val ic = icons[i]
            val hidden = skip != null && ic.kind == IconKind.POI && ic.id == skip.id
            iconXs[i] = if (hidden) Float.NaN else ic.at.x * s + tx
            iconYs[i] = ic.at.y * s + ty
        }
        val i = HitTest.nearest(iconXs, iconYs, icons.size, sx, sy, TAP_RADIUS_DP * dp)
        return if (i >= 0) icons[i] else null
    }

    // Destination: gold diamond (never a circle), constant screen size; off-screen -> arrow on the edge of the safe rectangle.
    private fun drawDestination(canvas: Canvas) {
        val d = dest ?: return
        val pt = screen(d.at)
        val left = arrowInsetLeft
        val top = arrowInsetTop
        val right = width - arrowInsetRight
        val bottom = height - arrowInsetBottom
        val arrow = DestinationMath.edgeArrow(pt[0], pt[1], left, top, right, bottom)
        // Arrow only when the pin is really outside the view; a pin that is merely near a control is still drawn.
        val offScreen = pt[0] < 0f || pt[1] < top || pt[0] > width || pt[1] > height
        if (arrow != null && offScreen) {
            val a = 14f * dp * markerScale
            canvas.save()
            canvas.translate(arrow.x, arrow.y)
            canvas.rotate(Math.toDegrees(arrow.angle.toDouble()).toFloat())
            shape.reset()
            shape.moveTo(a * 0.6f, 0f); shape.lineTo(-a * 0.7f, -a * 0.75f); shape.lineTo(-a * 0.3f, 0f); shape.lineTo(-a * 0.7f, a * 0.75f); shape.close()
            canvas.drawPath(shape, destPaint)
            canvas.drawPath(shape, destOutline)
            canvas.restore()
            return
        }
        val r = 13f * dp * markerScale
        shape.reset()
        shape.moveTo(pt[0], pt[1] - r * 1.25f); shape.lineTo(pt[0] + r, pt[1]); shape.lineTo(pt[0], pt[1] + r * 1.25f); shape.lineTo(pt[0] - r, pt[1]); shape.close()
        canvas.drawPath(shape, destPaint)
        canvas.drawPath(shape, destOutline)
        if (!showLabels) return
        val label = d.name
        // Label goes to the right of the pin unless it would run under the controls / off the safe area; then to the left.
        val w = textPaint.measureText(label)
        val xr = pt[0] + r + 5f * dp
        val xl = pt[0] - r - 5f * dp - w
        val x = when {
            xr + w <= right -> xr
            xl >= arrowInsetLeft -> xl
            // Neither side fits the safe area: use the side with more room, never left of the safe area.
            else -> maxOf(arrowInsetLeft, if (pt[0] - r - 5f * dp - arrowInsetLeft > right - xr) xl else xr)
        }
        canvas.drawText(label, x, pt[1] - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun screen(p: PointF): FloatArray {
        val s = scale()
        return floatArrayOf(p.x * s + tx, p.y * s + ty)
    }

    // Live = bright cyan + outer ring; paused (stale live) = dimmed cyan, no ring; from save = pink.
    private fun playerColor() = when (playerStyle) {
        PlayerStyle.LIVE -> Color.rgb(0, 220, 255)
        PlayerStyle.STALE -> Color.argb(130, 0, 220, 255)
        PlayerStyle.SAVE -> Color.rgb(255, 40, 120)
    }

    // Markers are drawn in screen space, so they keep the same size at every zoom level.
    private fun drawMarker(canvas: Canvas, pts: FloatArray, color: Int, label: String, labelLeft: Boolean, style: PlayerStyle = PlayerStyle.SAVE) {
        markerPaint.color = color
        val r = 11f * dp * markerScale
        val live = style == PlayerStyle.LIVE
        if (live) {
            liveRingPaint.color = color
            canvas.drawCircle(pts[0], pts[1], r * 1.9f, liveRingPaint)
        }
        canvas.drawCircle(pts[0], pts[1], r, markerPaint)
        ringPaint.alpha = if (style == PlayerStyle.STALE) 110 else 255
        canvas.drawCircle(pts[0], pts[1], r, ringPaint)
        if (!showLabels) return
        val gap = (if (live) r * 1.9f else r) + 5f * dp
        val x = if (labelLeft) pts[0] - gap - textPaint.measureText(label) else pts[0] + gap
        val y = pts[1] - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, x, y, textPaint)
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activeId = e.getPointerId(0); lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress) {
                val i = e.findPointerIndex(activeId)
                if (i >= 0 && laidOut) {
                    if (!dragging && hypot(e.getX(i) - downX, e.getY(i) - downY) > slop) dragging = true
                    if (dragging) {
                        if (follow) setFollowQuiet(false) // also after a pinch (POINTER_DOWN already set dragging)
                        tx += e.getX(i) - lastX; ty += e.getY(i) - lastY
                        clampPan()
                        invalidate()
                    }
                    lastX = e.getX(i); lastY = e.getY(i)
                }
            } else {
                val i = e.findPointerIndex(activeId)
                if (i >= 0) { lastX = e.getX(i); lastY = e.getY(i) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = true // a second finger is a pinch, never a double tap
            MotionEvent.ACTION_POINTER_UP -> {
                val up = e.actionIndex
                if (e.getPointerId(up) == activeId) {
                    val n = if (up == 0) 1 else 0
                    activeId = e.getPointerId(n); lastX = e.getX(n); lastY = e.getY(n)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onViewSettled?.invoke()
                activeId = -1
            }
        }
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)
        return true
    }

    /** Dragging turns follow off (state saved when the gesture ends). */
    private fun setFollowQuiet(on: Boolean) {
        follow = on
        onFollowChanged?.invoke(on)
    }

    private companion object {
        const val FEATURE_HALF_DP = 8f // 16 dp icon at Medium
        const val POI_HALF_DP = 10.5f // 21 dp badge at Medium
        const val TAP_RADIUS_DP = 24f
    }
}
