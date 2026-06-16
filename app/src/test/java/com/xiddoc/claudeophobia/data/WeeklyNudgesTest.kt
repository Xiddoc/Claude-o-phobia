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
        val a = WeeklyNudges.forDay(42L, 0.5, DayOfWeek.THURSDAY, Locale.ENGLISH)
        val b = WeeklyNudges.forDay(42L, 0.5, DayOfWeek.THURSDAY, Locale.ENGLISH)
        assertEquals(a.body, b.body)
    }

    @Test
    fun clampsOutOfRangeFraction() {
        val over = WeeklyNudges.forDay(1L, 1.5, DayOfWeek.THURSDAY, Locale.ENGLISH)
        val under = WeeklyNudges.forDay(1L, -0.2, DayOfWeek.THURSDAY, Locale.ENGLISH)
        assertFalse(over.body.contains("150%"))
        assertFalse(under.body.contains("-"))
    }
}
