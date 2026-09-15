package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsProviderVersionContextTest {

    @Test
    fun finalResolverAcceptsExplicitAlbumEvidenceForTitleVersionDifference() {
        val track = TrackInfo(
            title = "Song",
            artist = "Artist",
            album = "Live at Wembley",
            durationMs = 200_000L
        )
        val candidate = LyricsProviderCandidate(
            provider = "LRCLIB",
            title = "Song (Live)",
            artist = "Artist",
            album = "Live at Wembley",
            durationSec = 200.0,
            lines = listOf(LyricLine(1_000L, "line")),
            status = LyricsStatus.FOUND,
            source = "LRCLIB · Synced",
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )

        assertNotNull(LyricsProviderResolver.metadataScore(track, candidate))
    }

    @Test
    fun finalResolverDoesNotTreatOrdinaryAlbumNameAsVersionEvidence() {
        val track = TrackInfo(
            title = "Song",
            artist = "Artist",
            album = "Live Through This",
            durationMs = 200_000L
        )
        val candidate = LyricsProviderCandidate(
            provider = "LRCLIB",
            title = "Song (Live)",
            artist = "Artist",
            album = "Live Through This",
            durationSec = 200.0,
            lines = listOf(LyricLine(1_000L, "line")),
            status = LyricsStatus.FOUND,
            source = "LRCLIB · Synced",
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )

        assertNull(LyricsProviderResolver.metadataScore(track, candidate))
    }
}
