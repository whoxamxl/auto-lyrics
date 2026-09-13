package com.autolyrics.util

import com.autolyrics.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncCalibrationTest {

    @Test
    fun upcomingTimedLineIndices_returnsThreeDistinctFutureLines() {
        val lines = listOf(
            LyricLine(1000, "a"),
            LyricLine(2000, "b"),
            LyricLine(3000, "c"),
            LyricLine(4000, "d"),
            LyricLine(5000, "e")
        )

        assertEquals(listOf(2, 3, 4), SyncCalibration.upcomingTimedLineIndices(lines, 1))
    }

    @Test
    fun upcomingTimedLineIndices_doesNotRepeatLastLineAtTrackEnd() {
        val lines = listOf(
            LyricLine(1000, "a"),
            LyricLine(2000, "b"),
            LyricLine(3000, "c")
        )

        assertEquals(listOf(2), SyncCalibration.upcomingTimedLineIndices(lines, 1))
    }

    @Test
    fun upcomingTimedLineIndices_skipsUntimedLines() {
        val lines = listOf(
            LyricLine(0, "untimed"),
            LyricLine(1000, "a"),
            LyricLine(0, "untimed again"),
            LyricLine(2000, "b"),
            LyricLine(3000, "c")
        )

        assertEquals(listOf(1, 3, 4), SyncCalibration.upcomingTimedLineIndices(lines, -1))
    }

    @Test
    fun offsetForTap_usesSameSignConventionAsPhoneSync() {
        assertEquals(-500L, SyncCalibration.offsetForTap(targetTimeMs = 10_000L, rawPositionMs = 10_500L))
        assertEquals(500L, SyncCalibration.offsetForTap(targetTimeMs = 10_500L, rawPositionMs = 10_000L))
    }
}
