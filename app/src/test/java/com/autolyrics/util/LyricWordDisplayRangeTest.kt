package com.autolyrics.util

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricWordDisplayRangeTest {

    @Test
    fun englishFragmentsShareWholeDisplayWordRange() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Provider timing works",
            words = listOf(
                LyricWord(1_000L, "Pro"),
                LyricWord(1_100L, "vi"),
                LyricWord(1_200L, "der"),
                LyricWord(1_500L, "timing"),
                LyricWord(2_000L, "works")
            )
        )

        val expected = LyricWordLayout.DisplayRange(0, 8)
        assertEquals(expected, LyricWordLayout.displayRangeForToken(line, 0))
        assertEquals(expected, LyricWordLayout.displayRangeForToken(line, 1))
        assertEquals(expected, LyricWordLayout.displayRangeForToken(line, 2))
    }

    @Test
    fun japaneseCharacterTokensMapToReadableDisplayUnits() {
        val text = "君を忘れない"
        val line = LyricLine(
            timeMs = 1_000L,
            text = text,
            words = text.mapIndexed { index, char ->
                LyricWord(1_000L + index * 150L, char.toString())
            }
        )

        assertEquals(
            LyricWordLayout.DisplayRange(0, 2),
            LyricWordLayout.displayRangeForToken(line, 1)
        )
        assertEquals(
            LyricWordLayout.DisplayRange(2, text.length),
            LyricWordLayout.displayRangeForToken(line, 5)
        )
    }

    @Test
    fun mismatchedTokenCaseFallsBackByLexicalOrder() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Hello world",
            words = listOf(
                LyricWord(1_000L, "HELLO"),
                LyricWord(1_500L, "WORLD")
            )
        )

        assertEquals(
            LyricWordLayout.DisplayRange(0, 5),
            LyricWordLayout.displayRangeForToken(line, 0)
        )
        assertEquals(
            LyricWordLayout.DisplayRange(6, 11),
            LyricWordLayout.displayRangeForToken(line, 1)
        )
    }

    @Test
    fun singleMismatchedTokenFallsBackToWholeVisibleLine() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Hello world",
            words = listOf(LyricWord(1_000L, "HELLO WORLD!"))
        )

        assertEquals(
            LyricWordLayout.DisplayRange(0, 11),
            LyricWordLayout.displayRangeForToken(line, 0)
        )
    }
}
