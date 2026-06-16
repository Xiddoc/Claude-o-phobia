package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUsageTest {

    private val snapshot = UsageSnapshot(weeklyUtilizationPercent = 42.0, sourceLabel = "LSPosed")

    private fun resolve(
        enabled: Boolean = true,
        moduleActive: Boolean = true,
        capturedAtMs: Long = 1_000L,
        ok: Boolean = true,
        error: String? = null,
        snap: UsageSnapshot = snapshot,
    ) = LiveUsage.resolve(enabled, moduleActive, capturedAtMs, ok, error, snap)

    @Test
    fun disabledWhenToggleOff() {
        assertEquals(UsageResult.Disabled, resolve(enabled = false))
    }

    @Test
    fun moduleInactiveTakesPrecedenceOverData() {
        // Even with a captured snapshot, an inactive module is reported first.
        assertEquals(UsageResult.ModuleInactive, resolve(moduleActive = false))
    }

    @Test
    fun notFoundWhenActiveButNothingCapturedYet() {
        assertTrue(resolve(capturedAtMs = 0L) is UsageResult.NotFound)
    }

    @Test
    fun errorSurfacesTheModulesDetail() {
        val result = resolve(ok = false, error = "boom")
        assertEquals(UsageResult.Error("boom"), result)
    }

    @Test
    fun errorFallsBackToGenericMessage() {
        val result = resolve(ok = false, error = null)
        assertTrue(result is UsageResult.Error)
    }

    @Test
    fun foundReturnsTheSnapshot() {
        assertEquals(UsageResult.Found(snapshot), resolve())
    }
}
