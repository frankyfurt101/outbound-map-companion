package dev.local.outboundcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewOutlineProvider

/** All colours and sizes of the UI live here. One dark palette, one accent (cyan). */
object Theme {
    val BG = Color.rgb(11, 15, 20)
    val SURFACE = Color.rgb(21, 27, 34)
    val SURFACE_HI = Color.rgb(33, 42, 52)
    val BAR = Color.argb(214, 11, 15, 20)
    val BAR_SOLID = Color.rgb(11, 15, 20)
    val OVERLAY = Color.argb(224, 17, 23, 30)
    val STROKE = Color.argb(51, 255, 255, 255)
    val DIVIDER = Color.argb(40, 255, 255, 255)
    val ACCENT = Color.rgb(0, 212, 240)
    val ON_ACCENT = Color.rgb(6, 24, 30)
    val TEXT = Color.WHITE
    val TEXT_DIM = Color.rgb(154, 167, 180)
    val GOOD = Color.rgb(61, 220, 132)
    val WARN = Color.rgb(255, 190, 80)
    val BAD = Color.rgb(255, 120, 100)
    /** Destination pin and edge arrow only; no other marker uses this yellow. */
    val DEST = Color.rgb(255, 226, 48)
    val GREY_DOT = Color.rgb(120, 130, 140)

    // dp
    const val BAR_H = 56
    const val TOUCH = 48
    const val FAB = 60
    const val FAB_GAP = 20
    const val MARGIN = 16
    const val RADIUS = 14
    const val ROW_H = 52

    // sp
    const val TEXT_BODY = 17f
    const val TEXT_SMALL = 14f
    const val TEXT_TITLE = 20f
}

fun roundRect(color: Int, radiusDp: Float, density: Float, stroke: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * density
        if (stroke != 0) setStroke((1.5f * density).toInt().coerceAtLeast(1), stroke)
    }

/** Rounded background that lightens while pressed. */
fun pressable(color: Int, pressed: Int, radiusDp: Float, density: Float): StateListDrawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), roundRect(pressed, radiusDp, density))
        addState(intArrayOf(), roundRect(color, radiusDp, density))
    }

/** Round icon button drawn with canvas (no font glyphs, no drawables). */
class IconButton(context: Context, private val icon: Icon, private val sizeDp: Int, private val baseColor: Int, elevated: Boolean) :
    View(context) {

    enum class Icon { PLUS, MINUS, LOCATE, MORE }

    var active = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 3f * d }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
        if (elevated) elevation = 6f * d
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = (sizeDp * d).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        val cx = s / 2f
        fill.color = if (active) Theme.ACCENT else if (isPressed) Theme.SURFACE_HI else baseColor
        c.drawCircle(cx, cx, cx, fill)
        if (!active) {
            line.color = Theme.STROKE; line.strokeWidth = 1.5f * d
            c.drawCircle(cx, cx, cx - 1f * d, line)
        }
        val ink = if (active) Theme.ON_ACCENT else Theme.TEXT
        line.color = ink; line.strokeWidth = 3f * d
        solid.color = ink
        when (icon) {
            Icon.PLUS -> { c.drawLine(cx - s * .2f, cx, cx + s * .2f, cx, line); c.drawLine(cx, cx - s * .2f, cx, cx + s * .2f, line) }
            Icon.MINUS -> c.drawLine(cx - s * .2f, cx, cx + s * .2f, cx, line)
            Icon.LOCATE -> {
                line.strokeWidth = 2.5f * d
                c.drawCircle(cx, cx, s * .17f, line)
                c.drawCircle(cx, cx, s * .06f, solid)
                for ((dx, dy) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
                    c.drawLine(cx + dx * s * .25f, cx + dy * s * .25f, cx + dx * s * .35f, cx + dy * s * .35f, line)
                }
            }
            Icon.MORE -> for (i in -1..1) c.drawCircle(cx + i * s * .2f, cx, s * .06f, solid)
        }
    }
}
