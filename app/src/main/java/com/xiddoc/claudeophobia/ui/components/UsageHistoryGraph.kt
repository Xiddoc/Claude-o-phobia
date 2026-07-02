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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiddoc.claudeophobia.data.GraphMath
import com.xiddoc.claudeophobia.data.Sample
import com.xiddoc.claudeophobia.data.Vec2
import com.xiddoc.claudeophobia.data.WeekBucket
import com.xiddoc.claudeophobia.ui.theme.ClaudeClay
import com.xiddoc.claudeophobia.ui.theme.ClaudeClayBright
import com.xiddoc.claudeophobia.ui.theme.ClaudeGlow
import com.xiddoc.claudeophobia.ui.theme.OnBackground
import com.xiddoc.claudeophobia.ui.theme.OnSurfaceMuted
import com.xiddoc.claudeophobia.ui.theme.Surface
import com.xiddoc.claudeophobia.ui.theme.TrackColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The horizontal gridlines drawn behind the progress curve, as unit-square y values (0%..100%). */
private val GRID_LEVELS = listOf(0.25f, 0.5f, 0.75f, 1f)

private val TOOLTIP_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())

/** One stroked polyline in a [LineGraphCanvas]: raw unit-square points, curved and drawn. */
private data class GraphSeries(
    val points: List<Vec2>,
    val tension: Float,
    /** When true the points are already noise-smoothed; skip the moving-average pass. */
    val preSmoothed: Boolean = false,
    val glow: Boolean = false,
    val showDots: Boolean = false,
    val gradient: Boolean = false,
)

/** A point the user can tap (nearest by x) to reveal [label] in a callout. */
private data class InspectPoint(val pos: Vec2, val label: String)

/** The built model for a line chart: its polylines, the inspectable points, and chrome. */
private class LineModel(
    val series: List<GraphSeries>,
    val inspect: List<InspectPoint>,
    val baseline: Float,
    val dividers: List<Float>,
)

/**
 * The in-app weekly-progress graph: a slightly Bezier-curved, noise-smoothed
 * progress line with sample dots, faint percentage gridlines, and a "now" marker.
 * The "gained per day" derivative lives in its own [WeeklyGainGraph] below. Shares
 * the exact [GraphMath] geometry with the widget's bitmap renderer.
 *
 * When [interactive] is set, tapping anywhere at an x pins the *nearest* recorded
 * sample (x-only, so an imprecise vertical tap still lands on the curve) and shows
 * its date/time (in [zone]) and percent; tapping the same point again dismisses it.
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
    val model = remember(week, tension, glow, zone) {
        val sp = GraphMath.toSamplePoints(
            week?.samples.orEmpty(),
            week?.weekStartMs ?: 0L,
            week?.weekEndMs ?: 0L,
        )
        LineModel(
            series = listOf(
                GraphSeries(sp.map { it.pos }, tension, glow = glow, showDots = true, gradient = true),
            ),
            inspect = sp.map { InspectPoint(it.pos, sampleLabel(it.sample, zone)) },
            baseline = 0f,
            dividers = emptyList(),
        )
    }
    LineGraphCanvas(
        model = model,
        percentGrid = true,
        nowFraction = todayFraction,
        interactive = interactive,
        modifier = modifier,
    )
}

/**
 * The continuous multi-week progress graph for the History range views: every week
 * in [weeks] on one shared time axis, each stroked and smoothed within its own
 * bounds (so a reset never rounds into a false ramp) with a faint divider at each
 * boundary — the line simply drops ~100%→0% across it, the sawtooth to eyeball
 * against the continuous gained/day chart. Inspectable exactly like the week view.
 */
@Composable
fun RangeHistoryGraph(
    weeks: List<WeekBucket>,
    tension: Float,
    glow: Boolean,
    nowFraction: Float?,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    if (weeks.isEmpty()) return
    val model = remember(weeks, tension, glow, zone) {
        val gStart = weeks.first().weekStartMs
        val gEnd = weeks.last().weekEndMs
        val span = (gEnd - gStart).toDouble().coerceAtLeast(1.0)
        val series = ArrayList<GraphSeries>(weeks.size)
        val inspect = ArrayList<InspectPoint>()
        val dividers = ArrayList<Float>()
        for ((i, wk) in weeks.withIndex()) {
            if (i > 0) dividers.add(((wk.weekStartMs - gStart) / span).toFloat())
            val sp = GraphMath.toSamplePoints(wk.samples, gStart, gEnd)
            // No raw dots across a multi-week span — they'd clutter; inspection is
            // x-snap so there's nothing to aim at anyway.
            series.add(GraphSeries(sp.map { it.pos }, tension, glow = glow, showDots = false, gradient = true))
            for (p in sp) inspect.add(InspectPoint(p.pos, sampleLabel(p.sample, zone)))
        }
        LineModel(series, inspect, baseline = 0f, dividers = dividers)
    }
    LineGraphCanvas(
        model = model,
        percentGrid = true,
        nowFraction = nowFraction,
        interactive = true,
        modifier = modifier,
    )
}

