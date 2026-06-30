package com.xiddoc.claudeophobia.notify

import com.xiddoc.claudeophobia.data.UsageHistoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySamplerTest {

    private val fifteenMin = 15L * 60 * 1000

    @Test
    fun nextTriggerIsOneIntervalOutAndStrictlyFuture() {
        val now = 1_700_000_000_000L
        assertEquals(now + fifteenMin, HistorySampler.nextTriggerMillis(now, fifteenMin))
        assertTrue(HistorySampler.nextTriggerMillis(now, fifteenMin) > now)
        // A degenerate sub-minute interval is floored to a minute so it's always future.
        assertTrue(HistorySampler.nextTriggerMillis(now, 0) > now)
        // Holds for a spread of seeds and intervals.
        for (t in listOf(0L, 1L, 999_999L)) {
            for (iv in listOf(5L, 30L, 60L).map { it * 60_000 }) {
                assertTrue(HistorySampler.nextTriggerMillis(t, iv) > t)
            }
        }
    }

    @Test
    fun shouldAppendAlwaysAllowsTheFirstSample() {
        assertTrue(UsageHistoryRepository.shouldAppend(null, 1_000, fifteenMin))
    }

    @Test
    fun shouldAppendThrottlesASecondSampleWithinHalfTheInterval() {
        val last = 1_000_000L
        // Half the cadence has not elapsed -> reject (guards a boot/manual double-fire).
        assertFalse(UsageHistoryRepository.shouldAppend(last, last + fifteenMin / 2 - 1, fifteenMin))
        // Exactly half elapsed -> accept.
        assertTrue(UsageHistoryRepository.shouldAppend(last, last + fifteenMin / 2, fifteenMin))
        // Well past the cadence -> accept.
        assertTrue(UsageHistoryRepository.shouldAppend(last, last + fifteenMin, fifteenMin))
    }

    @Test
    fun shouldAppendScalesWithTheConfiguredInterval() {
        val fiveMin = 5L * 60 * 1000
        val last = 1_000_000L
        // The same 4-minute gap is throttled at a 15-min cadence but allowed at 5-min.
        val fourMin = 4L * 60 * 1000
        assertFalse(UsageHistoryRepository.shouldAppend(last, last + fourMin, fifteenMin))
        assertTrue(UsageHistoryRepository.shouldAppend(last, last + fourMin, fiveMin))
    }
}
