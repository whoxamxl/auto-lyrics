package com.autolyrics.media

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.autolyrics.BuildConfig
import com.autolyrics.lyrics.LrcLibClient
import com.autolyrics.lyrics.LrcParser
import com.autolyrics.lyrics.LyricsCache
import com.autolyrics.lyrics.LyricsProviderCandidate
import com.autolyrics.lyrics.LyricsProviderResolver
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.lyrics.MetadataCleaner
import com.autolyrics.lyrics.PetitLyricsClient
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.autolyrics.util.AlbumColorExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaTracker private constructor(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auto_lyrics_prefs", Context.MODE_PRIVATE)
    private val lyricsCache = LyricsCache(context)

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private var activeController: MediaController? = null
    private var lastPositionMs: Long = 0
    private var lastPositionUpdateTime: Long = 0
    private var playbackSpeed: Float = 1.0f
    private var fetchJob: Job? = null
    private var artJob: Job? = null
    private var translationJob: Job? = null
    private var pendingTrack: TrackInfo? = null
    private var pendingArt: Bitmap? = null
    private var lyricsOffsetMs: Long = 0L

    init {
        lyricsOffsetMs = prefs.getLong("lyrics_offset_ms", 0L)
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
    }

    private val positionChecker = object : Runnable {
        override fun run() {
            updateCurrentPosition()
            if (_state.value.isPlaying) {
                handler.postDelayed(this, 150)
            }
        }
    }

    private val trackChangeRunnable = Runnable {
        val track = pendingTrack ?: return@Runnable
        val art = pendingArt
        val current = _state.value.track
        if (track == current) return@Runnable

        translationJob?.cancel()
        _state.value = _state.value.copy(
            track = track,
            lines = emptyList(),
            currentIndex = -1,
            currentWordIndex = -1,
            status = LyricsStatus.LOADING,
            source = "",
            albumArt = art,
            albumColors = null,
            translatedLines = null,
            detectedLanguage = null
        )
        fetchLyrics(track)
        extractAlbumColors(art)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadataChanged(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateChanged(state)
        }

        override fun onSessionDestroyed() {
            onMediaSessionChanged(null)
        }
    }

    fun adjustOffset(deltaMs: Long) {
        lyricsOffsetMs += deltaMs
        prefs.edit().putLong("lyrics_offset_ms", lyricsOffsetMs).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resetOffset() {
        lyricsOffsetMs = 0L
        prefs.edit().putLong("lyrics_offset_ms", 0L).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resumePlayback() {
        activeController?.transportControls?.play()
    }

    fun setOffset(ms: Long) {
        lyricsOffsetMs = ms
        prefs.edit().putLong("lyrics_offset_ms", ms).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun getCurrentPositionMs(): Long {
        val basePos = if (!_state.value.isPlaying) {
            lastPositionMs
        } else {
            val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
            lastPositionMs + (elapsed * playbackSpeed).toLong()
        }
        return basePos + lyricsOffsetMs
    }

    private fun updateCurrentPosition() {
        val currentState = _state.value
        val lines = currentState.lines
        if (lines.isEmpty() || currentState.status != LyricsStatus.FOUND) return

        val posMs = getCurrentPositionMs()

        var newLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) {
                newLineIndex = i
            } else {
                break
            }
        }

        var newWordIndex = -1
        if (newLineIndex >= 0) {
            val words = lines[newLineIndex].words
            if (words.isNotEmpty()) {
                for (i in words.indices) {
                    if (words[i].timeMs <= posMs) {
                        newWordIndex = i
                    } else {
                        break
                    }
                }
            }
        }

        if (newLineIndex != currentState.currentIndex || newWordIndex != currentState.currentWordIndex) {
            _state.value = currentState.copy(
                currentIndex = newLineIndex,
                currentWordIndex = newWordIndex
            )
        }
    }

    fun onMediaSessionChanged(controller: MediaController?) {
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller

        if (controller == null) {
            handler.removeCallbacks(positionChecker)
            handler.removeCallbacks(trackChangeRunnable)
            artJob?.cancel()
            _state.value = LyricsState(offsetMs = lyricsOffsetMs)
            return
        }

        controller.registerCallback(mediaCallback)
        handleMetadataChanged(controller.metadata)
        handlePlaybackStateChanged(controller.playbackState)
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) return

        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val rawAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val title = MetadataCleaner.cleanTitle(rawTitle)
        val artist = MetadataCleaner.cleanArtist(rawArtist)
        val album = MetadataCleaner.cleanAlbum(rawAlbum)

        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val newTrack = TrackInfo(title, artist, album, duration)
        val current = _state.value.track

        if (current != null && newTrack == current) {
            if (art != null && _state.value.albumArt == null) {
                _state.value = _state.value.copy(albumArt = art)
                extractAlbumColors(art)
            }
            return
        }

        pendingTrack = newTrack
        pendingArt = art
        handler.removeCallbacks(trackChangeRunnable)
        handler.postDelayed(trackChangeRunnable, 600)
    }

    private fun extractAlbumColors(bitmap: Bitmap?) {
        artJob?.cancel()
        if (bitmap == null) return
        artJob = scope.launch(Dispatchers.Default) {
            val colors = AlbumColorExtractor.extract(bitmap)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(albumColors = colors)
            }
        }
    }

    private fun handlePlaybackStateChanged(pbState: PlaybackState?) {
        if (pbState == null) return

        val isPlaying = pbState.state == PlaybackState.STATE_PLAYING
        lastPositionMs = pbState.position
        lastPositionUpdateTime = pbState.lastPositionUpdateTime
        if (lastPositionUpdateTime == 0L) {
            lastPositionUpdateTime = SystemClock.elapsedRealtime()
        }
        playbackSpeed = if (pbState.playbackSpeed > 0) pbState.playbackSpeed else 1.0f

        _state.value = _state.value.copy(isPlaying = isPlaying)

        handler.removeCallbacks(positionChecker)
        if (isPlaying) {
            handler.post(positionChecker)
        }
    }

    private fun fetchLyrics(track: TrackInfo) {
        fetchJob?.cancel()
        fetchJob = scope.launch(Dispatchers.IO) {
            try {
                val cached = lyricsCache.get(track)
                if (cached != null) {
                    val (lines, status, source) = cached
                    withContext(Dispatchers.Main) {
                        if (_state.value.track != track) return@withContext
                        _state.value = _state.value.copy(
                            lines = lines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = status,
                            source = "$source (cached)"
                        )
                        if (status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(lines, track)
                    }

                    val cacheAge = lyricsCache.getAge(track)
                    if (cacheAge < CACHE_REFRESH_MS) {
                        return@launch
                    }
                }

                val result = fetchBestLyrics(track)

                withContext(Dispatchers.Main) {
                    if (_state.value.track != track) return@withContext

                    if (result != null) {
                        lyricsCache.put(track, result.lines, result.status, result.source)
                        _state.value = _state.value.copy(
                            lines = result.lines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = result.status,
                            source = result.source
                        )
                        if (result.status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(result.lines, track)
                    } else if (cached == null) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.NOT_FOUND,
                            source = ""
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track && _state.value.status == LyricsStatus.LOADING) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.ERROR,
                            source = ""
                        )
                    }
                }
            }
        }
    }

    private suspend fun fetchBestLyrics(track: TrackInfo): LyricsProviderCandidate? = coroutineScope {
        // Both providers are independent network sources. Start them together and
        // resolve only after both have had a chance to return a candidate.
        val lrcLibDeferred = async(Dispatchers.IO) { fetchFromLrcLib(track) }
        val petitLyricsDeferred = if (PetitLyricsClient.isConfigured) {
            async(Dispatchers.IO) { fetchFromPetitLyrics(track) }
        } else {
            null
        }

        val candidates = buildList {
            lrcLibDeferred.await()?.let(::add)
            petitLyricsDeferred?.await()?.let(::add)
        }

        val scored = LyricsProviderResolver.scoreCandidates(track, candidates)
        if (BuildConfig.DEBUG) {
            scored.forEach { score ->
                Log.d(
                    PROVIDER_RESOLVER_TAG,
                    "%s metadata=%.3f quality=%.3f confidence=%.3f final=%.3f kind=%s".format(
                        score.candidate.provider,
                        score.metadataScore,
                        score.qualityScore,
                        score.sourceConfidence,
                        score.finalScore,
                        score.candidate.syncKind
                    )
                )
            }
        }

        val selected = LyricsProviderResolver.selectBest(track, candidates)
        if (BuildConfig.DEBUG) {
            Log.d(
                PROVIDER_RESOLVER_TAG,
                selected?.let {
                    "selected=${it.candidate.provider} final=${"%.3f".format(it.finalScore)} " +
                        "kind=${it.candidate.syncKind}"
                } ?: "selected=none"
            )
        }
        selected?.candidate
    }

    private fun fetchFromPetitLyrics(track: TrackInfo): LyricsProviderCandidate? {
        if (!PetitLyricsClient.isConfigured || track.artist.isBlank()) return null

        val result = try {
            PetitLyricsClient.getSyncedLyrics(
                album = track.album,
                artist = track.artist,
                title = track.title
            )
        } catch (_: Exception) {
            null
        } ?: return null

        val hasRealText = result.lines.any { it.text != "♪" && it.text.isNotBlank() }
        if (!hasRealText) return null

        val syncKind = when (result.lyricsType) {
            3 -> LyricsProviderCandidate.SyncKind.WORD_SYNC
            else -> LyricsProviderCandidate.SyncKind.LINE_SYNC
        }

        return LyricsProviderCandidate(
            provider = "PetitLyrics",
            title = result.matchedTitle.ifBlank { track.title },
            artist = result.matchedArtist,
            album = result.matchedAlbum,
            durationSec = result.matchedDurationSec,
            lines = result.lines,
            status = LyricsStatus.FOUND,
            source = "PetitLyrics · Synced",
            syncKind = syncKind
        )
    }

    private fun fetchFromLrcLib(track: TrackInfo): LyricsProviderCandidate? {
        val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0

        val result = try {
            LrcLibClient.getLyrics(
                trackName = track.title,
                artistName = track.artist,
                albumName = track.album,
                durationSec = durationSec
            )
        } catch (_: Exception) {
            null
        } ?: return null

        if (result.syncedLyrics != null) {
            val lines = LrcParser.parse(result.syncedLyrics)
            val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
            if (hasRealText) {
                return LyricsProviderCandidate(
                    provider = "LRCLIB",
                    title = result.trackName.orEmpty().ifBlank { track.title },
                    artist = result.artistName.orEmpty(),
                    album = result.albumName.orEmpty(),
                    durationSec = result.duration,
                    lines = lines,
                    status = LyricsStatus.FOUND,
                    source = "LRCLIB · Synced",
                    syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
                )
            }
        }

        if (result.plainLyrics != null) {
            val lines = result.plainLyrics.lines()
                .filter { it.isNotBlank() }
                .map { text -> LyricLine(0L, text) }
            if (lines.isNotEmpty()) {
                return LyricsProviderCandidate(
                    provider = "LRCLIB",
                    title = result.trackName.orEmpty().ifBlank { track.title },
                    artist = result.artistName.orEmpty(),
                    album = result.albumName.orEmpty(),
                    durationSec = result.duration,
                    lines = lines,
                    status = LyricsStatus.PLAIN_ONLY,
                    source = "LRCLIB · Plain",
                    syncKind = LyricsProviderCandidate.SyncKind.PLAIN
                )
            }
        }

        return null
    }

    private fun translateIfNeeded(lines: List<LyricLine>, track: TrackInfo) {
        if (!prefs.getBoolean("translation_enabled", true)) return
        translationJob?.cancel()
        translationJob = scope.launch(Dispatchers.IO) {
            try {
                val result = LyricsTranslator.translateLines(lines) ?: return@launch
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track) {
                        _state.value = _state.value.copy(
                            translatedLines = result.translatedLines,
                            detectedLanguage = result.detectedLanguage
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }

    companion object {
        private const val CACHE_REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        private const val PROVIDER_RESOLVER_TAG = "ProviderResolver"

        @Volatile
        private var instance: MediaTracker? = null

        fun init(context: Context) {
            getInstance(context)
        }

        fun getInstance(context: Context): MediaTracker {
            return instance ?: synchronized(this) {
                instance ?: MediaTracker(context.applicationContext).also { instance = it }
            }
        }
    }
}
