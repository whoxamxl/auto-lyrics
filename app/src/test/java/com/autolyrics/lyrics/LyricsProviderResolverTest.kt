package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsProviderResolverTest {

    @Test
    fun japaneseTrackPrefersPetitLyricsWhenMetadataIsEquallyStrong() {
        val track = TrackInfo(
            title = "みずいろの雨",
            artist = "Junko Yagami",
            album = "Test Album",
            durationMs = 204_000L
        )

        val lrcLib = candidate(
            provider = "LRCLIB",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 204.0,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )
        val petit = candidate(
            provider = "PetitLyrics",
            title = track.title,
            artist = "八神純子",
            album = track.album,
            durationSec = null,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC
        )

        val selected = LyricsProviderResolver.selectBest(track, listOf(lrcLib, petit))

        assertEquals("PetitLyrics", selected?.candidate?.provider)
    }

    @Test
    fun interleavedRomanizationReceivesQualityPenalty() {
        val track = TrackInfo("アイドル", "YOASOBI", "", 210_000L)
        val contaminated = LyricsProviderCandidate(
            provider = "LRCLIB",
            title = track.title,
            artist = track.artist,
            album = "",
            durationSec = 210.0,
            lines = listOf(
                LyricLine(1_000, "これは日本語です"),
                LyricLine(2_000, "kore wa nihongo desu"),
                LyricLine(3_000, "次の日本語です"),
                LyricLine(4_000, "tsugi no nihongo desu"),
                LyricLine(5_000, "さらに日本語です"),
                LyricLine(6_000, "sarani nihongo desu"),
                LyricLine(7_000, "最後の日本語です"),
                LyricLine(8_000, "saigo no nihongo desu")
            ),
            status = LyricsStatus.FOUND,
            source = "LRCLIB · Synced",
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )
        val clean = contaminated.copy(
            provider = "PetitLyrics",
            lines = listOf(
                LyricLine(1_000, "これは日本語です"),
                LyricLine(2_000, "次の日本語です"),
                LyricLine(3_000, "さらに日本語です"),
                LyricLine(4_000, "最後の日本語です"),
                LyricLine(5_000, "別の日本語です"),
                LyricLine(6_000, "終わりの日本語です")
            ),
            source = "PetitLyrics · Synced",
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC
        )

        val badScore = LyricsProviderResolver.lyricsQualityScore(contaminated, track.durationMs)
        val cleanScore = LyricsProviderResolver.lyricsQualityScore(clean, track.durationMs)

        assertTrue(badScore < cleanScore)
        assertTrue(badScore < 0.80)
    }

    @Test
    fun romanizedArtistVsJapaneseArtistIsNeutralWithAlbumEvidence() {
        val track = TrackInfo("巡恋歌", "Tsuyoshi Nagabuchi", "風は南から", 215_000L)
        val petit = candidate(
            provider = "PetitLyrics",
            title = "巡恋歌",
            artist = "長渕 剛",
            album = "風は南から",
            durationSec = null,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )

        val metadata = LyricsProviderResolver.metadataScore(track, petit)

        assertTrue(metadata != null && metadata > 0.95)
    }

    @Test
    fun artistConstrainedPetitLyricsQueryCorroboratesCrossScriptArtist() {
        val track = TrackInfo(
            title = "君を忘れない",
            artist = "Chiharu Matsuyama",
            album = "TOUR",
            durationMs = 288_000L
        )
        val petit = candidate(
            provider = "PetitLyrics",
            title = "君を忘れない",
            artist = "松山千春",
            album = "別アルバム",
            durationSec = null,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC,
            artistQueryCorroborated = true
        )

        val metadata = LyricsProviderResolver.metadataScore(track, petit)

        assertTrue(metadata != null && metadata > 0.90)
    }

    @Test
    fun titleOnlyCrossScriptArtistMismatchWithoutSecondaryEvidenceIsRejected() {
        val track = TrackInfo("同じタイトル", "Romanized Artist", "", 200_000L)
        val petit = candidate(
            provider = "PetitLyrics",
            title = "同じタイトル",
            artist = "別の歌手",
            album = "",
            durationSec = null,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC,
            artistQueryCorroborated = false
        )

        assertNull(LyricsProviderResolver.metadataScore(track, petit))
    }

    @Test
    fun westernTrackKeepsLrcLibTiePreference() {
        val track = TrackInfo("Example Song", "Example Artist", "Example Album", 200_000L)
        val lrcLib = candidate(
            provider = "LRCLIB",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 200.0,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )
        val petit = candidate(
            provider = "PetitLyrics",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 200.0,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC
        )

        val selected = LyricsProviderResolver.selectBest(track, listOf(lrcLib, petit))

        assertEquals("LRCLIB", selected?.candidate?.provider)
    }

    @Test
    fun synchronizedCandidateAlwaysBeatsPlainLyrics() {
        val track = TrackInfo("テスト曲", "テスト歌手", "", 180_000L)
        val plain = candidate(
            provider = "LRCLIB",
            title = track.title,
            artist = track.artist,
            album = "",
            durationSec = 180.0,
            status = LyricsStatus.PLAIN_ONLY,
            syncKind = LyricsProviderCandidate.SyncKind.PLAIN
        )
        val synced = candidate(
            provider = "PetitLyrics",
            title = track.title,
            artist = track.artist,
            album = "",
            durationSec = null,
            status = LyricsStatus.FOUND,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )

        val selected = LyricsProviderResolver.selectBest(track, listOf(plain, synced))

        assertEquals("PetitLyrics", selected?.candidate?.provider)
    }

    private fun candidate(
        provider: String,
        title: String,
        artist: String,
        album: String,
        durationSec: Double?,
        status: LyricsStatus = LyricsStatus.FOUND,
        syncKind: LyricsProviderCandidate.SyncKind,
        artistQueryCorroborated: Boolean = false
    ): LyricsProviderCandidate {
        return LyricsProviderCandidate(
            provider = provider,
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            lines = listOf(
                LyricLine(1_000, "テスト行一"),
                LyricLine(10_000, "テスト行二"),
                LyricLine(20_000, "テスト行三"),
                LyricLine(30_000, "テスト行四")
            ),
            status = status,
            source = "$provider · test",
            syncKind = syncKind,
            artistQueryCorroborated = artistQueryCorroborated
        )
    }
}