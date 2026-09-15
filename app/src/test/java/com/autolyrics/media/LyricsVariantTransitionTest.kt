package com.autolyrics.media

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsVariantTransitionTest {

    @Test
    fun clearsTranslationButPreservesCurrentLyricsState() {
        val state = LyricsState(
            lines = listOf(LyricLine(1_000L, "Original")),
            currentIndex = 0,
            status = LyricsStatus.FOUND,
            source = "LRCLIB · Synced",
            translatedLines = listOf("Translated"),
            detectedLanguage = "ja"
        )

        val cleared = clearTranslationForLyricsVariantSwitch(state)

        assertNull(cleared.translatedLines)
        assertNull(cleared.detectedLanguage)
        assertEquals(state.lines, cleared.lines)
        assertEquals(state.currentIndex, cleared.currentIndex)
        assertEquals(state.status, cleared.status)
        assertEquals(state.source, cleared.source)
    }
}
