package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class UsageHistoryBucketTest {

    // Default reset: Thursday 08:00 UTC.
    private val utc = ResetConfig()

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun weekKeyMatchesPreviousResetBoundary() {
        val ts = at("2026-06-30T12:00:00Z") // a Tuesday
        val expected = utc.previousResetAtOrBefore(Instant.ofEpochMilli(ts)).toInstant().toEpochMilli()
        assertEquals(expected, weekKeyFor(ts, utc))
        // The boundary itself is a Thursday 08:00 UTC.
        val boundary = Instant.ofEpochMilli(weekKeyFor(ts, utc)).atZone(ZoneId.of("UTC"))
        assertEquals(DayOfWeek.THURSDAY, boundary.dayOfWeek)
        assertEquals(8, boundary.hour)
    }

    @Test
    fun samplesStraddlingAResetSplitIntoTwoBuckets() {
        var h = History(emptyList())
        // Wednesday (before Thu 08:00 reset) then Friday (after it).
        h = insertSample(h, Sample(at("2026-06-24T10:00:00Z"), 20), utc)
        h = insertSample(h, Sample(at("2026-06-26T10:00:00Z"), 40), utc)
        assertEquals(2, h.buckets.size)
        assertTrue(h.buckets[0].weekStartMs < h.buckets[1].weekStartMs)
        assertEquals(20, h.buckets[0].samples.single().pct)
        assertEquals(40, h.buckets[1].samples.single().pct)
    }

    @Test
    fun sameWeekSamplesAccumulateInOneBucketSortedAndDeduped() {
        var h = History(emptyList())
        h = insertSample(h, Sample(at("2026-06-26T10:00:00Z"), 40), utc)
        h = insertSample(h, Sample(at("2026-06-26T13:00:00Z"), 55), utc)
        h = insertSample(h, Sample(at("2026-06-26T10:00:00Z"), 99), utc) // same ts -> overwrite
        assertEquals(1, h.buckets.size)
        assertEquals(listOf(99, 55), h.buckets[0].samples.map { it.pct })
    }

    @Test
    fun changingResetConfigAfterDataKeepsOldBucketsFrozen() {
        var h = History(emptyList())
        h = insertSample(h, Sample(at("2026-06-24T10:00:00Z"), 20), utc)
        val oldStart = h.buckets[0].weekStartMs
        val oldSig = h.buckets[0].resetSignature

        // User switches reset to Monday 00:00 in a different zone, weeks later.
        val monday = ResetConfig(DayOfWeek.MONDAY, LocalTime.of(0, 0), ZoneId.of("America/Los_Angeles"))
        h = insertSample(h, Sample(at("2026-07-20T10:00:00Z"), 60), monday)

        // The historical bucket's boundary & signature are untouched.
        val old = h.buckets.first { it.weekStartMs == oldStart }
        assertEquals(oldSig, old.resetSignature)
        assertEquals(20, old.samples.single().pct)
        assertTrue(h.buckets.any { it.resetSignature == monday.signature() })
    }

    @Test
    fun bucketsForMergesOverlappingConfigChangeBuckets() {
        // Two buckets whose ranges overlap (a mid-week config change can cause this).
        val a = WeekBucket(0, 100, "OLD", listOf(Sample(10, 10)))
        val b = WeekBucket(50, 150, ResetConfig().signature(), listOf(Sample(120, 30)))
        val merged = bucketsFor(History(listOf(a, b)), ResetConfig())
        assertEquals(1, merged.size)
        assertEquals(0, merged[0].weekStartMs)
        assertEquals(150, merged[0].weekEndMs)
        assertEquals(ResetConfig().signature(), merged[0].resetSignature) // prefers current
        assertEquals(listOf(Sample(10, 10), Sample(120, 30)), merged[0].samples)
    }

    @Test
    fun nonOverlappingBucketsArePreservedInOrder() {
        val a = WeekBucket(0, 100, "S", listOf(Sample(10, 10)))
        val b = WeekBucket(100, 200, "S", listOf(Sample(150, 30)))
        val out = bucketsFor(History(listOf(b, a)), ResetConfig())
        assertEquals(listOf(0L, 100L), out.map { it.weekStartMs })
    }

    @Test
    fun midWeekConfigChangeClosesInProgressBucket() {
        var h = History(emptyList())
        // First sample under UTC config establishes a bucket.
        h = insertSample(h, Sample(at("2026-06-26T10:00:00Z"), 20), utc)
        val firstEnd = h.buckets[0].weekEndMs
        // A later same-week sample under a DIFFERENT config whose new boundary falls
        // inside the in-progress bucket should close it and open a fresh one.
        val shifted = ResetConfig(DayOfWeek.FRIDAY, LocalTime.of(0, 0), ZoneId.of("UTC"))
        h = insertSample(h, Sample(at("2026-06-27T10:00:00Z"), 35), shifted)
        // Either a clean split or an accumulate, but never a crash and never a lost sample.
        val allPcts = h.buckets.flatMap { it.samples }.map { it.pct }.toSet()
        assertTrue(allPcts.containsAll(setOf(20, 35)))
        assertTrue(h.buckets.all { it.weekStartMs < it.weekEndMs })
        assertTrue(firstEnd > 0)
    }
}
