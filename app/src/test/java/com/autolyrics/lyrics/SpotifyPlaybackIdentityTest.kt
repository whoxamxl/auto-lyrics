package com.autolyrics.lyrics

import com.autolyrics.media.SpotifyTrackIdentity
import com.autolyrics.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyPlaybackIdentityTest {

    private val spotifyId = "6rqhFgbbKwnb9MLmUQDhG6"

    @Test
    fun extractsTrackIdFromSpotifyUri() {
        val track = track(
            sourcePackage = SpotifyTrackIdentity.PACKAGE_NAME,
            sourceUri = "spotify:track:$spotifyId"
        )

        assertEquals(spotifyId, SpotifyTrackIdentity.trackId(track))
    }

    @Test
    fun extractsTrackIdFromSpotifyMediaId() {
        val track = track(
            sourcePackage = SpotifyTrackIdentity.PACKAGE_NAME,
            sourceId = spotifyId
        )

        assertEquals(spotifyId, SpotifyTrackIdentity.trackId(track))
    }

    @Test
    fun rejectsSpotifyLookingIdFromAnotherPlayer() {
        val track = track(
            sourcePackage = "com.example.player",
            sourceUri = "spotify:track:$spotifyId"
        )

        assertNull(SpotifyTrackIdentity.trackId(track))
    }

    @Test
    fun musixmatchAddsSpotifyIdHintForSpotifyPlayback() {
        val track = track(
            sourcePackage = SpotifyTrackIdentity.PACKAGE_NAME,
            sourceUri = "spotify:track:$spotifyId"
        )

        val params = MusixmatchClient.buildMacroParams(track)

        assertEquals(spotifyId, params["track_spotify_id"])
        assertEquals("Example Song", params["q_track"])
        assertEquals("Example Artist", params["q_artist"])
    }

    @Test
    fun musixmatchKeepsNonSpotifyRequestUnchanged() {
        val track = track(sourcePackage = "com.example.player")

        val params = MusixmatchClient.buildMacroParams(track)

        assertFalse(params.containsKey("track_spotify_id"))
    }

    private fun track(
        sourcePackage: String,
        sourceId: String = "",
        sourceUri: String = ""
    ): TrackInfo {
        return TrackInfo(
            title = "Example Song",
            artist = "Example Artist",
            album = "Example Album",
            durationMs = 200_000L,
            playbackSourcePackage = sourcePackage,
            playbackSourceMediaId = sourceId,
            playbackSourceMediaUri = sourceUri
        )
    }
}
