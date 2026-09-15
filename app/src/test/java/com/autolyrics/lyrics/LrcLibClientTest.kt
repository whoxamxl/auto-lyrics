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
    fun exactContributorComponentCanMatchCompositeArtistMetadata() {
        val score = LrcLibClient.artistSimilarity(
            left = "Alan Menken, Howard Ashman, Samuel E. Wright, Disney",
            right = "Samuel E. Wright",
            allowContributorComponents = true
        )

        assertEquals(0.95, score, 0.0001)
    }

    @Test
    fun contributorComponentsAreNotUsedForWeakTitleMatches() {
        val score = LrcLibClient.artistSimilarity(
            left = "Alan Menken, Howard Ashman, Samuel E. Wright, Disney",
            right = "Samuel E. Wright",
            allowContributorComponents = false
        )

        assertTrue(score < 0.95)
    }

    @Test
    fun liveVersionDoesNotMatchStudioVersion() {
        assertFalse(LrcLibClient.versionsCompatible("Song (Live)", "Song"))
        assertFalse(LrcLibClient.versionsCompatible("Song", "Song - Live"))
    }

    @Test
    fun albumVersionEvidenceCanCorroborateCandidateTitle() {
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song",
                candidateTitle = "Song (Live)",
                requestedAlbum = "Live at Wembley"
            )
        )
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song",
                candidateTitle = "Song (Live)",
                requestedAlbum = "Album: Live"
            )
        )
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song",
                candidateTitle = "Song (Live)",
                requestedAlbum = "Album: Subtitle: Live"
            )
        )
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song (Remastered)",
                candidateTitle = "Song",
                candidateAlbum = "Album: Remastered"
            )
        )
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song (Live Remastered)",
                candidateTitle = "Song",
                candidateAlbum = "Album: Live: Remastered"
            )
        )
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song (Remastered)",
                candidateTitle = "Song",
                candidateAlbum = "Live Through This: Remastered"
            )
        )
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song (Remastered)",
                candidateTitle = "Song",
                candidateAlbum = "Album (Remastered)"
            )
        )
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song (Remastered)",
                candidateTitle = "Song",
                candidateAlbum = "Album (Deluxe Remastered Edition)"
            )
        )
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song (Remastered)",
                candidateTitle = "Song",
                candidateAlbum = "Album (20th Anniversary Remastered Edition)"
            )
        )
    }

    @Test
    fun albumEvidenceDoesNotEraseExplicitTitleVersionConflict() {
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song (Live)",
                candidateTitle = "Song (Remastered)",
                requestedAlbum = "Album (Live Remastered Edition)",
                candidateAlbum = "Album (Live Remastered Edition)"
            )
        )
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song (Live)",
                candidateTitle = "Song (Live Remastered)",
                requestedAlbum = "Album (Remastered Edition)",
                candidateAlbum = "Album (Remastered Edition)"
            )
        )
    }

    @Test
    fun explicitVersionSegmentBeforeOrdinaryTrailingSubtitleIsNotEvidence() {
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song",
                candidateTitle = "Song (Live)",
                requestedAlbum = "Album: Live: Subtitle"
            )
        )
    }

    @Test
    fun japaneseAnniversaryRemasterEvidenceIsRecognized() {
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名（リマスター）",
                candidateTitle = "曲名",
                candidateAlbum = "アルバム（20周年リマスター版）"
            )
        )
    }

    @Test
    fun japaneseCompoundAlbumVersionSuffixesAreRecognized() {
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名",
                candidateTitle = "曲名（ライブ・リマスター）",
                requestedAlbum = "アルバム: ライブ版: リマスター版"
            )
        )
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名",
                candidateTitle = "曲名（ライブ・リマスター）",
                requestedAlbum = "ライブ版: リマスター版"
            )
        )
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名",
                candidateTitle = "曲名（ライブ）",
                requestedAlbum = "アルバム: ライブ版: サブタイトル"
            )
        )
    }

    @Test
    fun ordinaryAlbumNameContainingLiveIsNotVersionEvidence() {
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song",
                candidateTitle = "Song (Live)",
                requestedAlbum = "Live Through This"
            )
        )
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song",
                candidateTitle = "Song (Live)",
                requestedAlbum = "Album - Live Through This"
            )
        )
    }

    @Test
    fun ordinaryBracketedAlbumSubtitleContainingLiveIsNotVersionEvidence() {
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song",
                candidateTitle = "Song (Live)",
                requestedAlbum = "Album (We Live Here)"
            )
        )
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song",
                candidateTitle = "Song (Live)",
                requestedAlbum = "Album (We Live Here — Deluxe Edition)"
            )
        )
    }

    @Test
    fun mixedScriptProseAroundEnglishMarkerIsNotVersionEvidence() {
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名",
                candidateTitle = "曲名（ライブ）",
                requestedAlbum = "アルバム - LIVE・ドア"
            )
        )
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名",
                candidateTitle = "曲名（ライブ）",
                requestedAlbum = "アルバム（LIVE・ドア）"
            )
        )
    }

    @Test
    fun fullWidthAlbumVersionPunctuationIsRecognized() {
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名",
                candidateTitle = "曲名（ライブ）",
                requestedAlbum = "アルバム（ライブ）"
            )
        )
    }

    @Test
    fun japaneseAlbumVersionSuffixIsRecognized() {
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名",
                candidateTitle = "曲名（ライブ）",
                requestedAlbum = "アルバム - ライブ版"
            )
        )
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名",
                candidateTitle = "曲名（ライブ）",
                requestedAlbum = "アルバム - ライブバージョン"
            )
        )
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "曲名",
                candidateTitle = "曲名（ライブ）",
                requestedAlbum = "アルバム - ライブドア"
            )
        )
    }

    @Test
    fun matchingTitleVersionsIgnoreExtraAlbumEditionMarker() {
        assertTrue(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song",
                candidateTitle = "Song",
                requestedAlbum = "Album",
                candidateAlbum = "Album (Remastered)"
            )
        )
    }

    @Test
    fun albumVersionEvidenceStillRejectsDifferentRecording() {
        assertFalse(
            LrcLibClient.versionsCompatible(
                requestedTitle = "Song",
                candidateTitle = "Song (Live)",
                requestedAlbum = "Studio Album",
                candidateAlbum = "Live at Wembley"
            )
        )
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