/** Callout text for a progress sample: "Jul 3, 2:00 PM  ·  42%". */
private fun sampleLabel(sample: Sample, zone: ZoneId): String =
    "${TOOLTIP_FMT.format(Instant.ofEpochMilli(sample.tsMs).atZone(zone))}  ·  ${sample.pct}%"

/**
 * The single-week "gained per day" chart — now a fine-grained, inspectable *line*
 * (progress rate at sample resolution) rather than a per-day bar chart. Its own
 * [tension] smooths the inherently spiky inter-sample rate independently of the
 * progress line.
 */
@Composable
fun WeeklyGainGraph(
    week: WeekBucket,
    tension: Float,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    GainLineChart(listOf(week), tension, zone, interactive, modifier)
}

/**
 * The continuous gained-per-day line across [weeks] — the point of the range views.
 * Each week's fine-grained rate is smoothed within its own bounds and concatenated
 * on the shared axis, so the derivative flows uninterrupted from one week into the
 * next and its continuity is readable at a glance.
 */
@Composable
fun RangeGainGraph(
    weeks: List<WeekBucket>,
    tension: Float,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    GainLineChart(weeks, tension, zone, interactive = true, modifier = modifier)
}

@Composable
private fun GainLineChart(
    weeks: List<WeekBucket>,
    tension: Float,
    zone: ZoneId,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    if (weeks.isEmpty()) return
    val model = remember(weeks, tension, zone) { buildRateModel(weeks, tension, zone) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(width = 14.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ClaudeClay),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Gained / day",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceMuted,
            )
        }
        Spacer(Modifier.height(6.dp))
        LineGraphCanvas(
            model = model,
            percentGrid = false,
            nowFraction = null,
            interactive = interactive,
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
    }
}

/**
 * Builds the gained/day line model: per-week fine-grained [GraphMath.progressRates],
 * each smoothed on its own by [GraphMath.smoothRates] at [tension] (never across a
 * reset), then all normalized against one shared [GraphMath.rateScale] so the y
 * scale is stable across the range. The zero-rate baseline drops to the floor when
 * nothing ever fell, else lifts to leave room for the negative excursions.
 */
private fun buildRateModel(weeks: List<WeekBucket>, tension: Float, zone: ZoneId): LineModel {
    val gStart = weeks.first().weekStartMs
    val gEnd = weeks.last().weekEndMs
    val span = (gEnd - gStart).toDouble().coerceAtLeast(1.0)
    val perWeek = ArrayList<List<GraphMath.RateSample>>(weeks.size)
    val all = ArrayList<GraphMath.RateSample>()
    val dividers = ArrayList<Float>()
    for ((i, wk) in weeks.withIndex()) {
        if (i > 0) dividers.add(((wk.weekStartMs - gStart) / span).toFloat())
        val smoothed = GraphMath.smoothRates(GraphMath.progressRates(wk.samples, gStart, gEnd), tension)
        perWeek.add(smoothed)
        all.addAll(smoothed)
    }
    val scale = GraphMath.rateScale(all)
    val hasNeg = all.any { it.ratePerDay < 0f }
    val baseline = if (hasNeg) 0.32f else 0.06f
    val posRange = 1f - baseline
    val negRange = baseline
    fun yOf(rate: Float): Float {
        val u = if (rate >= 0f) baseline + (rate / scale) * posRange else baseline + (rate / scale) * negRange
        return u.coerceIn(0f, 1f)
    }
    val series = perWeek.map { sm ->
        GraphSeries(sm.map { Vec2(it.x, yOf(it.ratePerDay)) }, tension, preSmoothed = true, gradient = false)
    }
    val inspect = all.map { InspectPoint(Vec2(it.x, yOf(it.ratePerDay)), rateLabel(it, zone)) }
    return LineModel(series, inspect, baseline, dividers)
}

