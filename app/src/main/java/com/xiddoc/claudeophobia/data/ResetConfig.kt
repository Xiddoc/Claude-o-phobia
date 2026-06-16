package com.xiddoc.claudeophobia.data

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Describes when the weekly Claude limit resets.
 *
 * Defaults to Thursday 08:00 UTC — the moment the weekly window rolls over.
 */
data class ResetConfig(
    val dayOfWeek: DayOfWeek = DayOfWeek.THURSDAY,
    val time: LocalTime = LocalTime.of(8, 0),
    val zone: ZoneId = ZoneId.of("UTC"),
) {
    /** The first reset boundary strictly after [now]. */
    fun nextResetAfter(now: Instant): ZonedDateTime {
        val zonedNow = now.atZone(zone)
        var candidate = zonedNow
            .with(TemporalAdjusters.nextOrSame(dayOfWeek))
            .withHour(time.hour)
            .withMinute(time.minute)
            .withSecond(0)
            .withNano(0)
        if (!candidate.isAfter(zonedNow)) {
            candidate = candidate.plusWeeks(1)
        }
        return candidate
    }

    /** The most recent reset boundary at or before [now]. */
    fun previousResetAtOrBefore(now: Instant): ZonedDateTime =
        nextResetAfter(now).minusWeeks(1)

    /** Snapshot of where we are within the current weekly window. */
    fun progress(now: Instant): WeekProgress {
        val previous = previousResetAtOrBefore(now)
        val next = nextResetAfter(now)
        val total = Duration.between(previous, next)
        val elapsed = Duration.between(previous.toInstant(), now)
        val remaining = Duration.between(now, next.toInstant())
        val fraction = if (total.isZero) 0.0
        else (elapsed.toMillis().toDouble() / total.toMillis().toDouble()).coerceIn(0.0, 1.0)
        return WeekProgress(
            previousReset = previous,
            nextReset = next,
            elapsed = elapsed,
            remaining = remaining,
            fraction = fraction,
        )
    }
}

data class WeekProgress(
    val previousReset: ZonedDateTime,
    val nextReset: ZonedDateTime,
    val elapsed: Duration,
    val remaining: Duration,
    /** 0.0 just after a reset, 1.0 the moment before the next one. */
    val fraction: Double,
)
