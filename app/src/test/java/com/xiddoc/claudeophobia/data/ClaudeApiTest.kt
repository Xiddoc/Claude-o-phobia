package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClaudeApiTest {

    // Shape of the real /usage response (no secrets — just usage figures).
    private val sample = """
        {"five_hour":{"utilization":0.0,"resets_at":null},
         "seven_day":{"utilization":15.0,"resets_at":"2026-06-18T05:00:00.119296+00:00"},
         "seven_day_sonnet":{"utilization":0.0,"resets_at":null},
         "extra_usage":{"is_enabled":false}}
    """.trimIndent()

    @Test
    fun parsesWeeklyUtilizationAndReset() {
        val snap = ClaudeApi.parseUsage(sample)
        assertEquals(15.0, snap.weeklyUtilizationPercent!!, 0.0001)
        assertEquals(
            Instant.parse("2026-06-18T05:00:00.119296Z").toEpochMilli(),
            snap.weeklyResetEpochMs!!,
        )
    }

    @Test
    fun fiveHourWindowMayBeInactive() {
        val snap = ClaudeApi.parseUsage(sample)
        assertEquals(0.0, snap.fiveHourUtilizationPercent!!, 0.0001)
        assertNull(snap.fiveHourResetEpochMs)
    }

    @Test
    fun snapshotReportsItHasData() {
        assertTrue(ClaudeApi.parseUsage(sample).hasAnything)
    }

    @Test
    fun pullsDeviceIdOutOfCookieHeader() {
        val header = "sessionKey=sk-ant-sid02-abc; anthropic-device-id=3adb5dda-7c2a-41ae; lastActiveOrg=df0d"
        assertEquals("3adb5dda-7c2a-41ae", ClaudeApi.deviceIdFrom(header))
    }

    @Test
    fun deviceIdAbsentOrBlankYieldsNull() {
        assertNull(ClaudeApi.deviceIdFrom("sessionKey=sk-ant; lastActiveOrg=df0d"))
        assertNull(ClaudeApi.deviceIdFrom("anthropic-device-id=; sessionKey=sk-ant"))
    }
}
