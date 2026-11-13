package dev.local.outboundcompanion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF

/** Icon kinds, in draw order (later kinds are drawn on top). */
object IconKind { const val CACHE = 0; const val CAMPFIRE = 1; const val TOWER = 2; const val POI = 3 }

/** How an icon is drawn: NORMAL standard colour, DIM faded, FULL brighter colour, USED tower with a green tick. */
object IconState { const val NORMAL = 0; const val DIM = 1; const val FULL = 2; const val USED = 3 }

/** One tappable map icon in 2048x2048 image pixel space. [wx]/[wz] are world coordinates (for the distance); [detail] is extra chip text. */
class MapIcon(
    val id: String, val kind: Int, val at: PointF, val title: String, val detail: String,
    val wx: Double, val wz: Double, val state: Int,
)

/**
 * Draws the glyphs. All shapes live in a unit box (-1..1, y down) built once; [draw] scales the canvas, so nothing is
 * allocated while drawing. Stroke widths are given in screen px and divided by the scale.
 */
class MapIconPainter(private val dp: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }

    private val flame = Path().apply {
        moveTo(0.05f, -1f)
        cubicTo(0.25f, -0.55f, 0.85f, -0.2f, 0.8f, 0.4f)
        cubicTo(0.75f, 0.85f, 0.4f, 1f, 0f, 1f)
        cubicTo(-0.45f, 1f, -0.8f, 0.8f, -0.8f, 0.3f)
        cubicTo(-0.8f, -0.1f, -0.5f, -0.2f, -0.4f, -0.55f)
        cubicTo(-0.2f, -0.4f, -0.1f, -0.3f, -0.05f, -0.15f)
        cubicTo(-0.1f, -0.5f, 0f, -0.8f, 0.05f, -1f)
        close()
    }
    private val flameCore = Path().apply {
        moveTo(0.05f, -0.1f)
        cubicTo(0.3f, 0.2f, 0.45f, 0.45f, 0.3f, 0.7f)
        cubicTo(0.2f, 0.88f, -0.2f, 0.88f, -0.3f, 0.65f)
        cubicTo(-0.4f, 0.4f, -0.1f, 0.3f, 0.05f, -0.1f)
        close()
    }
    private val lattice = Path().apply {
        moveTo(-0.42f, 0.72f); lineTo(-0.12f, -0.62f)
        moveTo(0.42f, 0.72f); lineTo(0.12f, -0.62f)
        moveTo(-0.34f, 0.34f); lineTo(0.34f, 0.34f)
        moveTo(-0.24f, -0.06f); lineTo(0.24f, -0.06f)
        moveTo(-0.34f, 0.34f); lineTo(0.24f, -0.06f)
        moveTo(0.34f, 0.34f); lineTo(-0.24f, -0.06f)
        moveTo(-0.24f, -0.06f); lineTo(0.12f, -0.62f)
        moveTo(0.24f, -0.06f); lineTo(-0.12f, -0.62f)
        moveTo(-0.5f, 0.72f); lineTo(0.5f, 0.72f)
    }
    private val tick = Path().apply { moveTo(-0.45f, 0.05f); lineTo(-0.1f, 0.4f); lineTo(0.5f, -0.35f) }
    private val crateRect = RectF(-0.85f, -0.62f, 0.85f, 0.72f)
    private val crateLines = Path().apply {
        moveTo(-0.85f, 0.05f); lineTo(0.85f, 0.05f)
        moveTo(-0.85f, -0.62f); lineTo(0.85f, 0.72f)
        moveTo(0.85f, -0.62f); lineTo(-0.85f, 0.72f)
    }
    private val hex = Path().apply {
        for (i in 0 until 6) {
            val a = Math.toRadians(30.0 + 60.0 * i)
            val x = Math.cos(a).toFloat(); val y = Math.sin(a).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    private val hexInner = Path().apply {
        for (i in 0 until 6) {
            val a = Math.toRadians(30.0 + 60.0 * i)
            val x = Math.cos(a).toFloat() * 0.38f; val y = Math.sin(a).toFloat() * 0.38f
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    /** Draws one icon centred at ([x], [y]) with half-size [r] (screen px). */
    fun draw(canvas: Canvas, kind: Int, state: Int, x: Float, y: Float, r: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(r, r)
        val px = 1f / r // one screen pixel in unit space
        val dim = state == IconState.DIM
        when (kind) {
            IconKind.CAMPFIRE -> {
                line.color = Color.argb(if (dim) 70 else 120, 0, 0, 0) // soft dark halo so the flame reads on any terrain
                line.strokeWidth = 3f * dp * px
                canvas.drawPath(flame, line)
                fill.style = Paint.Style.FILL
                fill.color = if (dim) Color.argb(90, 255, 140, 30) else Color.rgb(255, 128, 20)
                canvas.drawPath(flame, fill)
                if (!dim) { fill.color = Color.rgb(255, 214, 90); canvas.drawPath(flameCore, fill) }
                line.color = if (dim) Color.argb(200, 255, 160, 60) else Color.argb(230, 40, 16, 0)
                line.strokeWidth = (if (dim) 1.2f else 1f) * dp * px
                canvas.drawPath(flame, line)
            }
            IconKind.TOWER -> {
                fill.style = Paint.Style.FILL
                fill.color = Color.argb(225, 22, 28, 36)
                canvas.drawCircle(0f, 0f, 1f, fill)
                line.color = Color.argb(200, 255, 255, 255)
                line.strokeWidth = 1f * dp * px
                canvas.drawCircle(0f, 0f, 0.96f, line)
                line.color = Color.WHITE
                line.strokeWidth = 1.5f * dp * px
                canvas.drawPath(lattice, line)
                if (state == IconState.USED) {
                    fill.color = Color.rgb(46, 190, 100)
                    canvas.drawCircle(0.72f, -0.72f, 0.46f, fill)
                    line.color = Color.argb(230, 10, 30, 16)
                    line.strokeWidth = 1f * dp * px
                    canvas.drawCircle(0.72f, -0.72f, 0.46f, line)
                    canvas.save()
                    canvas.translate(0.72f, -0.72f)
                    canvas.scale(0.34f, 0.34f)
                    line.color = Color.WHITE
                    line.strokeWidth = 1.6f * dp * px / 0.34f
                    canvas.drawPath(tick, line)
                    canvas.restore()
                }
            }
            IconKind.CACHE -> {
                val a = if (dim) 90 else if (state == IconState.FULL) 255 else 215
                fill.style = Paint.Style.FILL
                fill.color = if (state == IconState.FULL) Color.argb(a, 232, 188, 110) else Color.argb(a, 200, 160, 105)
                canvas.drawRect(crateRect, fill)
                line.color = Color.argb(if (dim) 110 else 200, 90, 58, 24)
                line.strokeWidth = 1f * dp * px
                canvas.drawPath(crateLines, line)
                line.color = Color.argb(if (dim) 150 else 240, 30, 18, 6)
                line.strokeWidth = 1.2f * dp * px
                canvas.drawRect(crateRect, line)
            }
            IconKind.POI -> {
                fill.style = Paint.Style.FILL
                fill.color = Color.argb(235, 232, 238, 245)
                canvas.drawPath(hex, fill)
                fill.color = Color.argb(230, 40, 52, 66)
                canvas.drawPath(hexInner, fill)
                line.color = Color.argb(235, 10, 14, 20)
                line.strokeWidth = 1.5f * dp * px
                canvas.drawPath(hex, line)
            }
        }
        canvas.restore()
    }
}
