package com.xiddoc.claudeophobia.notify

import com.xiddoc.claudeophobia.data.UsageHistoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySamplerTest {

    @Test
    fun nextTriggerIsExactlyThreeHoursOutAndStrictlyFuture() {
        val now = 1_700_000_000_000L
        assertEquals(now + 3L * 60 * 60 * 1000, HistorySampler.nextTriggerMillis(now))
        assertTrue(HistorySampler.nextTriggerMillis(now) > now)
        // Holds for a spread of seeds.
        for (t in listOf(0L, 1L, 999_999L, Long.MAX_VALUE - HistorySampler.SAMPLE_INTERVAL_MS)) {
            assertTrue(HistorySampler.nextTriggerMillis(t) > t)
        }
    }

    @Test
    fun shouldAppendAlwaysAllowsTheFirstSample() {
        assertTrue(UsageHistoryRepository.shouldAppend(null, 1_000, HistorySampler.SAMPLE_INTERVAL_MS))
    }

    @Test
    fun shouldAppendThrottlesASecondSampleWithinHalfTheInterval() {
        val interval = HistorySampler.SAMPLE_INTERVAL_MS
        val last = 1_000_000L
        // Half the cadence has not elapsed -> reject (guards a boot/manual double-fire).
        assertFalse(UsageHistoryRepository.shouldAppend(last, last + interval / 2 - 1, interval))
        // Exactly half elapsed -> accept.
        assertTrue(UsageHistoryRepository.shouldAppend(last, last + interval / 2, interval))
        // Well past the cadence -> accept.
        assertTrue(UsageHistoryRepository.shouldAppend(last, last + interval, interval))
    }
}
