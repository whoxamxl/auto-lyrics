package com.autolyrics.lyrics

import android.content.SharedPreferences
import android.util.Log
import com.autolyrics.BuildConfig
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Musixmatch provider using the anonymous desktop-token flow.
 *
 * Flow:
 *  1. token.get -> anonymous user_token, cached in SharedPreferences.
 *  2. macro.subtitles.get -> matched metadata + richsync/subtitle macro calls.
 *  3. track.richsync.get -> fallback RichSync request if the macro omitted it.
 *
 * This is an unofficial endpoint. Failures remain isolated to this provider.
 */
object MusixmatchClient {

    private const val TAG = "Musixmatch"
    private const val BASE_URL = "https://apic-desktop.musixmatch.com/ws/1.1/"
    private const val APP_ID = "web-desktop-app-v1.0"
    private const val MIN_PROVIDER_METADATA_SCORE = 0.70

    private const val TOKEN_PREF_KEY = "musixmatch_user_token"
    private const val TOKEN_TIME_PREF_KEY = "musixmatch_user_token_time_ms"
    private const val TOKEN_TTL_MS = 10L * 60L * 1000L

    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    private val tokenLock = Any()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedTokenAtMs: Long = 0L

    data class MusixmatchResult(
        val lines: List<LyricLine>,
        val matchedTitle: String,
        val matchedArtist: String,
        val matchedAlbum: String,
        val matchedDurationSec: Double?,
        val isRichSync: Boolean,
        val artistQueryCorroborated: Boolean = false
    )

    internal data class TrackCandidate(
        val trackId: Long?,
        val commonTrackId: Long?,
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Double?,
        val hasRichSync: Boolean,
        val instrumental: Boolean
    )

    internal data class MacroMatch(
        val candidate: TrackCandidate,
        val subtitleBody: String,
        val richSyncResponseJson: String? = null
    )

    private data class HttpJsonResponse(
        val httpCode: Int,
        val json: JsonObject?
    )

    fun getLyrics(
        track: TrackInfo,
        prefs: SharedPreferences? = null
    ): MusixmatchResult? {
        if (track.title.isBlank()) return null

        val macro = fetchMacro(track, prefs) ?: return null
        val match = parseMacroResponse(macro.toString()) ?: return null
        val candidate = match.candidate

        logMatchDiagnostics(track, candidate)

        if (candidate.instrumental) {
            debugLog("macro match rejected: instrumental")
            return null
        }

        val normalized = normalizedCandidate(
            candidate = candidate,
            artistQueryCorroborated = track.artist.isNotBlank()
        )
        val metadataScore = LyricsProviderResolver.metadataScore(track, normalized)
        if (metadataScore == null || metadataScore < MIN_PROVIDER_METADATA_SCORE) {
            debugLog("macro match rejected: metadata=${scoreText(metadataScore)}")
            return null
        }

        if (candidate.hasRichSync) {
            val inlineRichSync = match.richSyncResponseJson
                ?.let(::parseRichSyncResponse)
                .orEmpty()
            val richSync = if (inlineRichSync.isNotEmpty()) {
                debugLog("using RichSync embedded in macro response")
                inlineRichSync
            } else if (candidate.commonTrackId != null) {
                fetchRichSync(candidate, prefs)
            } else {
                emptyList()
            }

            if (richSync.isNotEmpty()) {
                debugLog("richsync accepted lines=${richSync.size}")
                return MusixmatchResult(
                    lines = richSync,
                    matchedTitle = candidate.title,
                    matchedArtist = candidate.artist,
                    matchedAlbum = candidate.album,
                    matchedDurationSec = candidate.durationSec,
                    isRichSync = true,
                    artistQueryCorroborated = track.artist.isNotBlank()
                )
            }
        }

        val lineSync = parseSubtitleBody(match.subtitleBody)
        if (lineSync.isNotEmpty()) {
            debugLog("line subtitle accepted lines=${lineSync.size}")
            return MusixmatchResult(
                lines = lineSync,
                matchedTitle = candidate.title,
                matchedArtist = candidate.artist,
                matchedAlbum = candidate.album,
                matchedDurationSec = candidate.durationSec,
                isRichSync = false,
                artistQueryCorroborated = track.artist.isNotBlank()
            )
        }

        debugLog("macro match had no usable synced lyrics")
        return null
    }

