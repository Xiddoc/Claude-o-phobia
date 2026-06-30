package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageHistoryCodecTest {

    private fun bucket(start: Long, end: Long, sig: String, vararg s: Pair<Long, Int>) =
        WeekBucket(start, end, sig, s.map { Sample(it.first, it.second) })

    @Test
    fun roundTripsMultipleBucketsExactly() {
        val history = History(
            listOf(
                bucket(1_000, 8_000, "4@8:0@UTC", 1_000L to 10, 4_000L to 25),
                bucket(8_000, 15_000, "4@8:0@UTC", 9_000L to 30, 14_000L to 80),
            )
        )
        val decoded = HistoryCodec.decode(HistoryCodec.encode(history))
        assertEquals(history, decoded)
    }

    @Test
    fun decodeOfEmptyOrBlankIsEmptyHistory() {
        assertTrue(HistoryCodec.decode("").isEmpty)
        assertTrue(HistoryCodec.decode("   \n  \n").isEmpty)
    }

    @Test
    fun decodeSkipsGarbageLinesWithoutThrowing() {
        val text = buildString {
            append("not-a-bucket\n")
            append("1|2\n")                                   // too few fields
            append("abc|2|4@8:0@UTC|1,5\n")                   // bad start
            append("1000|8000|4@8:0@UTC|1000,10;oops;4000,bad;4000,25\n") // some bad tokens
            append("\n")
        }
        val decoded = HistoryCodec.decode(text)
        assertEquals(1, decoded.buckets.size)
        // Only the two well-formed samples survive; "oops" and the non-numeric pct drop.
        assertEquals(listOf(Sample(1000, 10), Sample(4000, 25)), decoded.buckets[0].samples)
    }

    @Test
    fun decodeClampsPercentIntoRange() {
        val decoded = HistoryCodec.decode("1000|8000|4@8:0@UTC|1000,250;2000,-5")
        assertEquals(listOf(Sample(1000, 100), Sample(2000, 0)), decoded.buckets[0].samples)
    }

    @Test
    fun decodeKeepsSamplesSortedAndDeduped() {
        val decoded = HistoryCodec.decode("1000|8000|4@8:0@UTC|4000,40;1000,10;4000,55")
        // sorted ascending; the later 4000,55 wins over 4000,40.
        assertEquals(listOf(Sample(1000, 10), Sample(4000, 55)), decoded.buckets[0].samples)
    }

    @Test
    fun zoneIdsWithSlashesSurviveTheLineSplit() {
        val history = History(listOf(bucket(1, 9, "1@8:30@America/Los_Angeles", 2L to 7)))
        assertEquals(history, HistoryCodec.decode(HistoryCodec.encode(history)))
    }

    @Test
    fun tenThousandSamplesStayWellUnderAMegabyte() {
        val samples = (0 until 10_000).map { Sample(it * 10_800_000L, it % 101) }
        val text = HistoryCodec.encode(History(listOf(bucket(0, Long.MAX_VALUE, "4@8:0@UTC").copy(samples = samples))))
        assertTrue("blob was ${text.length} bytes", text.length < 1_000_000)
        assertEquals(samples, HistoryCodec.decode(text).buckets[0].samples)
    }

    @Test
    fun signatureDistinguishesConfigsAndIgnoresEqualOnes() {
        val a = ResetConfig()
        assertEquals(a.signature(), a.copy().signature())
        assertNotEquals(a.signature(), a.copy(time = a.time.plusHours(1)).signature())
        assertTrue('|' !in a.signature())
    }
}
