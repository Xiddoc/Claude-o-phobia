package com.xiddoc.claudeophobia.data

/**
 * A best-effort reading of Claude usage pulled from the Claude app's private
 * storage. Every field is nullable because the on-device schema is undocumented
 * and may change — the UI degrades gracefully when a value can't be found.
 */
data class UsageSnapshot(
    /** Percent of the weekly limit consumed, 0..100. */
    val weeklyUtilizationPercent: Double? = null,
    /** Epoch millis at which the weekly window resets, if discovered. */
    val weeklyResetEpochMs: Long? = null,
    /** Percent of the current 5-hour window consumed, 0..100. */
    val fiveHourUtilizationPercent: Double? = null,
    /** Epoch millis at which the current 5-hour window ends, if discovered. */
    val fiveHourResetEpochMs: Long? = null,
    /** Where the values came from, for debugging in the UI. */
    val sourceLabel: String? = null,
) {
    val hasAnything: Boolean
        get() = weeklyUtilizationPercent != null ||
            weeklyResetEpochMs != null ||
            fiveHourUtilizationPercent != null ||
            fiveHourResetEpochMs != null
}

/** Result of attempting to read usage from the Claude app via root. */
sealed interface RootResult {
    /** Root usage reading is switched off in settings. */
    data object Disabled : RootResult

    /** No usable result yet (initial state / in-flight). */
    data object Idle : RootResult

    /** `su` is unavailable or denied us a shell. */
    data object NoRoot : RootResult

    /** Root works, but we couldn't locate Claude's usage data. */
    data class NotFound(val detail: String) : RootResult

    /** Something blew up while shelling out. */
    data class Error(val message: String) : RootResult

    /** We found at least one usage value. */
    data class Found(val snapshot: UsageSnapshot) : RootResult
}
