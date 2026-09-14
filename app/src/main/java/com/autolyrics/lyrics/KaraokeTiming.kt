package com.autolyrics.lyrics

import com.autolyrics.model.LyricWord

/** Shared active-word selection for phone, Performance, and Android Auto karaoke. */
object KaraokeTiming {

    fun activeWordIndex(words: List<LyricWord>, positionMs: Long): Int {
        var activeIndex = -1
        var latestStart = Long.MIN_VALUE

        words.forEachIndexed { index, word ->
            if (word.timeMs > positionMs) return@forEachIndexed

            val explicitEnd = word.endTimeMs
            if (explicitEnd != null && positionMs >= explicitEnd) {
                return@forEachIndexed
            }

            if (word.timeMs >= latestStart) {
                latestStart = word.timeMs
                activeIndex = index
            }
        }

        return activeIndex
    }
}
