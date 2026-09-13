package com.autolyrics.lyrics

import android.os.SystemClock
import android.util.Log
import com.autolyrics.model.LyricLine
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TranslationResult(
    val translatedLines: List<String>,
    val detectedLanguage: String
)

object LyricsTranslator {

    enum class Phase {
        IDLE,
        DETECTING_LANGUAGE,
        CHECKING_MODEL,
        DOWNLOADING_MODEL,
        TRANSLATING,
        READY,
        ALREADY_ENGLISH,
        UNSUPPORTED_LANGUAGE,
        DOWNLOAD_FAILED,
        DOWNLOAD_TIMED_OUT,
        TRANSLATION_FAILED
    }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val sourceLanguage: String? = null,
        val error: String? = null
    )

    private const val TAG = "LyricsTranslator"
    private const val MODEL_DOWNLOAD_TIMEOUT_MS = 5L * 60 * 1000
    private const val MODEL_POLL_INTERVAL_MS = 2_000L
    private const val MODEL_CHECK_TIMEOUT_MS = 10_000L
    private const val MODEL_POLL_LOG_INTERVAL = 5

    // Model downloads belong to application-level translation infrastructure rather
    // than to one track. This scope deliberately survives cancellation of a track's
    // translationJob so changing to English/no-lyrics content cannot stop the model
    // download monitor or its timeout/retry state.
    private val modelDownloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ML Kit model download Tasks are not cancellable by coroutines. Keep the active
    // Task per language so changing tracks only replaces the waiter; the underlying
    // model download continues and the newest track can reuse it.
    private val activeModelDownloads = ConcurrentHashMap<String, Task<Void>>()

    // The polling/timeout monitor is also shared per language and runs in the
    // application-level scope above. Awaiting it from a track coroutine does not make
    // the monitor a child of that track coroutine.
    private val activeModelMonitors = ConcurrentHashMap<String, Deferred<Boolean>>()

    // A retryable failure stays latched until the user explicitly presses Retry.
    // This prevents every track change from silently re-running a failed model
    // download/translation attempt. Retry clears the latch, while any still-running
    // ML Kit model Task remains available for reuse.
    private val retryRequired = ConcurrentHashMap<String, UiState>()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun resetUiState() {
        _uiState.value = UiState()
    }

    fun prepareManualRetry() {
        val retryStates = retryRequired.values.toList()
        if (retryStates.isNotEmpty()) {
            Log.d(TAG, "manual retry enabled; clearing ${retryStates.size} retry gate(s)")
            retryRequired.clear()
        }
        resetUiState()

        // Restart model monitoring independently of the current song. This matters
        // when Retry is pressed while the current track is English or has no lyrics.
        retryStates
            .filter {
                it.phase == Phase.DOWNLOAD_FAILED ||
                    it.phase == Phase.DOWNLOAD_TIMED_OUT
            }
            .mapNotNull { it.sourceLanguage }
            .distinct()
            .forEach { language ->
                startModelDownloadMonitor(language)
            }
    }

    suspend fun translateLines(lines: List<LyricLine>): TranslationResult? {
        retryRequired.values.firstOrNull()?.let { blockedState ->
            Log.d(
                TAG,
                "automatic translation retry suppressed until user presses Retry: " +
                    "language=${blockedState.sourceLanguage} phase=${blockedState.phase}"
            )
            _uiState.value = blockedState
            return null
        }

        Log.d(TAG, "translateLines start: lines=${lines.size}")
        updateState(Phase.DETECTING_LANGUAGE)

        val sampleText = lines
            .map { it.text }
            .filter { it.isNotBlank() && it != "♪" }
            .take(5)
            .joinToString("\n")

        if (sampleText.isBlank()) {
            val reason = "No translatable lyric text"
            Log.w(TAG, "skip: $reason")
            updateState(Phase.TRANSLATION_FAILED, error = reason)
            return null
        }

        Log.d(TAG, "detecting source language")
        val langCode = detectLanguage(sampleText)
        if (langCode == null) {
            val reason = "Language detection failed"
            Log.w(TAG, "skip: $reason")
            updateState(Phase.TRANSLATION_FAILED, error = reason)
            return null
        }
        Log.d(TAG, "detected source language=$langCode")

        if (langCode == "en") {
            Log.d(TAG, "skip: source is already English")
            updateState(Phase.ALREADY_ENGLISH, sourceLanguage = langCode)
            return null
        }
        if (langCode == "und") {
            val reason = "Could not determine lyric language"
            Log.w(TAG, "skip: $reason")
            updateState(Phase.UNSUPPORTED_LANGUAGE, sourceLanguage = langCode, error = reason)
            return null
        }

        val mlLang = TranslateLanguage.fromLanguageTag(langCode)
        if (mlLang == null) {
            val reason = "ML Kit does not support source language '$langCode'"
            Log.w(TAG, "skip: $reason")
            updateState(Phase.UNSUPPORTED_LANGUAGE, sourceLanguage = langCode, error = reason)
            return null
        }

        val remoteModelManager = RemoteModelManager.getInstance()
        val remoteModel = TranslateRemoteModel.Builder(mlLang).build()

        updateState(Phase.CHECKING_MODEL, sourceLanguage = langCode)
        val modelDownloaded = try {
            isModelDownloaded(remoteModelManager, remoteModel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.localizedMessage ?: e.javaClass.simpleName
            Log.e(TAG, "translation model check failed", e)
            markRetryRequired(
                language = langCode,
                phase = Phase.DOWNLOAD_FAILED,
                reason = reason
            )
            return null
        }

        if (modelDownloaded) {
            retryRequired.remove(langCode)
            Log.d(TAG, "translation model already downloaded: $mlLang")
        } else {
            Log.d(TAG, "translation model missing; ensuring explicit download: $mlLang")
            try {
                val becameAvailable = waitForModelDownload(
                    manager = remoteModelManager,
                    model = remoteModel,
                    languageKey = mlLang,
                    sourceLanguage = langCode,
                    conditions = DownloadConditions.Builder().build()
                )
                if (!becameAvailable) {
                    // The shared monitor owns timeout/failure state, including Retry.
                    return null
                }
                retryRequired.remove(langCode)
                Log.d(TAG, "translation model confirmed available: $mlLang")
            } catch (e: CancellationException) {
                // Only this track's waiter is cancelled. The application-level model
                // monitor keeps running and continues updating/logging download state.
                throw e
            }
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlLang)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        val translator = Translation.getClient(options)

        try {
            updateState(Phase.TRANSLATING, sourceLanguage = langCode)
            var failures = 0
            var attempted = 0
            val translated = lines.map { line ->
                val text = line.text.trim()
                if (text.isBlank() || text == "♪") {
                    ""
                } else {
                    attempted++
                    try {
                        translateText(translator, text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures++
                        Log.w(TAG, "line translation failed; keeping original", e)
                        text
                    }
                }
            }

            if (attempted > 0 && failures == attempted) {
                val reason = "All lyric lines failed to translate"
                Log.e(TAG, reason)
                markRetryRequired(
                    language = langCode,
                    phase = Phase.TRANSLATION_FAILED,
                    reason = reason
                )
                return null
            }

            Log.d(TAG, "translation complete: lines=${translated.size}, failures=$failures")
            retryRequired.remove(langCode)
            updateState(Phase.READY, sourceLanguage = langCode)
            return TranslationResult(translated, langCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.localizedMessage ?: e.javaClass.simpleName
            Log.e(TAG, "translation failed", e)
            markRetryRequired(
                language = langCode,
                phase = Phase.TRANSLATION_FAILED,
                reason = reason
            )
            return null
        } finally {
            translator.close()
        }
    }

    private fun markRetryRequired(
        language: String,
        phase: Phase,
        reason: String
    ) {
        val state = UiState(
            phase = phase,
            sourceLanguage = language,
            error = reason
        )
        retryRequired[language] = state
        _uiState.value = state
    }

    private fun updateState(
        phase: Phase,
        sourceLanguage: String? = null,
        error: String? = null
    ) {
        _uiState.value = UiState(phase, sourceLanguage, error)
    }

    private suspend fun detectLanguage(text: String): String? {
        val identifier = LanguageIdentification.getClient()
        return try {
            suspendCancellableCoroutine { cont ->
                identifier.identifyLanguage(text)
                    .addOnSuccessListener { language ->
                        if (cont.isActive) cont.resume(language)
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "language identification failed", error)
                        if (cont.isActive) cont.resume(null)
                    }
                    .addOnCanceledListener {
                        cont.cancel()
                    }
            }
        } finally {
            identifier.close()
        }
    }

    private suspend fun isModelDownloaded(
        manager: RemoteModelManager,
        model: TranslateRemoteModel
    ): Boolean = suspendCancellableCoroutine { cont ->
        manager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (cont.isActive) cont.resume(downloaded)
            }
            .addOnFailureListener { error ->
                if (cont.isActive) cont.resumeWithException(error)
            }
            .addOnCanceledListener {
                cont.cancel()
            }
    }

    private fun getOrStartDownloadTask(
        manager: RemoteModelManager,
        model: TranslateRemoteModel,
        languageKey: String,
        conditions: DownloadConditions
    ): Task<Void> {
        synchronized(activeModelDownloads) {
            activeModelDownloads[languageKey]?.let { existing ->
                if (!existing.isComplete) {
                    Log.d(TAG, "reusing active model download task: $languageKey")
                    return existing
                }
                activeModelDownloads.remove(languageKey, existing)
            }

            Log.d(TAG, "starting model download task: $languageKey")
            val task = manager.download(model, conditions)
            activeModelDownloads[languageKey] = task
            task.addOnSuccessListener {
                Log.d(TAG, "model download task reported success: $languageKey")
                activeModelDownloads.remove(languageKey, task)
            }
            task.addOnFailureListener { error ->
                Log.e(TAG, "model download task reported failure: $languageKey", error)
                activeModelDownloads.remove(languageKey, task)
            }
            task.addOnCanceledListener {
                Log.w(TAG, "model download task reported cancellation: $languageKey")
                activeModelDownloads.remove(languageKey, task)
            }
            return task
        }
    }

    private fun startModelDownloadMonitor(sourceLanguage: String): Deferred<Boolean>? {
        val mlLang = TranslateLanguage.fromLanguageTag(sourceLanguage) ?: return null
        val manager = RemoteModelManager.getInstance()
        val model = TranslateRemoteModel.Builder(mlLang).build()
        return getOrStartModelMonitor(
            manager = manager,
            model = model,
            languageKey = mlLang,
            sourceLanguage = sourceLanguage,
            conditions = DownloadConditions.Builder().build()
        )
    }

    private fun getOrStartModelMonitor(
        manager: RemoteModelManager,
        model: TranslateRemoteModel,
        languageKey: String,
        sourceLanguage: String,
        conditions: DownloadConditions
    ): Deferred<Boolean> {
        synchronized(activeModelMonitors) {
            activeModelMonitors[languageKey]?.let { existing ->
                if (!existing.isCompleted) {
                    Log.d(TAG, "reusing active model download monitor: $languageKey")
                    return existing
                }
                activeModelMonitors.remove(languageKey, existing)
            }

            val monitor = modelDownloadScope.async {
                monitorModelDownload(
                    manager = manager,
                    model = model,
                    languageKey = languageKey,
                    sourceLanguage = sourceLanguage,
                    conditions = conditions
                )
            }
            activeModelMonitors[languageKey] = monitor
            monitor.invokeOnCompletion {
                activeModelMonitors.remove(languageKey, monitor)
            }
            return monitor
        }
    }

    private suspend fun waitForModelDownload(
        manager: RemoteModelManager,
        model: TranslateRemoteModel,
        languageKey: String,
        sourceLanguage: String,
        conditions: DownloadConditions
    ): Boolean {
        val monitor = getOrStartModelMonitor(
            manager = manager,
            model = model,
            languageKey = languageKey,
            sourceLanguage = sourceLanguage,
            conditions = conditions
        )
        return monitor.await()
    }

    private suspend fun monitorModelDownload(
        manager: RemoteModelManager,
        model: TranslateRemoteModel,
        languageKey: String,
        sourceLanguage: String,
        conditions: DownloadConditions
    ): Boolean {
        val task = getOrStartDownloadTask(manager, model, languageKey, conditions)
        val startedAt = SystemClock.elapsedRealtime()
        var pollCount = 0

        updateState(Phase.DOWNLOADING_MODEL, sourceLanguage = sourceLanguage)

        while (SystemClock.elapsedRealtime() - startedAt < MODEL_DOWNLOAD_TIMEOUT_MS) {
            // Re-publish the download state so a track change to English/no-lyrics
            // cannot make the Phone UI look as though the background model download
            // has stopped. The progress indicator remains indeterminate by design.
            updateState(Phase.DOWNLOADING_MODEL, sourceLanguage = sourceLanguage)

            val downloaded = withTimeoutOrNull(MODEL_CHECK_TIMEOUT_MS) {
                isModelDownloaded(manager, model)
            }
            if (downloaded == true) {
                Log.d(TAG, "model became available while polling: $languageKey")
                activeModelDownloads.remove(languageKey, task)
                retryRequired.remove(sourceLanguage)
                if (_uiState.value.phase == Phase.DOWNLOADING_MODEL &&
                    _uiState.value.sourceLanguage == sourceLanguage
                ) {
                    resetUiState()
                }
                return true
            }

            if (task.isComplete && !task.isSuccessful) {
                val reason = task.exception?.localizedMessage
                    ?: task.exception?.javaClass?.simpleName
                    ?: "Model download task failed"
                Log.e(TAG, "translation model download failed: $languageKey", task.exception)
                markRetryRequired(
                    language = sourceLanguage,
                    phase = Phase.DOWNLOAD_FAILED,
                    reason = reason
                )
                return false
            }

            pollCount++
            if (pollCount % MODEL_POLL_LOG_INTERVAL == 0) {
                val elapsedSec = (SystemClock.elapsedRealtime() - startedAt) / 1000
                Log.d(
                    TAG,
                    "model download still pending: $languageKey elapsed=${elapsedSec}s " +
                        "taskComplete=${task.isComplete}"
                )
            }
            delay(MODEL_POLL_INTERVAL_MS)
        }

        // One final authoritative check before showing Retry. The underlying ML Kit
        // Task may finish independently of the polling cadence.
        val finalDownloaded = withTimeoutOrNull(MODEL_CHECK_TIMEOUT_MS) {
            isModelDownloaded(manager, model)
        } == true
        if (finalDownloaded) {
            Log.d(TAG, "model became available on final check: $languageKey")
            activeModelDownloads.remove(languageKey, task)
            retryRequired.remove(sourceLanguage)
            if (_uiState.value.phase == Phase.DOWNLOADING_MODEL &&
                _uiState.value.sourceLanguage == sourceLanguage
            ) {
                resetUiState()
            }
            return true
        }

        val reason = "Model still unavailable after ${MODEL_DOWNLOAD_TIMEOUT_MS / 60_000} min"
        Log.e(TAG, reason)
        markRetryRequired(
            language = sourceLanguage,
            phase = Phase.DOWNLOAD_TIMED_OUT,
            reason = reason
        )
        return false
    }

    private suspend fun translateText(
        translator: com.google.mlkit.nl.translate.Translator,
        text: String
    ): String = suspendCancellableCoroutine { cont ->
        translator.translate(text)
            .addOnSuccessListener { translated ->
                if (cont.isActive) cont.resume(translated)
            }
            .addOnFailureListener { error ->
                if (cont.isActive) cont.resumeWithException(error)
            }
            .addOnCanceledListener {
                cont.cancel()
            }
    }
}
