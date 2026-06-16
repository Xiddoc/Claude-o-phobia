package com.xiddoc.claudeophobia.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

class NudgeSchedulerTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun beforeWindow_schedulesLaterToday_insideWindow() {
        // 05:00 UTC — the daytime window (10:00–18:00) is still ahead.
        val now = Instant.parse("2026-06-16T05:00:00Z").toEpochMilli()
        val trigger = NudgeScheduler.nextTriggerMillis(now, utc, Random(1))

        val zoned = Instant.ofEpochMilli(trigger).atZone(utc)
        assertEquals("2026-06-16", zoned.toLocalDate().toString())
        assertTrue("hour=${zoned.hour}", zoned.hour in NudgeScheduler.WINDOW_START_HOUR until NudgeScheduler.WINDOW_END_HOUR)
        assertTrue(trigger > now)
    }

    @Test
    fun afterWindow_rollsToTomorrow_insideWindow() {
        // 21:00 UTC — today's window has passed, so it must land tomorrow.
        val now = Instant.parse("2026-06-16T21:00:00Z").toEpochMilli()
        val trigger = NudgeScheduler.nextTriggerMillis(now, utc, Random(1))

        val zoned = Instant.ofEpochMilli(trigger).atZone(utc)
        assertEquals("2026-06-17", zoned.toLocalDate().toString())
        assertTrue("hour=${zoned.hour}", zoned.hour in NudgeScheduler.WINDOW_START_HOUR until NudgeScheduler.WINDOW_END_HOUR)
        assertTrue(trigger > now)
    }

    @Test
    fun alwaysLandsStrictlyInTheFuture_acrossManySeeds() {
        val now = Instant.parse("2026-06-16T12:30:00Z").toEpochMilli()
        repeat(200) { seed ->
            val trigger = NudgeScheduler.nextTriggerMillis(now, utc, Random(seed.toLong()))
            assertTrue("seed=$seed produced a non-future trigger", trigger > now)
            val hour = Instant.ofEpochMilli(trigger).atZone(utc).hour
            assertTrue(hour in NudgeScheduler.WINDOW_START_HOUR until NudgeScheduler.WINDOW_END_HOUR)
        }
    }
}
