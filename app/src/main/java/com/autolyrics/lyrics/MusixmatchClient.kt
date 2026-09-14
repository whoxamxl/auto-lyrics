package com.autolyrics.lyrics

import android.util.Log
import com.autolyrics.BuildConfig
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToLong

/**
 * Musixmatch provider using the web-desktop API flow.
 *
 * The implementation mirrors the companion Python prototype:
 *  - signed requests to apic-desktop.musixmatch.com
 *  - track.search for discovery
 *  - track.richsync.get for synchronized lyrics
 *  - track.lyrics.get as a plain-text fallback
 *
 * Musixmatch does not publish this web-desktop flow as a stable public API, so
 * failures are intentionally isolated to this provider and never prevent the
 * other lyrics providers from running.
 */
object MusixmatchClient {

    private const val TAG = "Musixmatch"
    private const val BASE_URL = "https://apic-desktop.musixmatch.com/ws/1.1/"
    private const val SEARCH_PAGE_URL = "https://www.musixmatch.com/search"
    private const val APP_ID = "web-desktop-app-v1.0"
    private const val SEARCH_PAGE_SIZE = 15
    private const val MIN_PROVIDER_METADATA_SCORE = 0.70

    // Known web-desktop fallback used by the reference Python implementation.
    // If Musixmatch rotates it, a failed signed request triggers one live refresh
    // from the current web bundle before this provider gives up.
    private const val FALLBACK_SECRET = "al46t38ylg78ty4hls2345"

    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedSecret: String = FALLBACK_SECRET

    @Volatile
    private var attemptedSecretRefresh = false

    data class MusixmatchResult(
        val lines: List<LyricLine>,
        val matchedTitle: String,
        val matchedArtist: String,
        val matchedAlbum: String,
        val matchedDurationSec: Double?,
        val isRichSync: Boolean
    )

    internal data class TrackCandidate(
        val trackId: Long?,
        val commonTrackId: Long?,
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Double?,
        val hasRichSync: Boolean,
        val hasLyrics: Boolean
    )

    private data class RankedCandidate(
        val candidate: TrackCandidate,
        val score: Double,
        val index: Int
    )

    fun getLyrics(track: TrackInfo): MusixmatchResult? {
        if (track.title.isBlank()) return null

        val discovered = LinkedHashMap<String, TrackCandidate>()
        val queries = linkedSetOf(
            listOf(track.title, track.artist)
                .filter { it.isNotBlank() }
                .joinToString(" "),
            track.title
        )

        for (query in queries) {
            if (query.isBlank()) continue
            searchTracks(query).forEach { candidate ->
                val key = candidate.trackId?.let { "track:$it" }
                    ?: candidate.commonTrackId?.let { "common:$it" }
                    ?: listOf(candidate.title, candidate.artist, candidate.album)
                        .joinToString("|")
                discovered.putIfAbsent(key, candidate)
            }

            if (rankCandidates(track, discovered.values.toList()).isNotEmpty()) {
                break
            }
        }

        logCandidateDiagnostics(track, discovered.values.toList())
        val ranked = rankCandidates(track, discovered.values.toList())
        debugLog("search candidates=${discovered.size}, acceptable=${ranked.size}")

        for (rankedCandidate in ranked.take(3)) {
            val candidate = rankedCandidate.candidate

            if (candidate.hasRichSync) {
                val richSync = fetchRichSync(candidate)
                if (richSync.isNotEmpty()) {
                    return MusixmatchResult(
                        lines = richSync,
                        matchedTitle = candidate.title,
                        matchedArtist = candidate.artist,
                        matchedAlbum = candidate.album,
                        matchedDurationSec = candidate.durationSec,
                        isRichSync = true
                    )
                }
            }

            if (candidate.hasLyrics) {
                val plain = fetchPlainLyrics(candidate)
                if (plain.isNotEmpty()) {
                    return MusixmatchResult(
                        lines = plain,
                        matchedTitle = candidate.title,
                        matchedArtist = candidate.artist,
                        matchedAlbum = candidate.album,
                        matchedDurationSec = candidate.durationSec,
                        isRichSync = false
                    )
                }
            }
        }

        return null
    }

