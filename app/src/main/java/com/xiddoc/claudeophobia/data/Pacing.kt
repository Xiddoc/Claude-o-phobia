package com.xiddoc.claudeophobia.data

/**
 * Turns "how much of the week has elapsed" vs. "how much I've actually used"
 * into a human verdict about pacing.
 *
 * A steady, evenly-paced week would have your usage tracking the clock: at 60%
 * of the week elapsed you'd be at roughly 60% usage. The signed gap between the
 * two is the whole story — positive means you're burning faster than the clock
 * (ahead of pace), negative means you've got headroom (under pace).
 *
 * Pure Kotlin, no Android types, so it can be unit-tested directly and shared by
 * the countdown screen, the widget and the daily nudge.
 */
object Pacing {

    /** Within this many points either side of the clock we call it "on pace". */
    const val ON_PACE_TOLERANCE = 5

    enum class Status { AHEAD, ON_PACE, UNDER }

    /** Signed gap: actual usage minus the on-pace target, both 0..100. */
    fun delta(actualPercent: Int, elapsedPercent: Int): Int = actualPercent - elapsedPercent

    fun status(delta: Int): Status = when {
        delta > ON_PACE_TOLERANCE -> Status.AHEAD
        delta < -ON_PACE_TOLERANCE -> Status.UNDER
        else -> Status.ON_PACE
    }

    /** Compact label for tight spots like the widget caption. */
    fun shortLabel(delta: Int): String = when (status(delta)) {
        Status.AHEAD -> "$delta% ahead of pace"
        Status.UNDER -> "${-delta}% under pace"
        Status.ON_PACE -> "on pace"
    }

    /** A small glyph that reads at a glance: hot when ahead, easy when under. */
    fun glyph(delta: Int): String = when (status(delta)) {
        Status.AHEAD -> "🔥"
        Status.UNDER -> "🚀"
        Status.ON_PACE -> "✨"
    }

    /** Friendly one-liner used in the in-app card. */
    fun verdict(delta: Int): String = when (status(delta)) {
        Status.AHEAD -> "You're $delta% ahead of pace — easy does it. 🙂"
        Status.UNDER -> "You're ${-delta}% under pace — plenty of room. 🚀"
        Status.ON_PACE -> "Right on pace. ✨"
    }
}
