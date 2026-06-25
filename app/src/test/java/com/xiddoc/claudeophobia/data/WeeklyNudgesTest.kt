package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.util.Locale

class WeeklyNudgesTest {

    @Test
    fun fillsInPercentAndDay_noPlaceholdersLeft() {
        // Run across many days to be sure no template leaks a placeholder.
        repeat(500) { day ->
            val nudge = WeeklyNudges.forDay(
                epochDay = day.toLong(),
                weekFraction = 0.65,
                resetDay = DayOfWeek.THURSDAY,
                locale = Locale.ENGLISH,
            )
            assertFalse("placeholder left in: ${nudge.body}", nudge.body.contains("{pct}"))
            assertFalse("placeholder left in: ${nudge.body}", nudge.body.contains("{day}"))
        }
    }

    @Test
    fun reflectsTheWeekPercentage() {
        val nudge = WeeklyNudges.forDay(
            epochDay = 3L,
            weekFraction = 0.65,
            resetDay = DayOfWeek.THURSDAY,
            locale = Locale.ENGLISH,
        )
        // 0.65 -> "65%" appears whenever the chosen template uses {pct}.
        if ("%" in nudge.body) {
            assertTrue("expected 65% in: ${nudge.body}", nudge.body.contains("65%"))
        }
    }

    @Test
    fun isStableForTheSameDay() {
        val a = WeeklyNudges.forDay(42L, 0.5, DayOfWeek.THURSDAY, usagePercent = null, Locale.ENGLISH)
        val b = WeeklyNudges.forDay(42L, 0.5, DayOfWeek.THURSDAY, usagePercent = null, Locale.ENGLISH)
        assertEquals(a.body, b.body)
    }

    @Test
    fun clampsOutOfRangeFraction() {
        val over = WeeklyNudges.forDay(1L, 1.5, DayOfWeek.THURSDAY, usagePercent = null, Locale.ENGLISH)
        val under = WeeklyNudges.forDay(1L, -0.2, DayOfWeek.THURSDAY, usagePercent = null, Locale.ENGLISH)
        assertFalse(over.body.contains("150%"))
        assertFalse(under.body.contains("-"))
    }

    @Test
    fun usageAwareTemplates_neverLeakPlaceholders_andCanQuoteUsage() {
        var sawUsageFigure = false
        repeat(500) { day ->
            val nudge = WeeklyNudges.forDay(
                epochDay = day.toLong(),
                weekFraction = 0.40,
                resetDay = DayOfWeek.THURSDAY,
                usagePercent = 72,
                locale = Locale.ENGLISH,
            )
            assertFalse("placeholder left in: ${nudge.body}", nudge.body.contains("{pct}"))
            assertFalse("placeholder left in: ${nudge.body}", nudge.body.contains("{day}"))
            assertFalse("placeholder left in: ${nudge.body}", nudge.body.contains("{usage}"))
            assertFalse("placeholder left in: ${nudge.body}", nudge.body.contains("{left}"))
            assertFalse("placeholder left in: ${nudge.body}", nudge.body.contains("{pace}"))
            // 72% used (vs 40% elapsed) — when a usage template is picked it shows.
            if (nudge.body.contains("72%")) sawUsageFigure = true
        }
        // Across 500 days at least some days must land on a usage-aware template.
        assertTrue("expected some nudges to quote real usage", sawUsageFigure)
    }

    @Test
    fun withoutUsage_neverQuotesRealUsageTemplates() {
        // The 72%/28%-left wording can only come from USAGE_TEMPLATES, which must
        // stay out of the pool when we have no live figure.
        repeat(500) { day ->
            val nudge = WeeklyNudges.forDay(
                epochDay = day.toLong(),
                weekFraction = 0.40,
                resetDay = DayOfWeek.THURSDAY,
                usagePercent = null,
                locale = Locale.ENGLISH,
            )
            assertFalse("leaked usage wording: ${nudge.body}", nudge.body.contains("limit"))
        }
    }

    @Test
    fun shouldNudgeOn_hitsTargetCountEachWeek() {
        for (perWeek in 0..7) {
            // Count fires over a 7-day block; the Bresenham spread is exact.
            val fires = (0L until 7L).count { WeeklyNudges.shouldNudgeOn(it, perWeek) }
            assertEquals("perWeek=$perWeek", perWeek, fires)
        }
    }

    @Test
    fun shouldNudgeOn_sevenIsEveryDay_zeroIsNever() {
        repeat(30) { day ->
            assertTrue(WeeklyNudges.shouldNudgeOn(day.toLong(), 7))
            assertFalse(WeeklyNudges.shouldNudgeOn(day.toLong(), 0))
        }
    }

    @Test
    fun shouldNudgeOn_isStableAndClampsOutOfRange() {
        // Stable for a given day, and out-of-range targets clamp into 0..7.
        assertEquals(
            WeeklyNudges.shouldNudgeOn(123L, 3),
            WeeklyNudges.shouldNudgeOn(123L, 3),
        )
        repeat(14) { day ->
            // 99/wk clamps to 7 (every day); -5 clamps to 0 (never).
            assertTrue(WeeklyNudges.shouldNudgeOn(day.toLong(), 99))
            assertFalse(WeeklyNudges.shouldNudgeOn(day.toLong(), -5))
        }
    }
}
