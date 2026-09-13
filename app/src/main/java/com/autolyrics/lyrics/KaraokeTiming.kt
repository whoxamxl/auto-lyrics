package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord

/**
 * Shared timing/layout helpers for word-synchronized lyrics.
 *
 * PetitLyrics WSY supplies explicit word start/end times and preserves spacing in
 * wordstring values. Legacy enhanced-LRC parsing instead reconstructs a line by
 * inserting spaces between word tokens. Keep both representations usable so UI
 * code does not need provider-specific branches.
 */
object KaraokeTiming {

    fun activeWordIndex(words: List<LyricWord>, positionMs: Long): Int {
        var activeIndex = -1
        var latestStart = Long.MIN_VALUE

        words.forEachIndexed { index, word ->
            if (word.timeMs > positionMs) return@forEachIndexed

            val endTimeMs = word.endTimeMs
            if (endTimeMs != null && positionMs >= endTimeMs) {
                return@forEachIndexed
            }

            if (word.timeMs >= latestStart) {
                latestStart = word.timeMs
                activeIndex = index
            }
        }

        return activeIndex
    }

    fun separatorFor(line: LyricLine): String {
        if (line.words.size <= 1) return ""

        val exact = line.words.joinToString(separator = "") { it.text }
        if (exact == line.text) return ""

        val spaced = line.words.joinToString(separator = " ") { it.text }
        if (spaced == line.text) return " "

        // Unknown word-sync formats should not invent whitespace. The canonical
        // full line remains available in LyricLine.text as a fallback display.
        return ""
    }
}
