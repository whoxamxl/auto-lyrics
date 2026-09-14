package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun enhancedLrcPreservesFragmentSpacingAndAbsoluteTiming() {
        val lrc = "[00:01.00]<00:01.00>Pro<00:01.10>vi<00:01.20>der <00:01.50>timing"

        val line = LrcParser.parseKaraoke(lrc).single()

        assertEquals(1_000L, line.timeMs)
        assertEquals("Provider timing", line.text)
        assertEquals(listOf("Pro", "vi", "der ", "timing"), line.words.map { it.text })
        assertEquals(listOf(1_000L, 1_100L, 1_200L, 1_500L), line.words.map { it.timeMs })
    }

    @Test
    fun enhancedLrcKeepsJapaneseCharacterTokensWithoutInventingSpaces() {
        val lrc = "[00:03.00]<00:03.00>君<00:03.15>を<00:03.30>忘<00:03.45>れ<00:03.60>な<00:03.75>い"

        val line = LrcParser.parseKaraoke(lrc).single()

        assertEquals("君を忘れない", line.text)
        assertEquals(listOf("君", "を", "忘", "れ", "な", "い"), line.words.map { it.text })
    }

    @Test
    fun enhancedLrcAcceptsColonFractionSeparator() {
        val lrc = "[00:01:25]<00:01:25>Hello <00:01:75>world"

        val line = LrcParser.parseKaraoke(lrc).single()

        assertEquals(1_250L, line.timeMs)
        assertEquals("Hello world", line.text)
        assertEquals(1_750L, line.words[1].timeMs)
    }

    @Test
    fun karaokeParserLeavesLineOnlyLrcWithoutFakeWordTiming() {
        val lines = LrcParser.parseKaraoke("[00:01.00]Line only")

        assertEquals(1, lines.size)
        assertEquals("Line only", lines.single().text)
        assertTrue(lines.single().words.isEmpty())
    }
}
