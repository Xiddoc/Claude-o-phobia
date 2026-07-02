package com.xiddoc.claudeophobia.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.xiddoc.claudeophobia.data.GraphMath
import com.xiddoc.claudeophobia.data.Vec2
import com.xiddoc.claudeophobia.data.WeekBucket

/**
 * Rasterizes one week's progress curve (and an optional derivative line) into a
 * transparent [Bitmap] for the RemoteViews graph widget, which can't host a
 * `Canvas`/Compose. Uses the shared [GraphMath] geometry so it can never disagree
 * with the in-app graph. All colors are hard-coded ARGB (no theme in RemoteViews);
 * the bitmap is transparent so the card's `widget_bg` shows through.
 *
 * The "gained per day" derivative rides on the same axis as a distinct dashed
 * green line (rather than the in-app dedicated bar chart, which doesn't fit a
 * small widget), centered so gains rise above the middle and drops fall below.
 *
 * Battery: the optional [glow] blur is the heaviest pass and is off by default for
 * this widget. The whole render is one bounded, one-shot allocation.
 */
object HistoryGraphRenderer {

    private const val CLAY = 0xFFD97757.toInt()
    private const val CLAY_BRIGHT = 0xFFE89A80.toInt()
    private const val GLOW = 0xFFFFC8A6.toInt()
    private const val TRACK = 0x4D35302B
    private const val DOT = 0xFFE89A80.toInt()
    private const val DERIV_LINE = 0xCC7FB069.toInt() // distinct dashed "gained / day" series
    private const val GRID_LINE = 0x1FA39B8D // faint percentage gridlines (25/50/75/100%)

    /** Percentage gridlines drawn behind the curve, as unit-square y values. */
    private val GRID_LEVELS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f)

    fun render(
        widthPx: Int,
        heightPx: Int,
        week: WeekBucket?,
        tension: Float,
        showDerivative: Boolean,
        glow: Boolean,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val padX = w * 0.04f
        val padY = h * 0.10f
        val left = padX
        val top = padY
        val contentW = (w - 2 * padX).coerceAtLeast(1f)
        val contentH = (h - 2 * padY).coerceAtLeast(1f)
        fun px(x: Float) = left + x.coerceIn(0f, 1f) * contentW
        fun py(y: Float) = top + (1f - y.coerceIn(0f, 1f)) * contentH

        // Baseline track along the 0% line.
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (h * 0.006f).coerceAtLeast(1f)
            color = TRACK
        }
        canvas.drawLine(left, py(0f), left + contentW, py(0f), trackPaint)

        // Faint percentage gridlines (25/50/75/100%) for easier reading.
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = GRID_LINE
        }
        for (level in GRID_LEVELS) {
            val y = py(level)
            canvas.drawLine(left, y, left + contentW, y, gridPaint)
        }

        val samples = week?.samples.orEmpty()
        val start = week?.weekStartMs ?: 0L
        val end = week?.weekEndMs ?: 0L
        val points = GraphMath.toPoints(samples, start, end)

        // Derivative as a distinct dashed line on the same axis (a different series
        // from the solid progress curve). Centered on the mid so gains rise above it
        // and the rare drop dips below, auto-scaled to the week's biggest move.
        if (showDerivative && week != null) {
            val deriv = GraphMath.dailyDerivative(samples, start, end)
            if (deriv.isNotEmpty()) {
                val scale = GraphMath.derivativeScale(deriv)
                val dpts = deriv.map {
                    Vec2(it.xMid, (0.5f + (it.deltaPerDay / scale) * 0.4f).coerceIn(0f, 1f))
                }
                val derivPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (h * 0.012f).coerceAtLeast(1.5f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = DERIV_LINE
                    pathEffect = DashPathEffect(floatArrayOf(h * 0.03f, h * 0.02f), 0f)
                }
                val dsegs = GraphMath.smoothPath(dpts, tension)
                if (dsegs.isNotEmpty()) {
                    val dpath = Path().apply {
                        moveTo(px(dsegs.first().p0.x), py(dsegs.first().p0.y))
                        for (s in dsegs) cubicTo(px(s.c1.x), py(s.c1.y), px(s.c2.x), py(s.c2.y), px(s.p3.x), py(s.p3.y))
                    }
                    canvas.drawPath(dpath, derivPaint)
                } else {
                    // A single-day week has no line to draw; mark its level with a dot.
                    val p = dpts.first()
                    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = DERIV_LINE
                    }
                    canvas.drawCircle(px(p.x), py(p.y), (h * 0.02f).coerceIn(2f, 8f), dotPaint)
                }
            }
        }

        // The progress curve — noise-smoothed (moving average) before the Bezier pass.
        val segs = GraphMath.smoothPath(GraphMath.smoothedPoints(points, tension), tension)
        if (segs.isNotEmpty()) {
            val path = Path().apply {
                moveTo(px(segs.first().p0.x), py(segs.first().p0.y))
                for (s in segs) cubicTo(px(s.c1.x), py(s.c1.y), px(s.c2.x), py(s.c2.y), px(s.p3.x), py(s.p3.y))
            }
            val lineStroke = (h * 0.02f).coerceAtLeast(2f)
            if (glow) drawGlow(canvas, path, lineStroke)
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = lineStroke
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                shader = LinearGradient(left, 0f, left + contentW, 0f, CLAY, CLAY_BRIGHT, Shader.TileMode.CLAMP)
            }
            canvas.drawPath(path, linePaint)
        }

        // Sample dots, thinned so a fine sampling cadence stays legible (still shows
        // something for a single-sample week).
        if (points.isNotEmpty()) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = DOT
            }
            val dotR = (h * 0.022f).coerceIn(2f, 10f)
            for (p in GraphMath.thinForDots(points)) canvas.drawCircle(px(p.x), py(p.y), dotR, dotPaint)
        }

        return bmp
    }

    /** Warm blurred bloom under the curve — the ProgressRing/LinearMeter recipe. */
    private fun drawGlow(canvas: Canvas, path: Path, stroke: Float) {
        // A degenerate curve — flat (e.g. a whole week at 0%) or a near-vertical spike
        // (samples bunched at one instant, common at the very start of a week) — has a
        // bounding box near-zero in one dimension; blurring it smears a broken band /
        // blob and can fault the mask allocation. Skip the bloom below a small span.
        val bounds = android.graphics.RectF()
        @Suppress("DEPRECATION") // single-arg overload isn't in the compileSdk 35 stubs
        path.computeBounds(bounds, true)
        if (bounds.height() < stroke || bounds.width() < stroke) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = stroke
        }
        val layers = listOf(
            stroke * 3.0f to (CLAY and 0x00FFFFFF or (0x73 shl 24)),
            stroke * 1.7f to (CLAY_BRIGHT and 0x00FFFFFF or (0x8C shl 24)),
            stroke * 0.8f to (GLOW and 0x00FFFFFF or (0xBF shl 24)),
        )
        for ((radius, color) in layers) {
            if (radius <= 0f) continue
            paint.color = color
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(path, paint)
        }
        paint.maskFilter = null
    }
}
