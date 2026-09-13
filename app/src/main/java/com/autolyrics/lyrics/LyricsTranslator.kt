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

    // ML Kit model download Tasks are not cancellable by coroutines. Keep the active
    // Task per language so Retry does not blindly start duplicate downloads while an
    // earlier request may still be running in Google Play services.
    private val activeModelDownloads = ConcurrentHashMap<String, Task<Void>>()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun resetUiState() {
        _uiState.value = UiState()
    }

    suspend fun translateLines(lines: List<LyricLine>): TranslationResult? {
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
            updateState(Phase.DOWNLOAD_FAILED, sourceLanguage = langCode, error = reason)
            return null
        }

        if (modelDownloaded) {
            Log.d(TAG, "translation model already downloaded: $mlLang")
        } else {
            Log.d(TAG, "translation model missing; ensuring explicit download: $mlLang")
            updateState(Phase.DOWNLOADING_MODEL, sourceLanguage = langCode)
            try {
                val becameAvailable = waitForModelDownload(
                    manager = remoteModelManager,
                    model = remoteModel,
                    languageKey = mlLang,
                    conditions = DownloadConditions.Builder().build()
                )
                if (!becameAvailable) {
                    val reason = "Model still unavailable after ${MODEL_DOWNLOAD_TIMEOUT_MS / 60_000} min"
                    Log.e(TAG, reason)
                    updateState(
                        Phase.DOWNLOAD_TIMED_OUT,
                        sourceLanguage = langCode,
                        error = reason
                    )
                    return null
                }
                Log.d(TAG, "translation model confirmed available: $mlLang")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.localizedMessage ?: e.javaClass.simpleName
                Log.e(TAG, "translation model download failed", e)
                updateState(Phase.DOWNLOAD_FAILED, sourceLanguage = langCode, error = reason)
                return null
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
                updateState(Phase.TRANSLATION_FAILED, sourceLanguage = langCode, error = reason)
                return null
            }

            Log.d(TAG, "translation complete: lines=${translated.size}, failures=$failures")
            updateState(Phase.READY, sourceLanguage = langCode)
            return TranslationResult(translated, langCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.localizedMessage ?: e.javaClass.simpleName
            Log.e(TAG, "translation failed", e)
            updateState(Phase.TRANSLATION_FAILED, sourceLanguage = langCode, error = reason)
            return null
        } finally {
            translator.close()
        }
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

    private suspend fun waitForModelDownload(
        manager: RemoteModelManager,
        model: TranslateRemoteModel,
        languageKey: String,
        conditions: DownloadConditions
    ): Boolean {
        val task = getOrStartDownloadTask(manager, model, languageKey, conditions)
        val startedAt = SystemClock.elapsedRealtime()
        var pollCount = 0

        while (SystemClock.elapsedRealtime() - startedAt < MODEL_DOWNLOAD_TIMEOUT_MS) {
            val downloaded = withTimeoutOrNull(MODEL_CHECK_TIMEOUT_MS) {
                isModelDownloaded(manager, model)
            }
            if (downloaded == true) {
                Log.d(TAG, "model became available while polling: $languageKey")
                activeModelDownloads.remove(languageKey, task)
                return true
            }

            if (task.isComplete && !task.isSuccessful) {
                throw task.exception ?: IllegalStateException("Model download task failed")
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
        // Task may finish independently of the coroutine timeout/polling cadence.
        val finalDownloaded = withTimeoutOrNull(MODEL_CHECK_TIMEOUT_MS) {
            isModelDownloaded(manager, model)
        } == true
        if (finalDownloaded) {
            Log.d(TAG, "model became available on final check: $languageKey")
            activeModelDownloads.remove(languageKey, task)
        }
        return finalDownloaded
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
