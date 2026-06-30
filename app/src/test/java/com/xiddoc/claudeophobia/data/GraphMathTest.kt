package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphMathTest {

    private val W0 = 0L
    private val W1 = 7L * 86_400_000L // a round 7-day week

    // --- toPoints ---------------------------------------------------------

    @Test
    fun toPointsEmptyForNoSamplesOrDegenerateWeek() {
        assertTrue(GraphMath.toPoints(emptyList(), W0, W1).isEmpty())
        assertTrue(GraphMath.toPoints(listOf(Sample(0, 10)), 100, 100).isEmpty())
        assertTrue(GraphMath.toPoints(listOf(Sample(0, 10)), 100, 50).isEmpty())
    }

    @Test
    fun toPointsClampsXIntoUnitRangeAndNormalizesY() {
        val pts = GraphMath.toPoints(
            listOf(Sample(-50, 10), Sample(W1 / 2, 50), Sample(W1 + 999, 200)),
            W0, W1,
        )
        assertEquals(3, pts.size)
        assertEquals(0f, pts[0].x, 1e-6f)   // before start clamps to 0
        assertEquals(0.5f, pts[1].x, 1e-3f)
        assertEquals(1f, pts[2].x, 1e-6f)   // after end clamps to 1
        assertEquals(1f, pts[2].y, 1e-6f)   // pct 200 clamps to 100% -> 1.0
    }

    @Test
    fun toPointsCollapsesCoincidentTimestampsLastWins() {
        val pts = GraphMath.toPoints(listOf(Sample(100, 10), Sample(100, 80)), W0, W1)
        assertEquals(1, pts.size)
        assertEquals(0.8f, pts[0].y, 1e-6f)
    }

    // --- smoothPath -------------------------------------------------------

    @Test
    fun smoothPathDegreeCases() {
        assertTrue(GraphMath.smoothPath(emptyList(), 0.5f).isEmpty())
        assertTrue(GraphMath.smoothPath(listOf(Vec2(0f, 0f)), 0.5f).isEmpty())
        assertEquals(1, GraphMath.smoothPath(listOf(Vec2(0f, 0f), Vec2(1f, 1f)), 0.5f).size)
        assertEquals(3, GraphMath.smoothPath((0..3).map { Vec2(it / 3f, it / 3f) }, 0.5f).size)
    }

    @Test
    fun smoothPathControlPointsAlwaysFiniteAndBounded() {
        val pts = listOf(Vec2(0f, 0f), Vec2(0.2f, 1f), Vec2(0.5f, 0f), Vec2(1f, 1f))
        for (tension in listOf(0f, 0.5f, 0.85f, 1f)) {
            for (seg in GraphMath.smoothPath(pts, tension)) {
                for (c in listOf(seg.c1, seg.c2)) {
                    assertTrue(c.x.isFinite() && c.y.isFinite())
                    assertTrue(c.x in -0.5f..1.5f && c.y in -0.5f..1.5f)
                }
            }
        }
    }

    @Test
    fun straightTensionKeepsControlsOnTheChord() {
        val seg = GraphMath.smoothPath(listOf(Vec2(0f, 0f), Vec2(1f, 1f)), 0f).single()
        // Zero tension -> controls collapse onto the endpoints (a straight segment).
        assertEquals(seg.p0.x, seg.c1.x, 1e-6f)
        assertEquals(seg.p0.y, seg.c1.y, 1e-6f)
        assertEquals(seg.p3.x, seg.c2.x, 1e-6f)
        assertEquals(seg.p3.y, seg.c2.y, 1e-6f)
    }

    // --- dailyDerivative --------------------------------------------------

    @Test
    fun derivativeIsPositivePerDayForMonotonicWeek() {
        val samples = (0..6).map { Sample(it * 86_400_000L + 3_600_000L, it * 10) }
        val d = GraphMath.dailyDerivative(samples, W0, W1)
        assertEquals(7, d.size)
        assertEquals(0f, d[0].deltaPerDay, 1e-6f) // day 0: 0 - 0
        for (i in 1 until d.size) assertEquals(10f, d[i].deltaPerDay, 1e-6f)
    }

    @Test
    fun derivativeCarriesAcrossMissingDaysDividingByGap() {
        // Sample on day 0 (level 10) and day 3 (level 40), nothing between.
        val samples = listOf(Sample(3_600_000L, 10), Sample(3 * 86_400_000L + 3_600_000L, 40))
        val d = GraphMath.dailyDerivative(samples, W0, W1)
        assertEquals(2, d.size)
        assertEquals(10f, d[0].deltaPerDay, 1e-6f)                // day 0: 10 - 0
        assertEquals(10f, d[1].deltaPerDay, 1e-6f)               // (40-10)/3 days
    }

    @Test
    fun derivativeSignPreservedForDrops() {
        val samples = listOf(Sample(3_600_000L, 80), Sample(86_400_000L + 3_600_000L, 50))
        val d = GraphMath.dailyDerivative(samples, W0, W1)
        assertEquals(-30f, d[1].deltaPerDay, 1e-6f)
    }

    @Test
    fun derivativeSingleSampleWeekIsOnePoint() {
        val d = GraphMath.dailyDerivative(listOf(Sample(3_600_000L, 42)), W0, W1)
        assertEquals(1, d.size)
        assertEquals(42f, d[0].deltaPerDay, 1e-6f)
    }

    @Test
    fun derivativeScaleNeverZero() {
        assertEquals(1f, GraphMath.derivativeScale(emptyList()), 1e-6f)
        assertEquals(1f, GraphMath.derivativeScale(listOf(DerivativePoint(0.5f, 0f))), 1e-6f)
        assertEquals(30f, GraphMath.derivativeScale(listOf(DerivativePoint(0.5f, -30f))), 1e-6f)
    }

    @Test
    fun derivativeHandlesWeekWiderThanSevenDays() {
        // A 9-day (config-widened) week must not crash; dayIndex coerces to last slot.
        val end = 9L * 86_400_000L
        val samples = listOf(Sample(0, 10), Sample(end - 1, 90))
        val d = GraphMath.dailyDerivative(samples, 0, end)
        assertTrue(d.isNotEmpty())
        assertTrue(d.all { it.xMid in 0f..1f && it.deltaPerDay.isFinite() })
    }

    // --- navigation -------------------------------------------------------

    private fun weeks(vararg starts: Long) =
        starts.map { WeekBucket(it, it + W1, "4@8:0@UTC", listOf(Sample(it, 1))) }

    @Test
    fun navStopsAtTheEnds() {
        val w = weeks(100, 200, 300)
        assertNull(GraphMath.newerWeek(w, null))          // null pin = newest, nothing newer
        assertEquals(200L, GraphMath.olderWeek(w, null))  // older than newest
        assertEquals(100L, GraphMath.olderWeek(w, 200))
        assertNull(GraphMath.olderWeek(w, 100))           // nothing older than oldest
        assertEquals(200L, GraphMath.newerWeek(w, 100))
        assertNull(GraphMath.newerWeek(w, 300))           // nothing newer than newest
        assertNull(GraphMath.olderWeek(emptyList(), null))
    }

    @Test
    fun resolveWeekSnapsMissingPinToNearest() {
        val w = weeks(100, 200, 300)
        assertEquals(300L, GraphMath.resolveWeek(w, null)?.weekStartMs)        // newest
        assertEquals(200L, GraphMath.resolveWeek(w, 200)?.weekStartMs)         // exact
        assertEquals(200L, GraphMath.resolveWeek(w, 210)?.weekStartMs)         // nearest
        assertNull(GraphMath.resolveWeek(emptyList(), 200))
    }
}
