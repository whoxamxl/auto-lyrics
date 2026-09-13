package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class KaraokeTimingTest {

    @Test
    fun explicitEndTimesLeaveGapsUnhighlighted() {
        val words = listOf(
            LyricWord(timeMs = 1_000L, text = "A", endTimeMs = 1_400L),
            LyricWord(timeMs = 1_800L, text = "B", endTimeMs = 2_200L)
        )

        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 999L))
        assertEquals(0, KaraokeTiming.activeWordIndex(words, 1_000L))
        assertEquals(0, KaraokeTiming.activeWordIndex(words, 1_399L))
        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 1_400L))
        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 1_799L))
        assertEquals(1, KaraokeTiming.activeWordIndex(words, 1_800L))
        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 2_200L))
    }

    @Test
    fun missingEndTimeFallsBackToLatestStartedWord() {
        val words = listOf(
            LyricWord(timeMs = 1_000L, text = "A"),
            LyricWord(timeMs = 2_000L, text = "B")
        )

        assertEquals(0, KaraokeTiming.activeWordIndex(words, 1_500L))
        assertEquals(1, KaraokeTiming.activeWordIndex(words, 2_500L))
    }

    @Test
    fun providerPreservedSpacingUsesNoInjectedSeparator() {
        val line = LyricLine(
            timeMs = 0L,
            text = "Alpha line",
            words = listOf(
                LyricWord(0L, "Alpha "),
                LyricWord(500L, "line")
            )
        )

        assertEquals("", KaraokeTiming.separatorFor(line))
    }

    @Test
    fun legacyTokenizedLyricsCanStillUseSpaces() {
        val line = LyricLine(
            timeMs = 0L,
            text = "Alpha line",
            words = listOf(
                LyricWord(0L, "Alpha"),
                LyricWord(500L, "line")
            )
        )

        assertEquals(" ", KaraokeTiming.separatorFor(line))
    }
}
