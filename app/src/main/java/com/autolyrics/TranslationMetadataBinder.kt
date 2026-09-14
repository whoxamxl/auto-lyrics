package com.autolyrics

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.lyrics.TranslationLanguages
import com.autolyrics.media.MediaTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * Keeps the compact Phone source label aligned with the currently selected
 * translation target without coupling MainActivity to translation settings.
 */
object TranslationMetadataBinder {
    private val jobs = WeakHashMap<AppCompatActivity, Job>()

    fun install(activity: AppCompatActivity) {
        if (jobs[activity]?.isActive == true) return

        val sourceView = activity.findViewById<TextView>(R.id.tv_source) ?: return
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mediaTracker = MediaTracker.getInstance(activity)

        jobs[activity] = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaTracker.state.collect { state ->
                    if (state.source.isBlank()) return@collect

                    val sourceLanguage = TranslationLanguages.normalizeLanguageTag(
                        state.detectedLanguage
                    )
                    val targetLanguage = TranslationLanguages.normalizeTargetLanguage(
                        prefs.getString(
                            TranslationLanguages.TARGET_LANGUAGE_PREF_KEY,
                            TranslationLanguages.DEFAULT_TARGET_LANGUAGE
                        )
                    )

                    // MainActivity also owns this TextView. Posting ensures this
                    // target-aware suffix is applied after its normal state render.
                    sourceView.post {
                        if (state.source.isBlank()) return@post
                        sourceView.text = if (sourceLanguage != null) {
                            "${state.source} · $sourceLanguage→$targetLanguage"
                        } else {
                            state.source
                        }
                    }
                }
            }
        }
    }

    private const val PREFS_NAME = "auto_lyrics_prefs"
}
