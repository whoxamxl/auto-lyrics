package com.autolyrics.lyrics

import com.autolyrics.model.LyricWord

/** Shared active-word selection for phone, Performance, and Android Auto karaoke. */
object KaraokeTiming {

    fun activeWordIndex(words: List<LyricWord>, positionMs: Long): Int {
        var latestIndex = -1
        for (index in words.indices) {
            if (words[index].timeMs <= positionMs) {
                latestIndex = index
            } else {
                break
            }
        }

        if (latestIndex < 0) return -1

        val latestWord = words[latestIndex]
        val explicitEnd = latestWord.endTimeMs
        return if (explicitEnd != null && positionMs >= explicitEnd) {
            -1
        } else {
            latestIndex
        }
    }
}
