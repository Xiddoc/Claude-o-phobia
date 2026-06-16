package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class ResetConfigTest {

    private val config = ResetConfig(
        dayOfWeek = DayOfWeek.THURSDAY,
        time = LocalTime.of(8, 0),
        zone = ZoneId.of("UTC"),
    )

    @Test
    fun nextReset_fromMidweek_isUpcomingThursday() {
        // Wednesday 2026-06-17 08:00 UTC.
        val now = Instant.parse("2026-06-17T08:00:00Z")
        val next = config.nextResetAfter(now)
        assertEquals(Instant.parse("2026-06-18T08:00:00Z"), next.toInstant())
    }

    @Test
    fun atResetBoundary_rollsToNextWeek() {
        // Exactly Thursday 08:00 — the boundary belongs to the window just ended.
        val now = Instant.parse("2026-06-18T08:00:00Z")
        val next = config.nextResetAfter(now)
        assertEquals(Instant.parse("2026-06-25T08:00:00Z"), next.toInstant())

        val progress = config.progress(now)
        assertEquals(0.0, progress.fraction, 0.0001)
    }

    @Test
    fun progressFraction_isSixSeventhsLateInWeek() {
        val now = Instant.parse("2026-06-17T08:00:00Z")
        val progress = config.progress(now)
        // Six of seven days elapsed since the previous Thursday reset.
        assertEquals(6.0 / 7.0, progress.fraction, 0.0001)
        assertEquals(Instant.parse("2026-06-11T08:00:00Z"), progress.previousReset.toInstant())
    }

    @Test
    fun fractionAlwaysInRange() {
        var t = Instant.parse("2026-01-01T00:00:00Z")
        repeat(24 * 14) { // every hour for two weeks
            val f = config.progress(t).fraction
            assertTrue("fraction out of range: $f", f in 0.0..1.0)
            t = t.plusSeconds(3600)
        }
    }
}