    internal fun parseSearchResponse(json: String): List<TrackCandidate> {
        val root = parseJsonObject(json) ?: return emptyList()
        if (apiStatus(root) != 200) return emptyList()

        val trackList = root.getAsJsonObject("message")
            ?.getAsJsonObject("body")
            ?.getAsJsonArray("track_list")
            ?: return emptyList()

        val result = ArrayList<TrackCandidate>(trackList.size())
        for (entry in trackList) {
            val track = entry.asJsonObject?.getAsJsonObject("track") ?: continue
            val title = track.string("track_name")
            if (title.isBlank()) continue

            result += TrackCandidate(
                trackId = track.longOrNull("track_id"),
                commonTrackId = track.longOrNull("commontrack_id"),
                title = title,
                artist = track.string("artist_name"),
                album = track.string("album_name"),
                durationSec = track.doubleOrNull("track_length"),
                hasRichSync = track.intOrNull("has_richsync") == 1,
                hasLyrics = track.intOrNull("has_lyrics") == 1
            )
        }
        return result
    }

    internal fun parseRichSyncResponse(json: String): List<LyricLine> {
        val root = parseJsonObject(json) ?: return emptyList()
        if (apiStatus(root) != 200) return emptyList()

        val richSyncBody = root.getAsJsonObject("message")
            ?.getAsJsonObject("body")
            ?.getAsJsonObject("richsync")
            ?.string("richsync_body")
            .orEmpty()
        if (richSyncBody.isBlank()) return emptyList()

        return try {
            val lines = JsonParser.parseString(richSyncBody).asJsonArray
            lines.mapNotNull { element ->
                val line = element.asJsonObject
                val startSec = line.doubleOrNull("ts") ?: return@mapNotNull null
                val text = line.string("x").ifBlank { "♪" }
                val startMs = secondsToMs(startSec)

                // The current renderer inserts a separator between LyricWord items.
                // RichSync for languages without spaces (notably Japanese) is kept
                // line-synced here to avoid visually corrupting the original text.
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

    internal fun parsePlainLyricsResponse(json: String): List<LyricLine> {
        val root = parseJsonObject(json) ?: return emptyList()
        if (apiStatus(root) != 200) return emptyList()

        val body = root.getAsJsonObject("message")
            ?.getAsJsonObject("body")
            ?.getAsJsonObject("lyrics")
            ?.string("lyrics_body")
            .orEmpty()
        if (body.isBlank()) return emptyList()

        return body.lineSequence()
            .map { it.trimEnd() }
            .takeWhile { line ->
                !line.startsWith("*******") &&
                    !line.contains("This Lyrics is NOT for Commercial use", ignoreCase = true)
            }
            .filter { it.isNotBlank() }
            .map { LyricLine(0L, it) }
            .toList()
    }

    private fun parseRichSyncWords(line: JsonObject, lineStartSec: Double): List<LyricWord> {
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

    private fun isPunctuationOnly(value: String): Boolean {
        return value.isNotBlank() && value.none { it.isLetterOrDigit() }
    }

    private fun searchTracks(query: String): List<TrackCandidate> {
        val response = makeRequest(
            endpoint = "track.search",
            params = linkedMapOf(
                "q" to query,
                "f_has_lyrics" to "true",
                "page_size" to SEARCH_PAGE_SIZE.toString(),
                "page" to "1"
            )
        ) ?: return emptyList()

        return parseSearchResponse(response)
    }

    private fun fetchRichSync(candidate: TrackCandidate): List<LyricLine> {
        val idParam = when {
            candidate.trackId != null -> "track_id" to candidate.trackId.toString()
            candidate.commonTrackId != null -> "commontrack_id" to candidate.commonTrackId.toString()
            else -> return emptyList()
        }

        val params = linkedMapOf(idParam)
        candidate.durationSec?.takeIf { it > 0.0 }?.let { duration ->
            params["f_richsync_length"] = duration.roundToLong().toString()
            params["f_richsync_length_max_deviation"] = "10"
        }

        val response = makeRequest("track.richsync.get", params) ?: return emptyList()
        return parseRichSyncResponse(response)
    }

    private fun fetchPlainLyrics(candidate: TrackCandidate): List<LyricLine> {
        val params = linkedMapOf<String, String>()
        when {
            candidate.trackId != null -> params["track_id"] = candidate.trackId.toString()
            else -> return emptyList()
        }

        val response = makeRequest("track.lyrics.get", params) ?: return emptyList()
        return parsePlainLyricsResponse(response)
    }

    private fun rankCandidates(
        track: TrackInfo,
        candidates: List<TrackCandidate>
    ): List<RankedCandidate> {
        return candidates.mapIndexedNotNull { index, candidate ->
            val normalized = normalizedCandidate(candidate)
            val score = LyricsProviderResolver.metadataScore(track, normalized)
                ?: return@mapIndexedNotNull null
            if (score < MIN_PROVIDER_METADATA_SCORE) return@mapIndexedNotNull null
            RankedCandidate(candidate, score, index)
        }.sortedWith(
            compareByDescending<RankedCandidate> { it.score }
                .thenByDescending { it.candidate.hasRichSync }
                .thenBy { it.index }
        )
    }

    private fun normalizedCandidate(candidate: TrackCandidate): LyricsProviderCandidate {
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
                LyricsProviderCandidate.SyncKind.PLAIN
            }
        )
    }

    private fun logCandidateDiagnostics(
        track: TrackInfo,
        candidates: List<TrackCandidate>
    ) {
        if (!BuildConfig.DEBUG) return

        val targetDurationSec = track.durationMs
            .takeIf { it > 0L }
            ?.div(1000.0)
        debugLog(
            "target title='${track.title}' artist='${track.artist}' album='${track.album}' " +
                "duration=${scoreText(targetDurationSec)}s"
        )

        candidates.forEachIndexed { index, candidate ->
            val titleCompatible = LrcLibClient.versionsCompatible(track.title, candidate.title)
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
            val metadataScore = LyricsProviderResolver.metadataScore(
                track,
                normalizedCandidate(candidate)
            )

            val verdict = when {
                !titleCompatible -> "REJECT version-mismatch"
                titleScore < 0.60 -> "REJECT title<0.60"
                durationScore != null && durationScore < 0.0 -> "REJECT duration-mismatch"
                metadataScore == null -> "REJECT resolver-gate"
                metadataScore < MIN_PROVIDER_METADATA_SCORE -> "REJECT metadata<0.70"
                else -> "ACCEPT"
            }

            debugLog(
                "candidate[$index] id=${candidate.trackId ?: "-"}/${candidate.commonTrackId ?: "-"} " +
                    "title='${candidate.title}' artist='${candidate.artist}' album='${candidate.album}' " +
                    "duration=${scoreText(candidate.durationSec)}s rich=${candidate.hasRichSync} " +
                    "lyrics=${candidate.hasLyrics}"
            )
            debugLog(
                "candidate[$index] scores compatible=$titleCompatible " +
                    "title=${scoreText(titleScore)} artist=${scoreText(artistScore)} " +
                    "album=${scoreText(albumScore)} duration=${scoreText(durationScore)} " +
                    "metadata=${scoreText(metadataScore)} $verdict"
            )
        }
    }

    private fun scoreText(value: Double?): String {
        return value?.let { String.format(Locale.US, "%.3f", it) } ?: "n/a"
    }

    private fun makeRequest(
        endpoint: String,
        params: LinkedHashMap<String, String>
    ): String? {
        val unsignedUrl = buildUnsignedUrl(endpoint, params)

        var response = executeSigned(unsignedUrl, cachedSecret)
        if (response != null && response.isAcceptedApiResponse()) {
            return response
        }

        if (!attemptedSecretRefresh) {
            synchronized(this) {
                if (!attemptedSecretRefresh) {
                    attemptedSecretRefresh = true
                    fetchCurrentSecret()?.let { cachedSecret = it }
                }
            }
            response = executeSigned(unsignedUrl, cachedSecret)
            if (response != null && response.isAcceptedApiResponse()) {
                return response
            }
        }

        return response?.takeIf { it.isAcceptedApiResponse() }
    }

    private fun buildUnsignedUrl(
        endpoint: String,
        params: LinkedHashMap<String, String>
    ): String {
        val all = linkedMapOf(
            "app_id" to APP_ID,
            "format" to "json"
        )
        all.putAll(params)

        val query = all.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        return "$BASE_URL$endpoint?$query"
    }

    private fun executeSigned(unsignedUrl: String, secret: String): String? {
        val signature = generateSignature(unsignedUrl, secret)
        val signedUrl = "$unsignedUrl&signature=${urlEncode(signature)}&signature_protocol=sha256"
        val request = Request.Builder()
            .url(signedUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                debugLog("${response.code} ${URI(unsignedUrl).path.substringAfterLast('/')}")
                body.takeIf { response.isSuccessful && it.isNotBlank() }
            }
        } catch (e: Exception) {
            debugLog("request failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            null
        }
    }

    private fun fetchCurrentSecret(): String? {
        val searchRequest = Request.Builder()
            .url(SEARCH_PAGE_URL)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "mxm_bab=AB")
            .build()

        val html = try {
            client.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string().orEmpty()
            }
        } catch (_: Exception) {
            return null
        }

        val matches = APP_SCRIPT_REGEX.findAll(html).toList()
        val scriptPath = matches.lastOrNull()?.groupValues?.getOrNull(1) ?: return null
        val scriptUrl = when {
            scriptPath.startsWith("https://") -> scriptPath
            scriptPath.startsWith("//") -> "https:$scriptPath"
            scriptPath.startsWith("/") -> "https://www.musixmatch.com$scriptPath"
            else -> "https://www.musixmatch.com/$scriptPath"
        }

        val scriptRequest = Request.Builder()
            .url(scriptUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        val javascript = try {
            client.newCall(scriptRequest).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string().orEmpty()
            }
        } catch (_: Exception) {
            return null
        }

        val encoded = SECRET_REGEX.find(javascript)?.groupValues?.getOrNull(1) ?: return null
        return try {
            val reversed = encoded.reversed()
            String(Base64.getDecoder().decode(reversed), StandardCharsets.UTF_8)
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun generateSignature(url: String, secret: String): String {
        val formatter = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val message = (url + formatter.format(Date())).toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(message))
    }

    private fun String.isAcceptedApiResponse(): Boolean {
        val root = parseJsonObject(this) ?: return false
        return apiStatus(root) == 200
    }

    private fun parseJsonObject(json: String): JsonObject? {
        return try {
            JsonParser.parseString(json).asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun apiStatus(root: JsonObject): Int? {
        return root.getAsJsonObject("message")
            ?.getAsJsonObject("header")
            ?.intOrNull("status_code")
    }

    private fun JsonObject.string(name: String): String {
        val value = get(name) ?: return ""
        return try {
            if (value.isJsonNull) "" else value.asString.orEmpty()
        } catch (_: Exception) {
            ""
        }
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

    private fun secondsToMs(seconds: Double): Long = (seconds * 1000.0).roundToLong()

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private val APP_SCRIPT_REGEX = Regex(
        """src=[\"']([^\"']*/_next/static/chunks/pages/_app-[^\"']+\.js)[\"']"""
    )
    private val SECRET_REGEX = Regex("""from\(\s*[\"'](.*?)[\"']\s*\.split""")
}
