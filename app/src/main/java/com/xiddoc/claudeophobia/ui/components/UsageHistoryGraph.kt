package com.xiddoc.claudeophobia.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiddoc.claudeophobia.data.GraphMath
import com.xiddoc.claudeophobia.data.SamplePoint
import com.xiddoc.claudeophobia.data.WeekBucket
import com.xiddoc.claudeophobia.ui.theme.ClaudeClay
import com.xiddoc.claudeophobia.ui.theme.ClaudeClayBright
import com.xiddoc.claudeophobia.ui.theme.ClaudeGlow
import com.xiddoc.claudeophobia.ui.theme.DangerRed
import com.xiddoc.claudeophobia.ui.theme.OnBackground
import com.xiddoc.claudeophobia.ui.theme.OnSurfaceMuted
import com.xiddoc.claudeophobia.ui.theme.Surface
import com.xiddoc.claudeophobia.ui.theme.TrackColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The horizontal gridlines drawn behind the curve, as unit-square y values (0%..100%). */
private val GRID_LEVELS = listOf(0.25f, 0.5f, 0.75f, 1f)

private val TOOLTIP_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())

/**
 * The in-app weekly-progress graph: a slightly Bezier-curved progress line with
 * sample dots, faint percentage gridlines, and a "now" marker for the current
 * week. The "gained per day" derivative now lives in its own [WeeklyGainGraph]
 * below, rather than overlaying the curve. Shares the exact [GraphMath] geometry
 * with the home-screen widget's bitmap renderer, so the two can never disagree —
 * only the canvas differs.
 *
 * The risky numeric work (0/1/2 points, NaN, gaps, overshoot) all lives in
 * [GraphMath]; this composable only maps the 0..1 unit square into pixels (y is
 * flipped) and strokes it. Empty / single-sample weeks are handled by the caller,
 * which shows explanatory copy instead of a bare canvas.
 *
 * When [interactive] is set, tapping near a recorded sample pins a callout showing
 * that point's date/time (in [zone]) and percent; tapping empty space dismisses it.
 */
@Composable
fun UsageHistoryGraph(
    week: WeekBucket?,
    tension: Float,
    glow: Boolean,
    todayFraction: Float?,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    // Sample-aligned points (raw Sample + unit-square position) power both the dots
    // and tap hit-testing, so a selection can never drift from the drawn curve.
    val samplePoints = remember(week) {
        GraphMath.toSamplePoints(
            week?.samples.orEmpty(),
            week?.weekStartMs ?: 0L,
            week?.weekEndMs ?: 0L,
        )
    }
    // Reset any pinned callout when the week (i.e. the pager page) changes.
    var selected by remember(week) { mutableStateOf<SamplePoint?>(null) }

    val tapModifier = if (interactive && samplePoints.isNotEmpty()) {
        Modifier.pointerInput(samplePoints) {
            detectTapGestures { tap ->
                val padX = size.width * 0.02f
                val padY = size.height * 0.08f
                val contentW = (size.width - 2 * padX).coerceAtLeast(1f)
                val contentH = (size.height - 2 * padY).coerceAtLeast(1f)
                fun sx(x: Float) = padX + x.coerceIn(0f, 1f) * contentW
                fun sy(y: Float) = padY + (1f - y.coerceIn(0f, 1f)) * contentH
                val nearest = samplePoints.minByOrNull { sp ->
                    val dx = tap.x - sx(sp.pos.x)
                    val dy = tap.y - sy(sp.pos.y)
                    dx * dx + dy * dy
                }
                // Only pin when the tap lands reasonably close; otherwise dismiss.
                val hitRadiusPx = 40.dp.toPx()
                selected = nearest?.takeIf { sp ->
                    val dx = tap.x - sx(sp.pos.x)
                    val dy = tap.y - sy(sp.pos.y)
                    dx * dx + dy * dy <= hitRadiusPx * hitRadiusPx
                }
            }
        }
    } else {
        Modifier
    }

    Canvas(modifier = modifier.fillMaxWidth().then(tapModifier)) {
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

        // Faint percentage gridlines (25/50/75/100%) so it's easy to read off where
        // any point on the curve sits, with a small label at the left of each.
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = OnSurfaceMuted.copy(alpha = 0.6f).toArgb()
            textSize = 9.sp.toPx()
        }
        for (level in GRID_LEVELS) {
            val y = py(level)
            drawLine(
                color = OnSurfaceMuted.copy(alpha = 0.12f),
                start = Offset(left, y),
                end = Offset(left + contentW, y),
                strokeWidth = 1.dp.toPx(),
            )
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    "${(level * 100).toInt()}%",
                    left + 2.dp.toPx(),
                    y - 2.dp.toPx(),
                    labelPaint,
                )
            }
        }

        val samples = week?.samples.orEmpty()
        val start = week?.weekStartMs ?: 0L
        val end = week?.weekEndMs ?: 0L
        val points = GraphMath.toPoints(samples, start, end)

        // The progress curve — noise-smoothed (moving average) before the Bezier pass,
        // while the sample dots below stay on the raw points.
        val segs = GraphMath.smoothPath(GraphMath.smoothedPoints(points, tension), tension)
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

        // Pinned selection: highlight the point and float its date/time + percent.
        selected?.let { sp ->
            val cx = px(sp.pos.x)
            val cy = py(sp.pos.y)
            drawCircle(color = ClaudeGlow, radius = 5.dp.toPx(), center = Offset(cx, cy))
            drawCircle(
                color = ClaudeClay,
                radius = 8.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx()),
            )
            val label = "${TOOLTIP_FMT.format(Instant.ofEpochMilli(sp.sample.tsMs).atZone(zone))}  ·  ${sp.sample.pct}%"
            drawTooltip(label, cx, cy, left, left + contentW, top)
        }
    }
}

