package com.xiddoc.claudeophobia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingTest {

    @Test
    fun deltaIsActualMinusElapsed() {
        assertEquals(12, Pacing.delta(actualPercent = 79, elapsedPercent = 67))
        assertEquals(-20, Pacing.delta(actualPercent = 40, elapsedPercent = 60))
        assertEquals(0, Pacing.delta(actualPercent = 50, elapsedPercent = 50))
    }

    @Test
    fun statusUsesTheToleranceBand() {
        // Just inside the band either way reads as on pace.
        assertEquals(Pacing.Status.ON_PACE, Pacing.status(Pacing.ON_PACE_TOLERANCE))
        assertEquals(Pacing.Status.ON_PACE, Pacing.status(-Pacing.ON_PACE_TOLERANCE))
        // One step past the band tips into ahead / under.
        assertEquals(Pacing.Status.AHEAD, Pacing.status(Pacing.ON_PACE_TOLERANCE + 1))
        assertEquals(Pacing.Status.UNDER, Pacing.status(-Pacing.ON_PACE_TOLERANCE - 1))
    }

    @Test
    fun shortLabelNeverShowsNegativeNumbers() {
        assertEquals("12% ahead of pace", Pacing.shortLabel(12))
        assertEquals("20% under pace", Pacing.shortLabel(-20))
        assertEquals("on pace", Pacing.shortLabel(0))
    }

    @Test
    fun verdictMatchesStatus() {
        assertTrue(Pacing.verdict(20).contains("ahead of pace"))
        assertTrue(Pacing.verdict(-20).contains("under pace"))
        assertTrue(Pacing.verdict(0).contains("on pace", ignoreCase = true))
    }

    @Test
    fun glyphMatchesStatus() {
        assertEquals(Pacing.glyph(20), Pacing.glyph(99))
        assertEquals(Pacing.glyph(-20), Pacing.glyph(-99))
        // On-pace glyph is distinct from the ahead glyph.
        assertTrue(Pacing.glyph(0) != Pacing.glyph(20))
    }
}
