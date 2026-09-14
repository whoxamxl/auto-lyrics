package com.autolyrics.media

import com.autolyrics.model.TrackInfo

/**
 * Extracts a Spotify track ID only from playback metadata that came from the
 * Spotify Android app and explicitly identifies a track resource.
 */
object SpotifyTrackIdentity {

    const val PACKAGE_NAME = "com.spotify.music"

    private val URI_PATTERN = Regex(
        "^spotify:track:([A-Za-z0-9]{22})$",
        RegexOption.IGNORE_CASE
    )
    private val URL_PATTERN = Regex(
        "^https?://open\\.spotify\\.com/track/([A-Za-z0-9]{22})(?:[/?#].*)?$",
        RegexOption.IGNORE_CASE
    )

    fun trackId(track: TrackInfo): String? {
        if (track.playbackSourcePackage != PACKAGE_NAME) return null

        return parse(track.playbackSourceMediaUri)
            ?: parse(track.playbackSourceMediaId)
    }

    internal fun parse(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) return null

        URI_PATTERN.matchEntire(normalized)?.let { return it.groupValues[1] }
        URL_PATTERN.matchEntire(normalized)?.let { return it.groupValues[1] }

        return null
    }
}
