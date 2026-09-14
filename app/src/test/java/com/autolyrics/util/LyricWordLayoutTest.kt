package com.autolyrics.util

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricWordLayoutTest {

    @Test
    fun reconstructsEnglishSpacesFromOriginalLine() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Hey Jude",
            words = listOf(
                LyricWord(1_000L, "Hey"),
                LyricWord(1_500L, "Jude")
            )
        )

        val layout = LyricWordLayout.layout(line)

        assertEquals(listOf("", " "), layout.prefixes)
        assertEquals("", layout.suffix)
        assertEquals("Hey 【Jude】", LyricWordLayout.karaokeText(line, 1))
    }

    @Test
    fun keepsJapaneseChunksAdjacent() {
        val line = LyricLine(
            timeMs = 2_000L,
            text = "君を忘れない",
            words = listOf(
                LyricWord(2_000L, "君を"),
                LyricWord(2_800L, "忘れない")
            )
        )

        val layout = LyricWordLayout.layout(line)

        assertEquals(listOf("", ""), layout.prefixes)
        assertEquals("君を【忘れない】", LyricWordLayout.karaokeText(line, 1))
    }

    @Test
    fun preservesMixedScriptSeparators() {
        val line = LyricLine(
            timeMs = 3_000L,
            text = "君と世界 I love you",
            words = listOf(
                LyricWord(3_000L, "君と"),
                LyricWord(3_300L, "世界"),
                LyricWord(3_600L, "I"),
                LyricWord(3_900L, "love"),
                LyricWord(4_200L, "you")
            )
        )

        val layout = LyricWordLayout.layout(line)

        assertEquals(listOf("", "", " ", " ", " "), layout.prefixes)
        assertEquals("君と世界 【I】 love you", LyricWordLayout.karaokeText(line, 2))
    }

    @Test
    fun fallsBackToLanguageAppropriateSeparatorWhenAlignmentFails() {
        val english = LyricLine(
            timeMs = 0L,
            text = "different line text",
            words = listOf(LyricWord(0L, "one"), LyricWord(1L, "two"))
        )
        val japanese = LyricLine(
            timeMs = 0L,
            text = "別テキスト",
            words = listOf(LyricWord(0L, "一"), LyricWord(1L, "二"))
        )

        assertEquals("one 【two】", LyricWordLayout.karaokeText(english, 1))
        assertEquals("一【二】", LyricWordLayout.karaokeText(japanese, 1))
    }
}
