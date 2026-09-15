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
        val candidate = candidate(title = "Song (Live)", album = "Live at Wembley")

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
        val candidate = candidate(title = "Song (Live)", album = "Live Through This")

        assertNull(LyricsProviderResolver.metadataScore(track, candidate))
    }

    @Test
    fun finalResolverDoesNotTreatBracketedAlbumSubtitleAsVersionEvidence() {
        val track = TrackInfo(
            title = "Song",
            artist = "Artist",
            album = "Album (We Live Here)",
            durationMs = 200_000L
        )
        val candidate = candidate(title = "Song (Live)", album = "")

        assertNull(LyricsProviderResolver.metadataScore(track, candidate))
    }

    private fun candidate(title: String, album: String): LyricsProviderCandidate {
        return LyricsProviderCandidate(
            provider = "LRCLIB",
            title = title,
            artist = "Artist",
            album = album,
            durationSec = 200.0,
            lines = listOf(LyricLine(1_000L, "line")),
            status = LyricsStatus.FOUND,
            source = "LRCLIB · Synced",
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )
    }
}
