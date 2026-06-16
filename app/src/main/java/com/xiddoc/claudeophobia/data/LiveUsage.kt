package com.xiddoc.claudeophobia.data

/**
 * Maps the persisted "last thing the LSPosed module told us" plus the live
 * module-active signal onto a [UsageResult] for the UI. Pure and Android-free so
 * it can be unit-tested directly (see `LiveUsageTest`).
 */
object LiveUsage {

    fun resolve(
        enabled: Boolean,
        moduleActive: Boolean,
        capturedAtMs: Long,
        ok: Boolean,
        error: String?,
        snapshot: UsageSnapshot,
    ): UsageResult = when {
        !enabled -> UsageResult.Disabled
        !moduleActive -> UsageResult.ModuleInactive
        capturedAtMs <= 0L -> UsageResult.NotFound(
            "The module is active — open the Claude app once so it can capture your usage."
        )
        !ok -> UsageResult.Error(
            error ?: "The module couldn't read your usage from Claude."
        )
        else -> UsageResult.Found(snapshot)
    }
}