    private fun fetchMacro(
        track: TrackInfo,
        prefs: SharedPreferences?
    ): JsonObject? {
        val params = linkedMapOf(
            "namespace" to "lyrics_richsynced",
            "optional_calls" to "track.richsync",
            "subtitle_format" to "lrc",
            "q_artist" to track.artist,
            "q_track" to track.title
        )
        if (track.durationMs > 0L) {
            params["f_subtitle_length"] = (track.durationMs / 1000.0).roundToLong().toString()
            params["f_subtitle_length_max_deviation"] = "3"
        }

        debugLog(
            "macro.subtitles.get q_track='${track.title}' q_artist='${track.artist}' " +
                "f_subtitle_length='${params["f_subtitle_length"].orEmpty()}' " +
                "max_deviation='${params["f_subtitle_length_max_deviation"].orEmpty()}'"
        )

        return authenticatedRequest("macro.subtitles.get", params, prefs)
    }

    private fun fetchRichSync(
        candidate: TrackCandidate,
        prefs: SharedPreferences?
    ): List<LyricLine> {
        val commonTrackId = candidate.commonTrackId ?: return emptyList()
        val response = authenticatedRequest(
            endpoint = "track.richsync.get",
            params = linkedMapOf("commontrack_id" to commonTrackId.toString()),
            prefs = prefs
        ) ?: return emptyList()

        return parseRichSyncResponse(response.toString())
    }

    private fun authenticatedRequest(
        endpoint: String,
        params: LinkedHashMap<String, String>,
        prefs: SharedPreferences?
    ): JsonObject? {
        var token = getToken(prefs, force = false) ?: return null
        var response = request(
            endpoint = endpoint,
            params = LinkedHashMap(params).apply { put("usertoken", token) }
        )

        if (response.httpCode == 401 || apiStatus(response.json) == 401) {
            debugLog("$endpoint token rejected; refreshing once")
            token = getToken(prefs, force = true) ?: return null
            response = request(
                endpoint = endpoint,
                params = LinkedHashMap(params).apply { put("usertoken", token) }
            )
        }

        return response.json?.takeIf { apiStatus(it) == 200 }
    }

    private fun getToken(
        prefs: SharedPreferences?,
        force: Boolean
    ): String? {
        synchronized(tokenLock) {
            val now = System.currentTimeMillis()

            if (cachedToken == null && prefs != null) {
                cachedToken = prefs.getString(TOKEN_PREF_KEY, null)
                cachedTokenAtMs = prefs.getLong(TOKEN_TIME_PREF_KEY, 0L)
            }

            val memoryToken = cachedToken
            if (
                !force &&
                !memoryToken.isNullOrBlank() &&
                now - cachedTokenAtMs in 0 until TOKEN_TTL_MS
            ) {
                debugLog("token cache hit")
                return memoryToken
            }

            debugLog(if (force) "token.get refresh" else "token.get")
            val response = request(
                endpoint = "token.get",
                params = linkedMapOf("user_language" to "en")
            )
            val token = parseTokenResponse(response.json?.toString().orEmpty())
            if (!token.isNullOrBlank()) {
                cachedToken = token
                cachedTokenAtMs = now
                prefs?.edit()
                    ?.putString(TOKEN_PREF_KEY, token)
                    ?.putLong(TOKEN_TIME_PREF_KEY, now)
                    ?.apply()
                debugLog("token cached")
                return token
            }

            // If refresh was rate-limited but an older token exists, give it one
            // last chance. authenticatedRequest() will reject it if it is invalid.
            return cachedToken
        }
    }

