package com.autolyrics.lyrics

import android.util.Log
import com.autolyrics.BuildConfig
import com.autolyrics.model.LyricLine
import com.autolyrics.model.TrackInfo
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Karaoke-only client for the public SyncLRC API.
 *
 * SyncLRC can fall back from a requested `karaoke` response to synced/plain
 * lyrics. Auto Lyrics deliberately rejects those fallbacks here because LRCLIB
 * already covers line/plain lyrics directly; this provider exists only to widen
 * genuine Enhanced-LRC / word-timed coverage.
 */
object SyncLrcClient {

    private const val TAG = "SyncLRC"
    private const val BASE_URL = "https://synclrc.dev/lyrics"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    data class SyncLrcResult(
        val lines: List<LyricLine>,
        val matchedTitle: String,
        val matchedArtist: String,
        val matchedAlbum: String,
        val matchedDurationSec: Double?,
        val artistQueryCorroborated: Boolean = true
    )

    internal data class ApiResponse(
        val lyrics: String? = null,
        val type: String? = null,
        val id: String? = null,
        val track: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean = false
    )

    fun getKaraokeLyrics(track: TrackInfo): SyncLrcResult? {
        if (track.title.isBlank() || track.artist.isBlank()) return null

        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("track", track.title)
            .addQueryParameter("artist", track.artist)
            .addQueryParameter("type", "karaoke")
            .apply {
                if (track.album.isNotBlank()) {
                    addQueryParameter("album", track.album)
                }
                if (track.durationMs > 0L) {
                    addQueryParameter(
                        "duration",
                        (track.durationMs / 1000.0).roundToInt().toString()
                    )
                }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "AutoLyrics/${BuildConfig.VERSION_NAME} (Android; SyncLRC)")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                debugLog("HTTP ${response.code}; karaoke request")
                if (!response.isSuccessful) return@use null
                parseApiResponse(response.body?.string().orEmpty(), track)
            }
        } catch (e: Exception) {
            debugLog("request failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            null
        }
    }

    internal fun parseApiResponse(
        json: String,
        requestedTrack: TrackInfo? = null
    ): SyncLrcResult? {
        if (json.isBlank()) return null

        val response = try {
            gson.fromJson(json, ApiResponse::class.java)
        } catch (_: Exception) {
            return null
        } ?: return null

        if (response.instrumental) {
            debugLog("response rejected: instrumental")
            return null
        }

        // The API intentionally falls back when karaoke is unavailable. Reject
        // that fallback so SyncLRC never duplicates LRCLIB LINE_SYNC/PLAIN data.
        if (!response.type.equals("karaoke", ignoreCase = true)) {
            debugLog("response has no karaoke payload; type=${response.type.orEmpty()}")
            return null
        }

        val lyrics = response.lyrics?.takeIf { it.isNotBlank() } ?: return null
        val lines = LrcParser.parseKaraoke(lyrics)
        if (lines.none { it.words.isNotEmpty() }) {
            debugLog("karaoke payload rejected: no timed tokens")
            return null
        }

        val fallback = requestedTrack
        val title = response.track.orEmpty().ifBlank { fallback?.title.orEmpty() }
        val artist = response.artist.orEmpty().ifBlank { fallback?.artist.orEmpty() }
        val album = response.album.orEmpty().ifBlank { fallback?.album.orEmpty() }

        debugLog(
            "karaoke accepted lines=${lines.size} tokens=${lines.sumOf { it.words.size }} " +
                "track='${title}' artist='${artist}'"
        )

        return SyncLrcResult(
            lines = lines,
            matchedTitle = title,
            matchedArtist = artist,
            matchedAlbum = album,
            matchedDurationSec = response.duration,
            artistQueryCorroborated = requestedTrack?.artist?.isNotBlank() != false
        )
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
