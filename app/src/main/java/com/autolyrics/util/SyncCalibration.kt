package com.autolyrics.util

import com.autolyrics.model.LyricLine

object SyncCalibration {
    const val REQUIRED_TAPS = 3

    fun upcomingTimedLineIndices(
        lines: List<LyricLine>,
        currentIndex: Int,
        count: Int = REQUIRED_TAPS
    ): List<Int> {
        if (count <= 0 || lines.isEmpty()) return emptyList()

        val startIndex = (currentIndex + 1).coerceAtLeast(0)
        if (startIndex >= lines.size) return emptyList()

        return (startIndex until lines.size)
            .asSequence()
            .filter { lines[it].timeMs > 0L }
            .take(count)
            .toList()
    }

    fun offsetForTap(targetTimeMs: Long, rawPositionMs: Long): Long {
        return targetTimeMs - rawPositionMs
    }
}
