package com.autolyrics

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.lyrics.LyricsTranslator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class TranslationStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val statusText = TextView(context).apply {
        setTextColor(Color.parseColor("#AAAACC"))
        textSize = 11f
        maxLines = 2
    }

    private val retryButton = Button(context).apply {
        text = "Retry"
        textSize = 10f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minimumHeight = 0
        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2A2A3E"))
        setTextColor(Color.parseColor("#CCCCDD"))
        setPadding(dp(12), 0, dp(12), 0)
        visibility = View.GONE
        setOnClickListener { retryTranslation() }
    }

    private val progressBar = LinearProgressIndicator(context).apply {
        isIndeterminate = true
        setIndicatorColor(Color.parseColor("#BB86FC"))
        trackColor = Color.parseColor("#2A2A3E")
        trackThickness = dp(3)
        visibility = View.GONE
    }

    private var observationJob: Job? = null

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TRANSLATION_ENABLED_KEY) {
                if (!prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)) {
                    LyricsTranslator.resetUiState()
                }
                post { render(LyricsTranslator.uiState.value) }
            }
        }

    init {
        orientation = VERTICAL
        visibility = View.GONE

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            statusText,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            retryButton,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
        )
        addView(
            row,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        addView(
            progressBar,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        render(LyricsTranslator.uiState.value)

        val activity = context as? AppCompatActivity ?: return
        observationJob?.cancel()
        observationJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LyricsTranslator.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        observationJob?.cancel()
        observationJob = null
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onDetachedFromWindow()
    }

    private fun render(state: LyricsTranslator.UiState) {
        if (!prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)) {
            progressBar.hide()
            visibility = View.GONE
            return
        }

        val language = languageName(state.sourceLanguage)
        val running = when (state.phase) {
            LyricsTranslator.Phase.DETECTING_LANGUAGE,
            LyricsTranslator.Phase.CHECKING_MODEL,
            LyricsTranslator.Phase.DOWNLOADING_MODEL,
            LyricsTranslator.Phase.TRANSLATING -> true
            else -> false
        }

        val canRetry = when (state.phase) {
            LyricsTranslator.Phase.DOWNLOAD_FAILED,
            LyricsTranslator.Phase.DOWNLOAD_TIMED_OUT,
            LyricsTranslator.Phase.TRANSLATION_FAILED -> true
            else -> false
        }

        val text = when (state.phase) {
            LyricsTranslator.Phase.IDLE -> null
            LyricsTranslator.Phase.DETECTING_LANGUAGE -> "Detecting language…"
            LyricsTranslator.Phase.CHECKING_MODEL ->
                "Checking ${language ?: "translation"} → English model…"
            LyricsTranslator.Phase.DOWNLOADING_MODEL ->
                "Downloading ${language ?: "translation"} → English model…"
            LyricsTranslator.Phase.TRANSLATING ->
                "Translating ${language ?: "lyrics"} → English…"
            LyricsTranslator.Phase.READY ->
                "${language ?: "Lyrics"} → English"
            LyricsTranslator.Phase.ALREADY_ENGLISH ->
                "Already English"
            LyricsTranslator.Phase.UNSUPPORTED_LANGUAGE ->
                state.error ?: "Unsupported language${state.sourceLanguage?.let { ": $it" } ?: ""}"
            LyricsTranslator.Phase.DOWNLOAD_FAILED ->
                buildFailureText("Model download failed", state.error)
            LyricsTranslator.Phase.DOWNLOAD_TIMED_OUT ->
                buildFailureText("Model download timed out", state.error)
            LyricsTranslator.Phase.TRANSLATION_FAILED ->
                buildFailureText("Translation failed", state.error)
        }

        if (text == null) {
            progressBar.hide()
            visibility = View.GONE
            return
        }

        visibility = View.VISIBLE
        statusText.text = text
        statusText.setTextColor(
            if (canRetry || state.phase == LyricsTranslator.Phase.UNSUPPORTED_LANGUAGE) {
                Color.parseColor("#FFB4AB")
            } else {
                Color.parseColor("#AAAACC")
            }
        )

        if (running) {
            progressBar.show()
        } else {
            progressBar.hide()
        }
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
    }

    private fun retryTranslation() {
        retryButton.isEnabled = false
        LyricsTranslator.resetUiState()

        // MediaTracker already reacts to this preference. Toggle it briefly so the
        // currently loaded lyrics are translated again without changing tracks or
        // re-querying a lyrics provider.
        prefs.edit().putBoolean(TRANSLATION_ENABLED_KEY, false).apply()
        postDelayed({
            prefs.edit().putBoolean(TRANSLATION_ENABLED_KEY, true).apply()
            retryButton.isEnabled = true
        }, RETRY_TOGGLE_DELAY_MS)
    }

    private fun languageName(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        return Locale.forLanguageTag(code)
            .getDisplayLanguage(Locale.ENGLISH)
            .takeIf { it.isNotBlank() }
            ?: code.uppercase(Locale.ENGLISH)
    }

    private fun buildFailureText(prefix: String, error: String?): String {
        val detail = error?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_ERROR_LENGTH)
        return if (detail == null) prefix else "$prefix · $detail"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val PREFS_NAME = "auto_lyrics_prefs"
        private const val TRANSLATION_ENABLED_KEY = "translation_enabled"
        private const val RETRY_TOGGLE_DELAY_MS = 150L
        private const val MAX_ERROR_LENGTH = 90
        private const val VIEW_TAG = "translation_status_view"

        fun install(activity: AppCompatActivity) {
            val translationSwitch = activity.findViewById<View>(R.id.switch_translation) ?: return
            val translationRow = translationSwitch.parent as? ViewGroup ?: return
            val settingsPanel = translationRow.parent as? LinearLayout ?: return

            if (settingsPanel.findViewWithTag<View>(VIEW_TAG) != null) return

            val view = TranslationStatusView(activity).apply {
                tag = VIEW_TAG
            }
            val index = settingsPanel.indexOfChild(translationRow)
            settingsPanel.addView(
                view,
                index + 1,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = view.dp(2)
                    bottomMargin = view.dp(4)
                }
            )
        }
    }
}
