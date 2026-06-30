package com.xiddoc.claudeophobia.data

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Pure geometry for the weekly-progress graph, shared verbatim by the in-app
 * Compose `Canvas` and the home-screen widget's `android.graphics` bitmap so the
 * two can never disagree. No Android types — every branch and numeric edge case
 * is unit-testable in plain JVM, which matters because the graph itself can't be
 * visually tested.
 *
 * Everything works in a normalized unit square: x in 0..1 across the week, y in
 * 0..1 for 0..100% usage. Renderers map that to pixels (y is flipped there).
 */

/** A point in the 0..1 unit square (or a Bezier control point, which may stray slightly outside). */
data class Vec2(val x: Float, val y: Float)

/** A cubic Bezier segment from [p0] to [p3] with control points [c1],[c2]. */
data class CubicSeg(val p0: Vec2, val c1: Vec2, val c2: Vec2, val p3: Vec2)

/** Progress gained per calendar day, anchored at the day's horizontal midpoint [xMid]. */
data class DerivativePoint(val xMid: Float, val deltaPerDay: Float)

object GraphMath {

    private const val DAY_MS = 86_400_000L

    /** Curve smoothing presets surfaced in settings (Catmull-Rom tension). */
    const val TENSION_STRAIGHT = 0f
    const val TENSION_SLIGHT = 0.5f
    const val TENSION_SMOOTH = 0.85f

    /**
     * Normalizes a bucket's samples into ascending unit-square points. Returns an
     * empty list for a degenerate week (`weekEndMs <= weekStartMs`) so nothing ever
     * divides by a zero or negative span. Samples sharing a timestamp are collapsed
     * (last wins) so the Bezier pass never sees a zero-width step.
     */
    fun toPoints(samples: List<Sample>, weekStartMs: Long, weekEndMs: Long): List<Vec2> {
        if (weekEndMs <= weekStartMs || samples.isEmpty()) return emptyList()
        val span = (weekEndMs - weekStartMs).toDouble()
        val collapsed = LinkedHashMap<Long, Sample>()
        for (s in samples.sortedBy { it.tsMs }) collapsed[s.tsMs] = s
        return collapsed.values
            .map { s ->
                val x = ((s.tsMs - weekStartMs).toDouble() / span).coerceIn(0.0, 1.0).toFloat()
                val y = s.pct.coerceIn(0, 100) / 100f
                Vec2(x, y)
            }
            .filter { it.x.isFinite() && it.y.isFinite() }
    }

    /**
     * A Catmull-Rom spline through [points], expressed as cubic Bezier segments so
     * either canvas can stroke it directly. [tension] (0 = straight polyline,
     * higher = curvier) scales how far the control points reach. Endpoints are
     * duplicated so the curve doesn't whip past the first/last sample. Control
     * coordinates are clamped to a small overshoot window and any non-finite value
     * is neutralized, so coincident or extreme inputs can never produce NaN paths.
     *
     * 0 or 1 point yields no segments (the caller draws a dot); 2 points yield one.
     */
    fun smoothPath(points: List<Vec2>, tension: Float): List<CubicSeg> {
        val n = points.size
        if (n < 2) return emptyList()
        val t = tension.coerceIn(0f, 1f)
        val segs = ArrayList<CubicSeg>(n - 1)
        for (i in 0 until n - 1) {
            val p0 = points[i]
            val p3 = points[i + 1]
            val prev = points[if (i - 1 >= 0) i - 1 else 0]
            val next = points[if (i + 2 <= n - 1) i + 2 else n - 1]
            val c1 = Vec2(
                (p0.x + (p3.x - prev.x) / 6f * t).clampCtl(),
                (p0.y + (p3.y - prev.y) / 6f * t).clampCtl(),
            )
            val c2 = Vec2(
                (p3.x - (next.x - p0.x) / 6f * t).clampCtl(),
                (p3.y - (next.y - p0.y) / 6f * t).clampCtl(),
            )
            segs.add(CubicSeg(p0, c1, c2, p3))
        }
        return segs
    }

    /** Control-point guard: neutralize NaN/Inf, clamp a touch beyond the unit square. */
    private fun Float.clampCtl(): Float = if (isFinite()) coerceIn(-0.5f, 1.5f) else 0f

