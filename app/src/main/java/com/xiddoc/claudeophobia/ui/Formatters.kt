package com.xiddoc.claudeophobia.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** "2d 14h 03m 22s" — trims leading zero-value units for a clean look. */
fun formatCountdown(duration: Duration): String {
    val total = maxOf(duration.seconds, 0)
    val days = total / 86_400
    val hours = (total % 86_400) / 3_600
    val minutes = (total % 3_600) / 60
    val seconds = total % 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (days > 0 || hours > 0) append("${hours}h ")
        append("${minutes.toString().padStart(2, '0')}m ")
        append("${seconds.toString().padStart(2, '0')}s")
    }.trim()
}

/** Short remaining label, e.g. "1h 12m left". */
fun formatRemainingShort(duration: Duration): String {
    val total = maxOf(duration.seconds, 0)
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m left"
        minutes > 0 -> "${minutes}m left"
        else -> "<1m left"
    }
}

fun formatResetMoment(reset: ZonedDateTime): String {
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())
    return reset.format(fmt) + " " + reset.zone.id
}

fun formatPercent(fraction: Double): String =
    "${(fraction * 100).toInt()}%"

/** Friendly zone label, e.g. "UTC" or "Europe/Berlin (UTC+02:00)". */
fun describeZone(zone: ZoneId, now: Instant): String {
    val offset = zone.rules.getOffset(now)
    return if (zone.id == "UTC") "UTC" else "${zone.id} (UTC$offset)"
}

fun dayLabel(value: Int): String =
    java.time.DayOfWeek.of(value).getDisplayName(TextStyle.FULL, Locale.getDefault())

/** Relative label for a future/past instant, e.g. "in 3h 20m" / "12m ago". */
fun formatRelative(target: Instant, now: Instant): String {
    val d = Duration.between(now, target)
    val future = !d.isNegative
    val secs = abs(d.seconds)
    val hours = secs / 3_600
    val minutes = (secs % 3_600) / 60
    val body = when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "moments"
    }
    return if (future) "in $body" else "$body ago"
}
