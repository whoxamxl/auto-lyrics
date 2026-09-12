package com.autolyrics.lyrics

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

object LrcLibClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private const val BASE_URL = "https://lrclib.net/api"
    private const val USER_AGENT = "AutoLyrics/1.9.6 (https://github.com/whoxamxl/auto-lyrics)"

    private const val MIN_MATCH_SCORE = 0.70
    private const val MIN_TITLE_SCORE = 0.60
    private const val MIN_ARTIST_SCORE = 0.40
    private const val MAX_DURATION_DELTA_SEC = 15.0

    private val MULTI_SPACE = Regex("""\s+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val FEAT_SUFFIX = Regex(
        """\s*[\(\[]?\s*(?:feat(?:uring)?|ft)\.?\s+.+?[\)\]]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    data class LrcLibResponse(
        @SerializedName("id") val id: Int?,
        @SerializedName("trackName") val trackName: String?,
        @SerializedName("artistName") val artistName: String?,
        @SerializedName("albumName") val albumName: String?,
        @SerializedName("duration") val duration: Double?,
        @SerializedName("instrumental") val instrumental: Boolean?,
        @SerializedName("plainLyrics") val plainLyrics: String?,
        @SerializedName("syncedLyrics") val syncedLyrics: String?
    )

    private data class SearchQuery(
        val trackName: String,
        val artistName: String?,
        val albumName: String?
    )

    private data class ScoredCandidate(
        val response: LrcLibResponse,
        val score: Double
    )

    fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LrcLibResponse? {
        val candidates = LinkedHashMap<String, LrcLibResponse>()

        fun addCandidate(candidate: LrcLibResponse?) {
            if (candidate == null) return
            val key = candidate.id?.let { "id:$it" }
                ?: listOf(
                    candidate.trackName.orEmpty(),
                    candidate.artistName.orEmpty(),
                    candidate.albumName.orEmpty(),
                    candidate.duration?.toString().orEmpty()
                ).joinToString("|")
            candidates.putIfAbsent(key, candidate)
        }

        fun addCandidates(results: List<LrcLibResponse>) {
            results.forEach(::addCandidate)
        }

        // /api/get is useful as a strong candidate, but do not trust it blindly.
        // LRCLIB's search endpoint can contain a closer match, so all candidates
        // are locally re-ranked by title, artist, album and duration below.
        if (durationSec > 0 && albumName.isNotBlank()) {
            addCandidate(getExact(trackName, artistName, albumName, durationSec))
        }

        val structuredQueries = linkedSetOf<SearchQuery>()
        structuredQueries += SearchQuery(
            trackName = trackName,
            artistName = artistName.takeIf { it.isNotBlank() },
            albumName = albumName.takeIf { it.isNotBlank() }
        )
        if (albumName.isNotBlank()) {
            structuredQueries += SearchQuery(
                trackName = trackName,
                artistName = artistName.takeIf { it.isNotBlank() },
                albumName = null
            )
        }

        val strippedTitle = stripFeaturing(trackName)
        val primaryArtist = stripFeaturing(artistName)
        if (strippedTitle != trackName || primaryArtist != artistName) {
            structuredQueries += SearchQuery(
                trackName = strippedTitle,
                artistName = primaryArtist.takeIf { it.isNotBlank() },
                albumName = null
            )
        }

        structuredQueries.forEach { query ->
            addCandidates(searchAll(query.trackName, query.artistName, query.albumName))
        }

        selectBest(
            candidates.values,
            trackName,
            artistName,
            albumName,
            durationSec,
            requireSynced = true
        )?.let { return it }

        // A free-text fallback helps when field-specific FTS matching is defeated
        // by collaboration/feature metadata differences. It is only used when no
        // acceptable synchronized result was found by the structured searches.
        val freeText = listOf(trackName, artistName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (freeText.isNotBlank()) {
            addCandidates(searchFreeText(freeText))
        }

        selectBest(
            candidates.values,
            trackName,
            artistName,
            albumName,
            durationSec,
            requireSynced = true
        )?.let { return it }

        // Plain lyrics are a last resort. They use the same metadata validation,
        // so a weak first search result cannot win simply because it was first.
        return selectBest(
            candidates.values,
            trackName,
            artistName,
            albumName,
            durationSec,
            requireSynced = false
        )
    }

    private fun selectBest(
        candidates: Collection<LrcLibResponse>,
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int,
        requireSynced: Boolean
    ): LrcLibResponse? {
        return candidates.asSequence()
            .filter { candidate ->
                if (requireSynced) {
                    !candidate.syncedLyrics.isNullOrBlank()
                } else {
                    !candidate.plainLyrics.isNullOrBlank()
                }
            }
            .mapNotNull { candidate ->
                scoreCandidate(candidate, trackName, artistName, albumName, durationSec)
                    ?.let { score -> ScoredCandidate(candidate, score) }
            }
            .filter { it.score >= MIN_MATCH_SCORE }
            .maxByOrNull { it.score }
            ?.response
    }

    private fun scoreCandidate(
        candidate: LrcLibResponse,
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): Double? {
        val candidateTitle = candidate.trackName.orEmpty()
        val candidateArtist = candidate.artistName.orEmpty()
        val candidateAlbum = candidate.albumName.orEmpty()

        val titleScore = titleSimilarity(trackName, candidateTitle)
        if (titleScore < MIN_TITLE_SCORE) return null

        val artistScore = if (artistName.isBlank() || candidateArtist.isBlank()) {
            null
        } else {
            artistSimilarity(artistName, candidateArtist)
        }

        val durationScore = durationSimilarity(durationSec, candidate.duration)
        if (durationScore != null && durationScore < 0.0) return null

        // Reject a clearly different artist unless title + duration are both
        // exceptionally strong. This still tolerates common feat./ft. metadata
        // differences through artistSimilarity().
        if (artistScore != null && artistScore < MIN_ARTIST_SCORE) {
            val strongTitleAndDuration = titleScore >= 0.95 && (durationScore ?: 0.0) >= 0.85
            if (!strongTitleAndDuration) return null
        }

        var weightedScore = titleScore * 0.55
        var totalWeight = 0.55

        if (artistScore != null) {
            weightedScore += artistScore * 0.30
            totalWeight += 0.30
        }

        if (durationScore != null) {
            weightedScore += durationScore * 0.12
            totalWeight += 0.12
        }

        if (albumName.isNotBlank() && candidateAlbum.isNotBlank() && candidateAlbum != "-") {
            weightedScore += stringSimilarity(albumName, candidateAlbum) * 0.03
            totalWeight += 0.03
        }

        return weightedScore / totalWeight
    }

    private fun durationSimilarity(durationSec: Int, candidateDuration: Double?): Double? {
        if (durationSec <= 0 || candidateDuration == null || candidateDuration <= 0.0) return null

        val delta = abs(candidateDuration - durationSec)
        if (delta > MAX_DURATION_DELTA_SEC) return -1.0

        return when {
            delta <= 2.0 -> 1.00
            delta <= 4.0 -> 0.92
            delta <= 7.0 -> 0.78
            delta <= 10.0 -> 0.60
            else -> 0.35
        }
    }

    private fun titleSimilarity(left: String, right: String): Double {
        return maxOf(
            stringSimilarity(left, right),
            stringSimilarity(stripFeaturing(left), stripFeaturing(right))
        )
    }

    private fun artistSimilarity(left: String, right: String): Double {
        return maxOf(
            stringSimilarity(left, right),
            stringSimilarity(stripFeaturing(left), stripFeaturing(right))
        )
    }

    private fun stringSimilarity(left: String, right: String): Double {
        val a = normalizeForMatch(left)
        val b = normalizeForMatch(right)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0

        val compactA = a.replace(" ", "")
        val compactB = b.replace(" ", "")
        if (compactA == compactB) return 1.0

        val dice = bigramDice(compactA, compactB)
        val token = tokenJaccard(a, b)
        val containment = if (compactA.contains(compactB) || compactB.contains(compactA)) {
            min(compactA.length, compactB.length).toDouble() /
                maxOf(compactA.length, compactB.length).toDouble()
        } else {
            0.0
        }

        return maxOf(
            dice,
            (dice * 0.72) + (token * 0.28),
            containment * 0.92
        ).coerceIn(0.0, 1.0)
    }

    private fun tokenJaccard(left: String, right: String): Double {
        val a = left.split(' ').filter { it.isNotBlank() }.toSet()
        val b = right.split(' ').filter { it.isNotBlank() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val union = a union b
        if (union.isEmpty()) return 0.0
        return (a intersect b).size.toDouble() / union.size.toDouble()
    }

    private fun bigramDice(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.length < 2 || right.length < 2) return 0.0

        val leftCounts = HashMap<String, Int>()
        for (i in 0 until left.length - 1) {
            val gram = left.substring(i, i + 2)
            leftCounts[gram] = (leftCounts[gram] ?: 0) + 1
        }

        val rightCounts = HashMap<String, Int>()
        for (i in 0 until right.length - 1) {
            val gram = right.substring(i, i + 2)
            rightCounts[gram] = (rightCounts[gram] ?: 0) + 1
        }

        var overlap = 0
        for ((gram, leftCount) in leftCounts) {
            val rightCount = rightCounts[gram] ?: continue
            overlap += min(leftCount, rightCount)
        }

        val leftTotal = left.length - 1
        val rightTotal = right.length - 1
        return (2.0 * overlap) / (leftTotal + rightTotal).toDouble()
    }

    private fun normalizeForMatch(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('`', '\'')
            .replace('&', ' ')
            .replace(NON_WORD, " ")
            .replace(MULTI_SPACE, " ")
            .trim()
    }

    private fun stripFeaturing(value: String): String {
        return value.replace(FEAT_SUFFIX, "").trim().ifBlank { value.trim() }
    }

    private fun getExact(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LrcLibResponse? {
        val urlBuilder = "$BASE_URL/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)
            .addQueryParameter("artist_name", artistName)
            .addQueryParameter("album_name", albumName)
            .addQueryParameter("duration", durationSec.toString())

        return executeSingle(urlBuilder.build().toString())
    }

    private fun searchAll(
        trackName: String,
        artistName: String?,
        albumName: String?
    ): List<LrcLibResponse> {
        val urlBuilder = "$BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)

        if (!artistName.isNullOrBlank()) {
            urlBuilder.addQueryParameter("artist_name", artistName)
        }
        if (!albumName.isNullOrBlank()) {
            urlBuilder.addQueryParameter("album_name", albumName)
        }

        return executeList(urlBuilder.build().toString())
    }

    private fun searchFreeText(query: String): List<LrcLibResponse> {
        val url = "$BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
            .toString()
        return executeList(url)
    }

    private fun executeSingle(url: String): LrcLibResponse? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                gson.fromJson(response.body?.string(), LrcLibResponse::class.java)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun executeList(url: String): List<LrcLibResponse> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val type = object : TypeToken<List<LrcLibResponse>>() {}.type
                gson.fromJson<List<LrcLibResponse>>(response.body?.string(), type) ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