    private fun request(
        endpoint: String,
        params: LinkedHashMap<String, String>
    ): HttpJsonResponse {
        val url = buildUrl(endpoint, params)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Accept-Language", "en")
            .header("Cookie", "AWSELBCORS=0; AWSELB=0")
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = parseJsonObject(body)
                debugLog(
                    "${response.code} api=${apiStatus(json) ?: "-"} $endpoint"
                )
                HttpJsonResponse(response.code, json)
            }
        } catch (e: Exception) {
            debugLog("$endpoint failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            HttpJsonResponse(-1, null)
        }
    }

    private fun buildUrl(
        endpoint: String,
        params: LinkedHashMap<String, String>
    ): String {
        val all = linkedMapOf(
            "app_id" to APP_ID,
            "format" to "json",
            "t" to System.currentTimeMillis().toString()
        )
        all.putAll(params)

        val query = all.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        return "$BASE_URL$endpoint?$query"
    }

    internal fun parseTokenResponse(json: String): String? {
        val root = parseJsonObject(json) ?: return null
        if (apiStatus(root) != 200) return null
        val token = root.getAsJsonObject("message")
            ?.getAsJsonObject("body")
            ?.string("user_token")
            .orEmpty()
        return token.takeIf { it.isNotBlank() && !it.startsWith("UpgradeOnly") }
    }

    internal fun parseMacroResponse(json: String): MacroMatch? {
        val root = parseJsonObject(json) ?: return null
        if (apiStatus(root) != 200) return null

        val macroCalls = root.getAsJsonObject("message")
            ?.getAsJsonObject("body")
            ?.getAsJsonObject("macro_calls")
            ?: return null

        val matcherCall = macroCalls.getAsJsonObject("matcher.track.get") ?: return null
        if (apiStatus(matcherCall) != 200) return null
        val track = matcherCall.getAsJsonObject("message")
            ?.getAsJsonObject("body")
            ?.getAsJsonObject("track")
            ?: return null

        val title = track.string("track_name")
        if (title.isBlank()) return null

        val candidate = TrackCandidate(
            trackId = track.longOrNull("track_id"),
            commonTrackId = track.longOrNull("commontrack_id"),
            title = title,
            artist = track.string("artist_name"),
            album = track.string("album_name"),
            durationSec = track.doubleOrNull("track_length"),
            hasRichSync = track.intOrNull("has_richsync") == 1,
            instrumental = track.intOrNull("instrumental") == 1
        )

        val subtitleCall = macroCalls.getAsJsonObject("track.subtitles.get")
        val subtitleBody = if (apiStatus(subtitleCall) == 200) {
            subtitleCall
                ?.getAsJsonObject("message")
                ?.getAsJsonObject("body")
                ?.getAsJsonArray("subtitle_list")
                ?.firstOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.getAsJsonObject("subtitle")
                ?.string("subtitle_body")
                .orEmpty()
        } else {
            ""
        }

        val richSyncCall = macroCalls.getAsJsonObject("track.richsync.get")
        val richSyncResponseJson = richSyncCall
            ?.takeIf { apiStatus(it) == 200 }
            ?.toString()

        return MacroMatch(candidate, subtitleBody, richSyncResponseJson)
    }

    internal fun parseSubtitleBody(subtitleBody: String): List<LyricLine> {
        if (subtitleBody.isBlank()) return emptyList()
        return LrcParser.parse(subtitleBody)
            .filter { it.timeMs >= 0L }
            .distinctBy { it.timeMs to it.text }
    }

    internal fun parseRichSyncResponse(json: String): List<LyricLine> {
        val root = parseJsonObject(json) ?: return emptyList()
        if (apiStatus(root) != 200) return emptyList()

        val richSyncBody = deepFind(root, "richsync_body")
            ?.asStringOrNull()
            .orEmpty()
        if (richSyncBody.isBlank()) return emptyList()

        return try {
            val lines = JsonParser.parseString(richSyncBody).asJsonArray
            lines.mapNotNull { element ->
                val line = element.asJsonObject
                val startSec = line.doubleOrNull("ts") ?: return@mapNotNull null
                val text = line.string("x").ifBlank { "♪" }
                val startMs = secondsToMs(startSec)

                // MainActivity currently inserts a separator between LyricWord
                // items. For no-space languages keep exact line text and expose
                // line timing until the renderer supports chunk-preserving joins.
                val words = if (text.any { it.isWhitespace() }) {
                    parseRichSyncWords(line, startSec)
                } else {
                    emptyList()
                }

                LyricLine(
                    timeMs = startMs,
                    text = text,
                    words = words
                )
            }
                .filter { it.timeMs >= 0L }
                .sortedBy { it.timeMs }
                .distinctBy { it.timeMs to it.text }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseRichSyncWords(
        line: JsonObject,
        lineStartSec: Double
    ): List<LyricWord> {
        val chunks = line.getAsJsonArray("l") ?: return emptyList()
        val words = mutableListOf<LyricWord>()

        for (chunkElement in chunks) {
            val chunk = chunkElement.asJsonObject
            val raw = chunk.string("c")
            val text = raw.trim()
            if (text.isBlank()) continue

            val offsetSec = chunk.doubleOrNull("o") ?: 0.0
            val timeMs = secondsToMs(lineStartSec + offsetSec)

            if (isPunctuationOnly(text) && words.isNotEmpty()) {
                val previous = words.removeAt(words.lastIndex)
                words += previous.copy(text = previous.text + text)
            } else {
                words += LyricWord(timeMs = timeMs, text = text)
            }
        }

        return words
    }

    private fun normalizedCandidate(
        candidate: TrackCandidate,
        artistQueryCorroborated: Boolean
    ): LyricsProviderCandidate {
        return LyricsProviderCandidate(
            provider = "Musixmatch",
            title = candidate.title,
            artist = candidate.artist,
            album = candidate.album,
            durationSec = candidate.durationSec,
            lines = listOf(LyricLine(0L, "candidate")),
            status = LyricsStatus.FOUND,
            source = "Musixmatch · candidate",
            syncKind = if (candidate.hasRichSync) {
                LyricsProviderCandidate.SyncKind.WORD_SYNC
            } else {
                LyricsProviderCandidate.SyncKind.LINE_SYNC
            },
            artistQueryCorroborated = artistQueryCorroborated
        )
    }

    private fun logMatchDiagnostics(
        track: TrackInfo,
        candidate: TrackCandidate
    ) {
        if (!BuildConfig.DEBUG) return

        val targetDurationSec = track.durationMs
            .takeIf { it > 0L }
            ?.div(1000.0)
        val titleScore = LrcLibClient.stringSimilarity(track.title, candidate.title)
        val artistScore = if (track.artist.isNotBlank() && candidate.artist.isNotBlank()) {
            LrcLibClient.artistSimilarity(
                left = track.artist,
                right = candidate.artist,
                allowContributorComponents = titleScore >= 0.95
            )
        } else {
            null
        }
        val albumScore = if (track.album.isNotBlank() && candidate.album.isNotBlank()) {
            LrcLibClient.stringSimilarity(track.album, candidate.album)
        } else {
            null
        }
        val durationScore = if (targetDurationSec != null && candidate.durationSec != null) {
            LrcLibClient.durationSimilarity(targetDurationSec.toInt(), candidate.durationSec)
        } else {
            null
        }
        val metadata = LyricsProviderResolver.metadataScore(
            track,
            normalizedCandidate(candidate, track.artist.isNotBlank())
        )

        debugLog(
            "target title='${track.title}' artist='${track.artist}' album='${track.album}' " +
                "duration=${scoreText(targetDurationSec)}s"
        )
        debugLog(
            "macro match id=${candidate.trackId ?: "-"}/${candidate.commonTrackId ?: "-"} " +
                "title='${candidate.title}' artist='${candidate.artist}' album='${candidate.album}' " +
                "duration=${scoreText(candidate.durationSec)}s rich=${candidate.hasRichSync} " +
                "instrumental=${candidate.instrumental}"
        )
        debugLog(
            "macro scores title=${scoreText(titleScore)} artist=${scoreText(artistScore)} " +
                "album=${scoreText(albumScore)} duration=${scoreText(durationScore)} " +
                "metadata=${scoreText(metadata)}"
        )
    }

    private fun deepFind(element: JsonElement?, key: String): JsonElement? {
        if (element == null || element.isJsonNull) return null

        if (element.isJsonObject) {
            val obj = element.asJsonObject
            obj.get(key)?.let { return it }
            for ((_, value) in obj.entrySet()) {
                deepFind(value, key)?.let { return it }
            }
        } else if (element.isJsonArray) {
            for (value in element.asJsonArray) {
                deepFind(value, key)?.let { return it }
            }
        }

        return null
    }

    private fun parseJsonObject(json: String): JsonObject? {
        if (json.isBlank() || json.trimStart().startsWith("<")) return null
        return try {
            JsonParser.parseString(json).asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun apiStatus(root: JsonObject?): Int? {
        return root
            ?.getAsJsonObject("message")
            ?.getAsJsonObject("header")
            ?.intOrNull("status_code")
    }

    private fun JsonObject.string(name: String): String {
        return get(name)?.asStringOrNull().orEmpty()
    }

    private fun JsonObject.intOrNull(name: String): Int? {
        return try {
            get(name)?.takeUnless { it.isJsonNull }?.asInt
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.longOrNull(name: String): Long? {
        return try {
            get(name)?.takeUnless { it.isJsonNull }?.asLong
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.doubleOrNull(name: String): Double? {
        return try {
            get(name)?.takeUnless { it.isJsonNull }?.asDouble
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonElement.asStringOrNull(): String? {
        return try {
            if (isJsonNull) null else asString
        } catch (_: Exception) {
            null
        }
    }

    private fun isPunctuationOnly(value: String): Boolean {
        return value.isNotBlank() && value.none { it.isLetterOrDigit() }
    }

    private fun secondsToMs(seconds: Double): Long = (seconds * 1000.0).roundToLong()

    private fun scoreText(value: Double?): String {
        return value?.let { String.format(Locale.US, "%.3f", it) } ?: "n/a"
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
