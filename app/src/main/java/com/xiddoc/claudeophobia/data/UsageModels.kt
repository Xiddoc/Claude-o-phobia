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

/**
 * Result of surfacing the live Claude usage that the LSPosed module captures
 * from inside the Claude app and hands to us.
 */
sealed interface UsageResult {
    /** Live usage is switched off in settings. */
    data object Disabled : UsageResult

    /** No usable result yet (initial state). */
    data object Idle : UsageResult

    /**
     * The module was installed/updated but the device hasn't rebooted since, so
     * LSPosed hasn't injected it yet. A reboot is needed before anything works.
     */
    data class RebootRequired(val detail: String) : UsageResult

    /** No session captured yet — likely the user just needs to open Claude. */
    data class NotFound(val detail: String) : UsageResult

    /** The module tried to read usage from Claude and reported a failure. */
    data class Error(val message: String) : UsageResult

    /** We have a usage snapshot the module captured. */
    data class Found(val snapshot: UsageSnapshot) : UsageResult
}
