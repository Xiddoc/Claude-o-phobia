package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUsageTest {

    @Test
    fun disabledWhenToggleOff() {
        assertEquals(
            UsageResult.Disabled,
            LiveUsage.preFetchState(enabled = false, hasCredentials = true, moduleActive = true),
        )
    }

    @Test
    fun fetchesWhenCredentialsPresentRegardlessOfModuleProbe() {
        // Credentials prove the module worked, so we proceed to fetch (null) even
        // if the in-app module probe couldn't confirm activation.
        assertNull(LiveUsage.preFetchState(enabled = true, hasCredentials = true, moduleActive = false))
    }

    @Test
    fun notFoundWhenActiveButNoCredentialsYet() {
        val state = LiveUsage.preFetchState(enabled = true, hasCredentials = false, moduleActive = true)
        assertTrue(state is UsageResult.NotFound)
    }

    @Test
    fun moduleInactiveWhenNoCredentialsAndProbeFalse() {
        assertEquals(
            UsageResult.ModuleInactive,
            LiveUsage.preFetchState(enabled = true, hasCredentials = false, moduleActive = false),
        )
    }
}
