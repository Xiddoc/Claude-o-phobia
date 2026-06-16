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
     *
     * With credentials we always fetch — their presence is the proof the module
     * is working. Without them, the most useful thing we can say is whether a
     * reboot is still pending (a freshly installed/updated module hasn't been
     * injected yet); otherwise we nudge the user to open Claude.
     */
    fun preFetchState(
        enabled: Boolean,
        hasCredentials: Boolean,
        rebootNeeded: Boolean,
    ): UsageResult? = when {
        !enabled -> UsageResult.Disabled
        hasCredentials -> null
        rebootNeeded -> UsageResult.RebootRequired(
            "Claude-o-phobia was installed or updated since your last reboot. LSPosed " +
                "only loads the module after a reboot — reboot, then open Claude once so " +
                "it can hand over your session."
        )
        else -> UsageResult.NotFound(
            "No session captured yet. Open the Claude app once so the module can hand it " +
                "over. If nothing shows up, make sure the module is enabled in LSPosed with " +
                "Claude and System Framework ticked in its scope."
        )
    }
}