    /**
     * Genuine per-calendar-day progress delta (not a smeared instantaneous rate).
     * Each day's *level* is its last sample's percent; the derivative for a day is
     * `(level - previousLevel) / gapDays`, where missing days carry the previous
     * level forward and spread the change evenly across the gap so "per day" stays
     * honest. The first observed day is measured against 0. Sign is preserved
     * (a drop is negative) so renderers can color losses differently.
     *
     * A week widened past 7 days by a config change simply gets more day slots; a
     * `gapDays >= 1` guard and an `isFinite` fallback keep it division-safe.
     */
    fun dailyDerivative(samples: List<Sample>, weekStartMs: Long, weekEndMs: Long): List<DerivativePoint> {
        if (weekEndMs <= weekStartMs || samples.isEmpty()) return emptyList()
        val dayCount = ceil((weekEndMs - weekStartMs).toDouble() / DAY_MS).toInt().coerceAtLeast(1)
        val level = arrayOfNulls<Int>(dayCount)
        for (s in samples.sortedBy { it.tsMs }) {
            val di = ((s.tsMs - weekStartMs) / DAY_MS).toInt().coerceIn(0, dayCount - 1)
            level[di] = s.pct.coerceIn(0, 100)
        }
        val out = ArrayList<DerivativePoint>()
        var prevLevel = 0
        var prevDay = -1
        for (d in 0 until dayCount) {
            val lv = level[d] ?: continue
            val gapDays = if (prevDay < 0) 1 else (d - prevDay).coerceAtLeast(1)
            val delta = (lv - prevLevel).toFloat() / gapDays.toFloat()
            val xMid = ((d + 0.5f) / dayCount).coerceIn(0f, 1f)
            out.add(DerivativePoint(xMid, if (delta.isFinite()) delta else 0f))
            prevLevel = lv
            prevDay = d
        }
        return out
    }

    /** Default cap on how many sample dots a renderer draws (keeps fine cadences legible). */
    const val DEFAULT_MAX_DOTS = 48

    /**
     * Thins [points] to at most [maxDots] roughly-evenly-spaced markers, always
     * keeping the first and last, so the dotted overlay stays legible at any
     * sampling cadence (a short interval can produce hundreds of points per week).
     * Returns the list unchanged when it already fits.
     */
    fun thinForDots(points: List<Vec2>, maxDots: Int = DEFAULT_MAX_DOTS): List<Vec2> {
        val n = points.size
        if (maxDots < 1 || n == 0) return emptyList()
        if (n <= maxDots) return points
        val out = ArrayList<Vec2>(maxDots + 1)
        val step = (n - 1).toDouble() / (maxDots - 1).coerceAtLeast(1)
        var lastIdx = -1
        for (k in 0 until maxDots) {
            val idx = (k * step).toInt().coerceIn(0, n - 1)
            if (idx != lastIdx) {
                out.add(points[idx])
                lastIdx = idx
            }
        }
        if (lastIdx != n - 1) out.add(points[n - 1])
        return out
    }

    /**
     * The magnitude that maps a derivative band to full height. At least 1f so a
     * flat or empty week never divides by zero; otherwise the largest absolute
     * daily change so the band auto-scales to the week's biggest move.
     */
    fun derivativeScale(points: List<DerivativePoint>): Float {
        val maxAbs = points.maxOfOrNull { abs(it.deltaPerDay) } ?: 0f
        return if (maxAbs.isFinite() && maxAbs > 1f) maxAbs else 1f
    }

    // --- Week navigation, shared by the widget (absolute pointer) and the in-app
    //     pager. All operate on a list already sorted ascending by weekStartMs and
    //     can never return a week outside the data (no future, no pre-earliest). ---

    /** The next-older week's key, or null if [current] is already the oldest. */
    fun olderWeek(weeks: List<WeekBucket>, current: Long?): Long? {
        if (weeks.isEmpty()) return null
        val cur = current ?: weeks.last().weekStartMs
        return weeks.filter { it.weekStartMs < cur }.maxByOrNull { it.weekStartMs }?.weekStartMs
    }

    /** The next-newer week's key, or null if [current] is null/already the newest. */
    fun newerWeek(weeks: List<WeekBucket>, current: Long?): Long? {
        if (weeks.isEmpty() || current == null) return null
        return weeks.filter { it.weekStartMs > current }.minByOrNull { it.weekStartMs }?.weekStartMs
    }

    /**
     * The bucket a (possibly stale) pinned key should display: the exact match if
     * it still exists, otherwise the nearest surviving week, otherwise — for a null
     * pin or empty data — the newest week (or null when there is none).
     */
    fun resolveWeek(weeks: List<WeekBucket>, pinned: Long?): WeekBucket? {
        if (weeks.isEmpty()) return null
        if (pinned == null) return weeks.last()
        return weeks.firstOrNull { it.weekStartMs == pinned }
            ?: weeks.minByOrNull { abs(it.weekStartMs - pinned) }
    }
}
