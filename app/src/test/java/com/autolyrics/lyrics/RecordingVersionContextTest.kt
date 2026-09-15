package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingVersionContextTest {

    @Test
    fun namedRemixInBracketsIsExplicitTitleVersionEvidence() {
        assertEquals(
            setOf("remix"),
            extractTitleVersionQualifiers("I Took a Pill in Ibiza (Seeb Remix)")
        )
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "I Took a Pill in Ibiza (Seeb Remix)",
                candidateTitle = "I Took a Pill in Ibiza"
            )
        )
    }

    @Test
    fun namedRemixAfterSeparatorIsExplicitTitleVersionEvidence() {
        assertEquals(
            setOf("remix"),
            extractTitleVersionQualifiers("Song - Seeb Remix")
        )
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song - Seeb Remix",
                candidateTitle = "Song (Club Remix)"
            )
        )
    }

    @Test
    fun compoundJapaneseTitleQualifiersAreExplicitVersionEvidence() {
        assertEquals(
            setOf("live", "remaster"),
            extractTitleVersionQualifiers("曲名（ライブ・リマスター）")
        )
        assertEquals(
            setOf("live", "remaster"),
            extractTitleVersionQualifiers("曲名（ライブ版・リマスター版）")
        )
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名（ライブ・リマスター）",
                candidateTitle = "曲名"
            )
        )
    }

    @Test
    fun incidentalVersionWordsInBaseTitlesRemainNonVersionEvidence() {
        assertEquals(emptySet<String>(), extractTitleVersionQualifiers("Live Forever"))
        assertEquals(emptySet<String>(), extractTitleVersionQualifiers("Remix to Ignition"))

        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Live Forever",
                candidateTitle = "Live Forever (Remastered)",
                requestedAlbum = "Definitely Maybe (Remastered)",
                candidateAlbum = "Definitely Maybe (Remastered)"
            )
        )
    }

    @Test
    fun explicitCoreVersionConflictsStillReject() {
        assertFalse(LrcLibClient.versionsCompatible("Song (Live)", "Song (Remastered)"))
        assertFalse(LrcLibClient.versionsCompatible("Song (Acoustic)", "Song (Remix)"))
        assertFalse(LrcLibClient.versionsCompatible("Song", "Song (Instrumental)"))
    }
}
