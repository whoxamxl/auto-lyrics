package com.autolyrics.lyrics

import android.util.Log
import com.autolyrics.model.LyricLine
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class TranslationResult(
    val translatedLines: List<String>,
    val detectedLanguage: String
)

object LyricsTranslator {

    private const val TAG = "LyricsTranslator"

    suspend fun translateLines(lines: List<LyricLine>): TranslationResult? {
        Log.d(TAG, "translateLines start: lines=${lines.size}")

        val sampleText = lines
            .map { it.text }
            .filter { it.isNotBlank() && it != "♪" }
            .take(5)
            .joinToString("\n")

        if (sampleText.isBlank()) {
            Log.d(TAG, "skip: no translatable sample text")
            return null
        }

        Log.d(TAG, "detecting source language")
        val langCode = detectLanguage(sampleText)
        if (langCode == null) {
            Log.w(TAG, "skip: language detection failed")
            return null
        }
        Log.d(TAG, "detected source language=$langCode")

        if (langCode == "en") {
            Log.d(TAG, "skip: source is already English")
            return null
        }
        if (langCode == "und") {
            Log.w(TAG, "skip: source language is undetermined")
            return null
        }

        val mlLang = TranslateLanguage.fromLanguageTag(langCode)
        if (mlLang == null) {
            Log.w(TAG, "skip: unsupported ML Kit source language=$langCode")
            return null
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlLang)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        val translator = Translation.getClient(options)

        try {
            Log.d(TAG, "ensuring translation model is downloaded: $mlLang -> en")
            val modelReady = suspendCoroutine { cont ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        Log.d(TAG, "translation model ready")
                        cont.resume(true)
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "translation model download failed", error)
                        cont.resume(false)
                    }
            }
            if (!modelReady) return null

            var failures = 0
            val translated = lines.map { line ->
                val text = line.text.trim()
                if (text.isBlank() || text == "♪") {
                    ""
                } else {
                    suspendCoroutine { cont ->
                        translator.translate(text)
                            .addOnSuccessListener { cont.resume(it) }
                            .addOnFailureListener { error ->
                                failures++
                                Log.w(TAG, "line translation failed; keeping original", error)
                                cont.resume(text)
                            }
                    }
                }
            }

            Log.d(TAG, "translation complete: lines=${translated.size}, failures=$failures")
            return TranslationResult(translated, langCode)
        } finally {
            translator.close()
        }
    }

    private suspend fun detectLanguage(text: String): String? {
        val identifier = LanguageIdentification.getClient()
        return try {
            suspendCoroutine { cont ->
                identifier.identifyLanguage(text)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "language identification failed", error)
                        cont.resume(null)
                    }
            }
        } finally {
            identifier.close()
        }
    }
}