/**
 * The "gained per day" companion chart, shown directly under [UsageHistoryGraph]
 * when the derivative is enabled. Each bar is a calendar day's progress delta,
 * grown from a baseline (up for gains in clay, down for the rare drop in red), so
 * it's easy to read *how fast* progress was earned without it fighting the curve
 * for space. Auto-scales to the week's biggest move via [GraphMath.derivativeScale].
 *
 * A header row labels it and doubles as the legend, so the main graph no longer
 * has to. Empty / degenerate weeks draw nothing but the frame.
 */
@Composable
fun WeeklyGainGraph(
    week: WeekBucket?,
    modifier: Modifier = Modifier,
) {
    val deriv = remember(week) {
        GraphMath.dailyDerivative(
            week?.samples.orEmpty(),
            week?.weekStartMs ?: 0L,
            week?.weekEndMs ?: 0L,
        )
    }
    GainChart(deriv = deriv, modifier = modifier)
}

/** The gained-per-day chart body: a labelled header plus a baseline bar chart of [deriv]. */
@Composable
private fun GainChart(
    deriv: List<com.xiddoc.claudeophobia.data.DerivativePoint>,
    modifier: Modifier = Modifier,
    dividers: List<Float> = emptyList(),
    height: Dp = 72.dp,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .width(10.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ClaudeClay.copy(alpha = 0.55f)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Gained / day",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceMuted,
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val padX = size.width * 0.02f
            val padY = size.height * 0.12f
            val left = padX
            val top = padY
            val contentW = (size.width - 2 * padX).coerceAtLeast(1f)
            val contentH = (size.height - 2 * padY).coerceAtLeast(1f)

            // Faint week-boundary dividers (only meaningful in a multi-week range).
            for (frac in dividers) {
                val x = left + frac.coerceIn(0f, 1f) * contentW
                drawLine(
                    color = OnSurfaceMuted.copy(alpha = 0.12f),
                    start = Offset(x, top),
                    end = Offset(x, top + contentH),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            if (deriv.isEmpty()) return@Canvas
            val scale = GraphMath.derivativeScale(deriv)

            // Keep the zero line at the bottom when nothing ever dropped (the common
            // case: progress only climbs), so bars grow up from the floor. Lift it to
            // leave room below only when a genuine drop needs to be shown.
            val hasNeg = deriv.any { it.deltaPerDay < 0f }
            val zeroY = if (hasNeg) top + contentH * 0.72f else top + contentH
            val posBand = zeroY - top
            val negBand = (top + contentH) - zeroY
            val barW = (contentW / deriv.size * 0.7f).coerceIn(1f, contentW * 0.1f)

            drawLine(
                color = OnSurfaceMuted.copy(alpha = 0.25f),
                start = Offset(left, zeroY),
                end = Offset(left + contentW, zeroY),
                strokeWidth = 1.dp.toPx(),
            )
            for (d in deriv) {
                val cx = left + d.xMid.coerceIn(0f, 1f) * contentW
                val frac = (d.deltaPerDay / scale).coerceIn(-1f, 1f)
                val barTop: Float
                val barH: Float
                if (frac >= 0f) {
                    barH = frac * posBand
                    barTop = zeroY - barH
                } else {
                    barH = -frac * negBand
                    barTop = zeroY
                }
                if (barH < 0.5f) continue // a zero-gain day: nothing to draw
                drawRoundRect(
                    color = if (d.deltaPerDay < 0f) DangerRed.copy(alpha = 0.6f)
                    else ClaudeClay.copy(alpha = 0.6f),
                    topLeft = Offset(cx - barW / 2f, barTop),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
    }
}

/**
 * The continuous multi-week progress graph for the History range views. Draws every
 * week in [weeks] on one shared time axis: each week's curve is stroked separately
 * (and noise-smoothed within its own bounds) so the Catmull-Rom pass never rounds a
 * reset into a false ramp, and a faint divider marks each week boundary — the line
 * simply drops from ~100% back to 0% across it, which is exactly the sawtooth the
 * user wants to eyeball against the continuous gained/day chart below.
 *
 * [nowFraction] (0..1 across the whole range) draws the "now" marker when the last
 * week is the current one. Non-interactive by design — this is a trend overview;
 * the single-week view keeps the tap-to-inspect callout.
 */
@Composable
fun RangeHistoryGraph(
    weeks: List<WeekBucket>,
    tension: Float,
    glow: Boolean,
    nowFraction: Float?,
    modifier: Modifier = Modifier,
) {
    if (weeks.isEmpty()) return
    val globalStart = weeks.first().weekStartMs
    val globalEnd = weeks.last().weekEndMs

    Canvas(modifier = modifier.fillMaxWidth()) {
        val padX = size.width * 0.02f
        val padY = size.height * 0.08f
        val left = padX
        val top = padY
        val contentW = (size.width - 2 * padX).coerceAtLeast(1f)
        val contentH = (size.height - 2 * padY).coerceAtLeast(1f)
        val span = (globalEnd - globalStart).toDouble().coerceAtLeast(1.0)
        fun gx(tsMs: Long) = left + ((tsMs - globalStart).toDouble() / span).coerceIn(0.0, 1.0).toFloat() * contentW
        fun px(x: Float) = left + x.coerceIn(0f, 1f) * contentW
        fun py(y: Float) = top + (1f - y.coerceIn(0f, 1f)) * contentH

        // Baseline + percentage gridlines, matching the single-week graph.
        drawLine(TrackColor, Offset(left, py(0f)), Offset(left + contentW, py(0f)), strokeWidth = 2.dp.toPx())
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = OnSurfaceMuted.copy(alpha = 0.6f).toArgb()
            textSize = 9.sp.toPx()
        }
        for (level in GRID_LEVELS) {
            val y = py(level)
            drawLine(OnSurfaceMuted.copy(alpha = 0.12f), Offset(left, y), Offset(left + contentW, y), strokeWidth = 1.dp.toPx())
            drawIntoCanvas { c ->
                c.nativeCanvas.drawText("${(level * 100).toInt()}%", left + 2.dp.toPx(), y - 2.dp.toPx(), labelPaint)
            }
        }

        // Each week: its own smoothed curve + dots, mapped onto the global time axis.
        for ((i, week) in weeks.withIndex()) {
            if (i > 0) {
                val bx = gx(week.weekStartMs)
                drawLine(
                    color = OnSurfaceMuted.copy(alpha = 0.18f),
                    start = Offset(bx, top),
                    end = Offset(bx, top + contentH),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            // Positions in the *global* unit square (span = whole range).
            val pts = GraphMath.toPoints(week.samples, globalStart, globalEnd)
            val segs = GraphMath.smoothPath(GraphMath.smoothedPoints(pts, tension), tension)
            if (segs.isNotEmpty()) {
                val path = Path().apply {
                    moveTo(px(segs.first().p0.x), py(segs.first().p0.y))
                    for (s in segs) cubicTo(px(s.c1.x), py(s.c1.y), px(s.c2.x), py(s.c2.y), px(s.p3.x), py(s.p3.y))
                }
                val strokePx = 2.dp.toPx()
                if (glow) drawGraphGlow(path, strokePx)
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(listOf(ClaudeClay, ClaudeClayBright)),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            for (p in GraphMath.thinForDots(pts)) {
                drawCircle(ClaudeClayBright, radius = 2.dp.toPx(), center = Offset(px(p.x), py(p.y)))
            }
        }

        if (nowFraction != null) {
            val x = px(nowFraction.coerceIn(0f, 1f))
            drawLine(ClaudeClay.copy(alpha = 0.5f), Offset(x, top), Offset(x, top + contentH), strokeWidth = 1.dp.toPx())
        }
    }
}

/**
 * The continuous gained-per-day chart across [weeks] — the whole point of the range
 * views. Each week's [GraphMath.dailyDerivative] is remapped from its own 0..1 onto
 * the shared range axis and concatenated, so the daily bars flow uninterrupted from
 * one week into the next and the derivative's continuity is readable at a glance.
 */
@Composable
fun RangeGainGraph(
    weeks: List<WeekBucket>,
    modifier: Modifier = Modifier,
) {
    val model = remember(weeks) {
        if (weeks.isEmpty()) {
            emptyList<com.xiddoc.claudeophobia.data.DerivativePoint>() to emptyList<Float>()
        } else {
            val globalStart = weeks.first().weekStartMs
            val globalEnd = weeks.last().weekEndMs
            val span = (globalEnd - globalStart).toDouble().coerceAtLeast(1.0)
            val combined = ArrayList<com.xiddoc.claudeophobia.data.DerivativePoint>()
            val dividers = ArrayList<Float>()
            for ((i, week) in weeks.withIndex()) {
                if (i > 0) dividers.add(((week.weekStartMs - globalStart).toDouble() / span).toFloat())
                val weekSpan = (week.weekEndMs - week.weekStartMs).toDouble()
                for (d in GraphMath.dailyDerivative(week.samples, week.weekStartMs, week.weekEndMs)) {
                    val tsMid = week.weekStartMs + (d.xMid.toDouble() * weekSpan).toLong()
                    val gx = ((tsMid - globalStart).toDouble() / span).coerceIn(0.0, 1.0).toFloat()
                    combined.add(com.xiddoc.claudeophobia.data.DerivativePoint(gx, d.deltaPerDay))
                }
            }
            combined to dividers
        }
    }
    GainChart(deriv = model.first, modifier = modifier, dividers = model.second, height = 84.dp)
}

/** A compact legend for the progress line under the main graph. */
@Composable
fun GraphLegend(
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
    }
}

/**
 * Floats a small rounded callout with [text] just above ([cx],[cy]), clamped to
 * stay within [leftBound]..[rightBound] horizontally and nudged below the point if
 * there isn't room above ([topBound]).
 */
private fun DrawScope.drawTooltip(
    text: String,
    cx: Float,
    cy: Float,
    leftBound: Float,
    rightBound: Float,
    topBound: Float,
) {
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = OnBackground.toArgb()
        textSize = 11.sp.toPx()
    }
    val padH = 8.dp.toPx()
    val padV = 5.dp.toPx()
    val textW = textPaint.measureText(text)
    val fm = textPaint.fontMetrics
    val textH = fm.descent - fm.ascent
    val boxW = textW + 2 * padH
    val boxH = textH + 2 * padV
    val gap = 12.dp.toPx()

    val boxLeft = (cx - boxW / 2f).coerceIn(leftBound, (rightBound - boxW).coerceAtLeast(leftBound))
    var boxTop = cy - gap - boxH
    if (boxTop < topBound) boxTop = cy + gap // not enough room above; drop below the point

    drawRoundRect(
        color = Surface.copy(alpha = 0.95f),
        topLeft = Offset(boxLeft, boxTop),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(6.dp.toPx()),
    )
    drawRoundRect(
        color = ClaudeClay.copy(alpha = 0.6f),
        topLeft = Offset(boxLeft, boxTop),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(6.dp.toPx()),
        style = Stroke(width = 1.dp.toPx()),
    )
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(
            text,
            boxLeft + padH,
            boxTop + padV - fm.ascent,
            textPaint,
        )
    }
}

/** Warm blurred bloom under the curve — the ProgressRing/LinearMeter LED recipe. */
private fun DrawScope.drawGraphGlow(path: Path, strokePx: Float) {
    val androidPath = path.asAndroidPath()
    // A degenerate curve — every sample at the same percent (a flat week, most often
    // 0%) or all bunched at one instant (a near-vertical spike at the very start of a
    // week) — has a bounding box that's near-zero in one dimension. Blurring that
    // concentrates all three layers into a bright blob / smear (and can fault the mask
    // allocation), so skip the bloom below a small span and let the plain line show.
    val bounds = RectF()
    @Suppress("DEPRECATION") // single-arg overload isn't in the compileSdk 35 stubs
    androidPath.computeBounds(bounds, true)
    val minSpanPx = 2.dp.toPx()
    if (bounds.height() < minSpanPx || bounds.width() < minSpanPx) return

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
        layers.forEach { (radius, color) ->
            paint.color = color.toArgb()
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            canvas.nativeCanvas.drawPath(androidPath, paint)
        }
        paint.maskFilter = null
    }
}
