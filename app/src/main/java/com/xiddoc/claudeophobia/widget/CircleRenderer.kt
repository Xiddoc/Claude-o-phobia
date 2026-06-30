package com.xiddoc.claudeophobia.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rasterizes the circular weekly-progress arc into an [Bitmap] for the RemoteViews
 * circle widget. A faithful port of the Compose `ProgressRing`: arc swept from the
 * top (-90°), a clay→bright→clay sweep gradient seam-aligned to the top, a round
 * cap, the same three-layer blurred bloom, and a faint pacing tick.
 *
 * All colors are hard-coded ARGB because RemoteViews has no theme. The blur radii
 * scale off the stroke width (mirroring ProgressRing's 18/10/5dp against a 16dp
 * stroke) so the glow looks identical at any widget size.
 */
object CircleRenderer {

    private const val CLAY = 0xFFD97757.toInt()
    private const val CLAY_BRIGHT = 0xFFE89A80.toInt()
    private const val GLOW = 0xFFFFC8A6.toInt()
    private const val TRACK = 0xFF35302B.toInt()
    private const val PACE = 0x59D97757.toInt() // faint clay, alpha 0x59

    /**
     * @param sidePx square bitmap edge in pixels (already size-bounded by the caller).
     * @param barPct filled fraction, 0..100.
     * @param pacePct optional pacing marker position, 1..100, or null to hide it.
     * @param glow whether to draw the warm bloom under the arc.
     */
    fun render(sidePx: Int, barPct: Int, pacePct: Int?, glow: Boolean): Bitmap {
        val side = sidePx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val stroke = side * 0.07f
        val inset = stroke / 2f + side * 0.03f
        val rect = RectF(inset, inset, side - inset, side - inset)
        val cx = side / 2f
        val cy = side / 2f
        val radius = rect.width() / 2f

        // Track (full ring).
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = TRACK
        }
        canvas.drawArc(rect, -90f, 360f, false, trackPaint)

        val sweep = 360f * (barPct.coerceIn(0, 100) / 100f)
        if (sweep > 0f) {
            if (glow) drawGlow(canvas, rect, sweep, stroke)
            val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                shader = SweepGradient(cx, cy, intArrayOf(CLAY, CLAY_BRIGHT, CLAY), null).apply {
                    // Align the gradient seam to the top, matching Brush.sweepGradient + -90 start.
                    setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
                }
            }
            canvas.drawArc(rect, -90f, sweep, false, progressPaint)
        }

        // Pacing tick: a short faint radial mark across the ring band.
        if (pacePct != null && pacePct in 1..100) {
            val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = PACE
                style = Paint.Style.STROKE
                strokeWidth = (side * 0.014f).coerceAtLeast(1f)
            }
            val angle = Math.toRadians(-90.0 + 360.0 * (pacePct / 100.0))
            val r0 = radius - stroke / 2f
            val r1 = radius + stroke / 2f
            canvas.drawLine(
                cx + (cos(angle) * r0).toFloat(), cy + (sin(angle) * r0).toFloat(),
                cx + (cos(angle) * r1).toFloat(), cy + (sin(angle) * r1).toFloat(),
                tickPaint,
            )
        }
        return bmp
    }

    /** Three blurred stroke passes, widest/softest to hottest core — ProgressRing's recipe. */
    private fun drawGlow(canvas: Canvas, rect: RectF, sweep: Float, stroke: Float) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = stroke
            shader = SweepGradient(cx, cy, intArrayOf(CLAY, CLAY_BRIGHT, CLAY), null).apply {
                setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
            }
        }
        // (blur radius, alpha) widest→hottest, scaled off the stroke like 18/10/5 vs 16dp.
        val layers = listOf(
            stroke * 1.125f to 0x73, // ~.45
            stroke * 0.625f to 0x8C, // ~.55
            stroke * 0.3125f to 0xBF, // ~.75
        )
        for ((radius, alpha) in layers) {
            if (radius <= 0f) continue
            paint.alpha = alpha
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            canvas.drawArc(rect, -90f, sweep, false, paint)
        }
        paint.maskFilter = null
    }
}
