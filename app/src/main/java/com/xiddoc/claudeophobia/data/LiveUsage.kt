package com.xiddoc.claudeophobia.data

/**
 * The Android-free part of deciding what to show for live usage: everything
 * that can be settled *without* making a network request. Kept pure so it can be
 * unit-tested directly (see `LiveUsageTest`).
 */
object LiveUsage {

    /**
     * Classifies the state when we are *not* going to fetch. Returns null when we
     * do have credentials and should go ahead and hit the network (the caller
     * then maps the fetch onto [UsageResult.Found] / [UsageResult.Error]).
     */
    fun preFetchState(
        enabled: Boolean,
        hasCredentials: Boolean,
        moduleActive: Boolean,
    ): UsageResult? = when {
        !enabled -> UsageResult.Disabled
        hasCredentials -> null
        moduleActive -> UsageResult.NotFound(
            "The module is active — open the Claude app once so it can hand over your session."
        )
        else -> UsageResult.ModuleInactive
    }
}
