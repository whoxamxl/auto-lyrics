package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcLibClientTest {

    @Test
    fun fullWidthMetadataNormalizesToSameText() {
        assertEquals(1.0, LrcLibClient.stringSimilarity("ＡＢＣ　１２３", "abc 123"), 0.0001)
    }

    @Test
    fun commonArtistSeparatorsNormalizeConsistently() {
        assertEquals(
            1.0,
            LrcLibClient.stringSimilarity("Fabolous & Jeremih", "Fabolous, Jeremih"),
            0.0001
        )
    }

    @Test
    fun liveVersionDoesNotMatchStudioVersion() {
        assertFalse(LrcLibClient.versionsCompatible("Song (Live)", "Song"))
        assertFalse(LrcLibClient.versionsCompatible("Song", "Song - Live"))
    }

    @Test
    fun differentLiveLabelsRemainCompatible() {
        assertTrue(LrcLibClient.versionsCompatible("Song (Live at Wembley)", "Song - Live"))
    }

    @Test
    fun acousticAndRemixDoNotMatchBaseOrEachOther() {
        assertFalse(LrcLibClient.versionsCompatible("Song (Acoustic)", "Song"))
        assertFalse(LrcLibClient.versionsCompatible("Song (Remix)", "Song (Remastered)"))
    }

    @Test
    fun japaneseVersionMarkersAreRecognized() {
        assertFalse(LrcLibClient.versionsCompatible("曲名（ライブ）", "曲名"))
        assertTrue(LrcLibClient.versionsCompatible("曲名（ライブ）", "曲名 - ライブ版"))
    }

    @Test
    fun instrumentalFlagPreventsVocalMismatch() {
        assertFalse(LrcLibClient.versionsCompatible("Song", "Song", candidateInstrumental = true))
        assertTrue(LrcLibClient.versionsCompatible("Song (Instrumental)", "Song", candidateInstrumental = true))
    }

    @Test
    fun durationScoringHasExpectedBoundaries() {
        assertEquals(1.0, LrcLibClient.durationSimilarity(240, 241.5)!!, 0.0001)
        assertEquals(0.60, LrcLibClient.durationSimilarity(240, 249.0)!!, 0.0001)
        assertEquals(0.35, LrcLibClient.durationSimilarity(240, 255.0)!!, 0.0001)
        assertEquals(-1.0, LrcLibClient.durationSimilarity(240, 256.0)!!, 0.0001)
        assertNull(LrcLibClient.durationSimilarity(0, 240.0))
    }
}
