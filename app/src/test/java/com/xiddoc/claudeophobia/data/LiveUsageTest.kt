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
            LiveUsage.preFetchState(enabled = false, hasCredentials = true, rebootNeeded = false),
        )
    }

    @Test
    fun fetchesWhenCredentialsPresentRegardlessOfReboot() {
        // Credentials prove the module worked, so we proceed to fetch (null) even
        // if a reboot looks pending — clearly it isn't really needed.
        assertNull(LiveUsage.preFetchState(enabled = true, hasCredentials = true, rebootNeeded = true))
    }

    @Test
    fun rebootRequiredWhenNoCredentialsAndUpdatedSinceBoot() {
        val state = LiveUsage.preFetchState(enabled = true, hasCredentials = false, rebootNeeded = true)
        assertTrue(state is UsageResult.RebootRequired)
    }

    @Test
    fun notFoundWhenNoCredentialsButAlreadyRebooted() {
        val state = LiveUsage.preFetchState(enabled = true, hasCredentials = false, rebootNeeded = false)
        assertTrue(state is UsageResult.NotFound)
    }
}
