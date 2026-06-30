package com.xiddoc.claudeophobia.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.xiddoc.claudeophobia.data.GraphMath
import com.xiddoc.claudeophobia.data.WeekBucket
import com.xiddoc.claudeophobia.ui.theme.ClaudeClay
import com.xiddoc.claudeophobia.ui.theme.ClaudeClayBright
import com.xiddoc.claudeophobia.ui.theme.ClaudeGlow
import com.xiddoc.claudeophobia.ui.theme.DangerRed
import com.xiddoc.claudeophobia.ui.theme.OnSurfaceMuted
import com.xiddoc.claudeophobia.ui.theme.TrackColor

/**
 * The in-app weekly-progress graph: a slightly Bezier-curved progress line with an
 * optional "gained per day" derivative overlay, sample dots, and a "now" marker for
 * the current week. Shares the exact [GraphMath] geometry with the home-screen
 * widget's bitmap renderer, so the two can never disagree — only the canvas differs.
 *
 * The risky numeric work (0/1/2 points, NaN, gaps, overshoot) all lives in
 * [GraphMath]; this composable only maps the 0..1 unit square into pixels (y is
 * flipped) and strokes it. Empty / single-sample weeks are handled by the caller,
 * which shows explanatory copy instead of a bare canvas.
 */
@Composable
fun UsageHistoryGraph(
    week: WeekBucket?,
    tension: Float,
    showDerivative: Boolean,
    glow: Boolean,
    todayFraction: Float?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth()) {
        val padX = size.width * 0.02f
        val padY = size.height * 0.08f
        val left = padX
        val top = padY
        val contentW = (size.width - 2 * padX).coerceAtLeast(1f)
        val contentH = (size.height - 2 * padY).coerceAtLeast(1f)
        fun px(x: Float) = left + x.coerceIn(0f, 1f) * contentW
        fun py(y: Float) = top + (1f - y.coerceIn(0f, 1f)) * contentH

        // Baseline track along 0%.
        drawLine(
            color = TrackColor,
            start = Offset(left, py(0f)),
            end = Offset(left + contentW, py(0f)),
            strokeWidth = 2.dp.toPx(),
        )

        val samples = week?.samples.orEmpty()
        val start = week?.weekStartMs ?: 0L
        val end = week?.weekEndMs ?: 0L
        val points = GraphMath.toPoints(samples, start, end)

        // Derivative overlay (behind the line).
        if (showDerivative && week != null) {
            val deriv = GraphMath.dailyDerivative(samples, start, end)
            if (deriv.isNotEmpty()) {
                val scale = GraphMath.derivativeScale(deriv)
                val zeroY = top + contentH * 0.80f
                val bandHalf = contentH * 0.18f
                val barW = (contentW / (deriv.size * 2f)).coerceIn(2f, contentW * 0.08f)
                drawLine(
                    color = OnSurfaceMuted.copy(alpha = 0.25f),
                    start = Offset(left, zeroY),
                    end = Offset(left + contentW, zeroY),
                    strokeWidth = 1.dp.toPx(),
                )
                for (d in deriv) {
                    val cx = px(d.xMid)
                    val frac = (d.deltaPerDay / scale).coerceIn(-1f, 1f)
                    val barH = frac * bandHalf
                    val color = if (d.deltaPerDay < 0f) DangerRed.copy(alpha = 0.5f)
                    else ClaudeClay.copy(alpha = 0.35f)
                    val topY = if (barH >= 0f) zeroY - barH else zeroY
                    val h = kotlin.math.abs(barH)
                    drawRect(
                        color = color,
                        topLeft = Offset(cx - barW / 2f, topY),
                        size = androidx.compose.ui.geometry.Size(barW, h),
                    )
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
            val strokePx = 2.5.dp.toPx()
            if (glow) drawGraphGlow(path, strokePx)
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(listOf(ClaudeClay, ClaudeClayBright)),
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        // Sample dots, thinned so a fine sampling cadence stays legible.
        for (p in GraphMath.thinForDots(points)) {
            drawCircle(color = ClaudeClayBright, radius = 3.dp.toPx(), center = Offset(px(p.x), py(p.y)))
        }

        // "Now" marker for the current week.
        if (todayFraction != null) {
            val x = px(todayFraction.coerceIn(0f, 1f))
            drawLine(
                color = ClaudeClay.copy(alpha = 0.5f),
                start = Offset(x, top),
                end = Offset(x, top + contentH),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

/** A compact legend for the graph: the clay progress line and the derivative bars. */
@Composable
fun GraphLegend(
    showDerivative: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(ClaudeClay),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Progress %",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceMuted,
        )
        if (showDerivative) {
            Spacer(Modifier.width(16.dp))
            Spacer(
                Modifier
                    .width(10.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ClaudeClay.copy(alpha = 0.35f)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Gained / day",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceMuted,
            )
        }
    }
}

/** Warm blurred bloom under the curve — the ProgressRing/LinearMeter LED recipe. */
private fun DrawScope.drawGraphGlow(path: Path, strokePx: Float) {
    drawIntoCanvas { canvas ->
        val paint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeWidth = strokePx
        }
        val layers = listOf(
            14.dp.toPx() to ClaudeClay.copy(alpha = 0.45f),
            8.dp.toPx() to ClaudeClayBright.copy(alpha = 0.55f),
            4.dp.toPx() to ClaudeGlow.copy(alpha = 0.75f),
        )
        val androidPath = path.asAndroidPath()
        layers.forEach { (radius, color) ->
            paint.color = color.toArgb()
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            canvas.nativeCanvas.drawPath(androidPath, paint)
        }
        paint.maskFilter = null
    }
}
