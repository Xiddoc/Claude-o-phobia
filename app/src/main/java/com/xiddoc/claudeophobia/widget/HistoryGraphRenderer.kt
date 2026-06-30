package com.xiddoc.claudeophobia.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.xiddoc.claudeophobia.data.GraphMath
import com.xiddoc.claudeophobia.data.WeekBucket

/**
 * Rasterizes one week's progress curve (and optional derivative overlay) into a
 * transparent [Bitmap] for the RemoteViews graph widget, which can't host a
 * `Canvas`/Compose. Uses the shared [GraphMath] geometry so it can never disagree
 * with the in-app graph. All colors are hard-coded ARGB (no theme in RemoteViews);
 * the bitmap is transparent so the card's `widget_bg` shows through.
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
    private const val DERIV_POS = 0x66D97757
    private const val DERIV_NEG = 0x80E5806B.toInt() // alpha 0x80 pushes past Int range
    private const val ZERO_LINE = 0x33A39B8D

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

        val samples = week?.samples.orEmpty()
        val start = week?.weekStartMs ?: 0L
        val end = week?.weekEndMs ?: 0L
        val points = GraphMath.toPoints(samples, start, end)

        // Derivative overlay (drawn behind the line), in a lower band.
        if (showDerivative && week != null) {
            val deriv = GraphMath.dailyDerivative(samples, start, end)
            if (deriv.isNotEmpty()) {
                val scale = GraphMath.derivativeScale(deriv)
                val zeroY = top + contentH * 0.80f
                val bandHalf = contentH * 0.18f
                val barW = (contentW / (deriv.size * 2f)).coerceIn(2f, contentW * 0.08f)
                // Faint zero reference line for the derivative band.
                val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                    color = ZERO_LINE
                }
                canvas.drawLine(left, zeroY, left + contentW, zeroY, zeroPaint)
                for (d in deriv) {
                    val x = px(d.xMid)
                    val frac = (d.deltaPerDay / scale).coerceIn(-1f, 1f)
                    val barH = frac * bandHalf
                    val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = if (d.deltaPerDay < 0f) DERIV_NEG else DERIV_POS
                    }
                    val tBar = if (barH >= 0f) zeroY - barH else zeroY
                    val bBar = if (barH >= 0f) zeroY else zeroY - barH
                    canvas.drawRect(x - barW / 2f, tBar, x + barW / 2f, bBar, barPaint)
                }
            }
        }

        // The progress curve.
        val segs = GraphMath.smoothPath(points, tension)
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

        // Sample dots (always drawn, so even a single-sample week shows something).
        if (points.isNotEmpty()) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = DOT
            }
            val dotR = (h * 0.022f).coerceIn(2f, 10f)
            for (p in points) canvas.drawCircle(px(p.x), py(p.y), dotR, dotPaint)
        }

        return bmp
    }

    /** Warm blurred bloom under the curve — the ProgressRing/LinearMeter recipe. */
    private fun drawGlow(canvas: Canvas, path: Path, stroke: Float) {
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