/** Callout text for a rate point: "Jul 3, 2:00 PM  ·  +12%/day". */
private fun rateLabel(rate: GraphMath.RateSample, zone: ZoneId): String {
    val r = rate.ratePerDay.roundToInt()
    val signed = if (r >= 0) "+$r" else "$r"
    return "${TOOLTIP_FMT.format(Instant.ofEpochMilli(rate.tsMs).atZone(zone))}  ·  $signed%/day"
}

/**
 * The shared canvas behind every line chart in this file. Strokes each series
 * (noise-smoothed unless pre-smoothed), draws either the percentage gridlines
 * (progress) or a single zero-rate baseline (derivative), the week dividers, the
 * "now" marker, and — when [interactive] — the tap-to-inspect crosshair, highlight
 * and callout. Selection is x-nearest, so a vertically-imprecise tap still lands on
 * the line; tapping the same point again clears it.
 */
@Composable
private fun LineGraphCanvas(
    model: LineModel,
    percentGrid: Boolean,
    nowFraction: Float?,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    var selected by remember(model) { mutableStateOf<Int?>(null) }
    val inspect = model.inspect

    val tapModifier = if (interactive && inspect.isNotEmpty()) {
        Modifier.pointerInput(model) {
            detectTapGestures { tap ->
                val padX = size.width * 0.02f
                val contentW = (size.width - 2 * padX).coerceAtLeast(1f)
                var best = 0
                var bestD = Float.MAX_VALUE
                for (i in inspect.indices) {
                    val d = abs(tap.x - (padX + inspect[i].pos.x.coerceIn(0f, 1f) * contentW))
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
                selected = if (selected == best) null else best
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

        // Reference chrome: percentage gridlines for progress, a single faint zero
        // line for the derivative band.
        if (percentGrid) {
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
        } else {
            drawLine(
                color = OnSurfaceMuted.copy(alpha = 0.3f),
                start = Offset(left, py(model.baseline)),
                end = Offset(left + contentW, py(model.baseline)),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // Week-boundary dividers (multi-week ranges only).
        for (frac in model.dividers) {
            val x = px(frac)
            drawLine(OnSurfaceMuted.copy(alpha = 0.18f), Offset(x, top), Offset(x, top + contentH), strokeWidth = 1.dp.toPx())
        }

        // The series.
        for (s in model.series) {
            val pts = if (s.preSmoothed) s.points else GraphMath.smoothedPoints(s.points, s.tension)
            val segs = GraphMath.smoothPath(pts, s.tension)
            if (segs.isNotEmpty()) {
                val path = Path().apply {
                    moveTo(px(segs.first().p0.x), py(segs.first().p0.y))
                    for (seg in segs) cubicTo(px(seg.c1.x), py(seg.c1.y), px(seg.c2.x), py(seg.c2.y), px(seg.p3.x), py(seg.p3.y))
                }
                val strokePx = 2.5.dp.toPx()
                if (s.glow) drawGraphGlow(path, strokePx)
                drawPath(
                    path = path,
                    brush = if (s.gradient) Brush.horizontalGradient(listOf(ClaudeClay, ClaudeClayBright)) else SolidColor(ClaudeClay),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            if (s.showDots) {
                for (p in GraphMath.thinForDots(s.points)) {
                    drawCircle(ClaudeClayBright, radius = 3.dp.toPx(), center = Offset(px(p.x), py(p.y)))
                }
            }
        }

        // "Now" marker.
        if (nowFraction != null) {
            val x = px(nowFraction.coerceIn(0f, 1f))
            drawLine(ClaudeClay.copy(alpha = 0.5f), Offset(x, top), Offset(x, top + contentH), strokeWidth = 1.dp.toPx())
        }

        // Tap-to-inspect: crosshair at the x, a highlight on the point, and its callout.
        selected?.let { idx ->
            val ip = inspect.getOrNull(idx) ?: return@let
            val cx = px(ip.pos.x)
            val cy = py(ip.pos.y)
            drawLine(OnSurfaceMuted.copy(alpha = 0.4f), Offset(cx, top), Offset(cx, top + contentH), strokeWidth = 1.dp.toPx())
            drawCircle(color = ClaudeGlow, radius = 5.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color = ClaudeClay, radius = 8.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
            drawTooltip(ip.label, cx, cy, left, left + contentW, top)
        }
    }
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
