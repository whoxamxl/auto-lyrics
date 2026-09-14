package com.autolyrics

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TranslationStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private data class DownloadManagerDebugInfo(
        val expectedFiles: Set<String>,
        val matchedFile: String? = null,
        val status: Int? = null,
        val bytesDownloaded: Long? = null,
        val totalBytes: Long? = null,
        val error: String? = null
    )

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
        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4A252C"))
        setTextColor(Color.parseColor("#D7A0A8"))
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

    private val debugText = TextView(context).apply {
        setTextColor(Color.parseColor("#777792"))
        textSize = 9f
        typeface = Typeface.MONOSPACE
        visibility = View.GONE
        setPadding(0, dp(6), 0, 0)
    }

    private var observationJob: Job? = null
    private var debugDiagnosticsJob: Job? = null
    private var hostActivity: AppCompatActivity? = null

    private val elapsedRefreshRunnable = Runnable {
        render(LyricsTranslator.uiState.value)
    }

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

        if (BuildConfig.DEBUG) {
            addView(
                debugText,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        render(LyricsTranslator.uiState.value)

        val activity = context as? AppCompatActivity ?: return
        hostActivity = activity
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
        removeCallbacks(elapsedRefreshRunnable)
        debugDiagnosticsJob?.cancel()
        debugDiagnosticsJob = null
        hostActivity = null
        observationJob?.cancel()
        observationJob = null
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onDetachedFromWindow()
    }

    private fun render(state: LyricsTranslator.UiState) {
        removeCallbacks(elapsedRefreshRunnable)

        if (!prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)) {
            progressBar.hide()
            hideDebugDiagnostics()
            visibility = View.GONE
            return
        }

        val language = languageName(state.sourceLanguage)
        val running = when (state.phase) {
            LyricsTranslator.Phase.DETECTING_LANGUAGE,
            LyricsTranslator.Phase.CHECKING_MODEL,
            LyricsTranslator.Phase.DOWNLOADING_MODEL,
            LyricsTranslator.Phase.WAITING_FOR_SYSTEM,
            LyricsTranslator.Phase.TRANSLATING -> true
            else -> false
        }

        val canRetry = when (state.phase) {
            LyricsTranslator.Phase.DOWNLOAD_FAILED,
            LyricsTranslator.Phase.DOWNLOAD_TIMED_OUT,
            LyricsTranslator.Phase.TRANSLATION_FAILED -> true
            else -> false
        }

        if (state.phase == LyricsTranslator.Phase.READY ||
            state.phase == LyricsTranslator.Phase.DOWNLOAD_FAILED ||
            state.phase == LyricsTranslator.Phase.DOWNLOAD_TIMED_OUT
        ) {
            state.sourceLanguage?.let { downloadStartedAtByLanguage.remove(it) }
        }

        val modelOperation = isModelOperationPhase(state.phase)
        val elapsedMs = if (modelOperation) {
            currentDownloadElapsedMs(state.sourceLanguage)
        } else {
            null
        }

        val text = when (state.phase) {
            LyricsTranslator.Phase.IDLE -> null
            LyricsTranslator.Phase.DETECTING_LANGUAGE -> "Detecting language…"
            LyricsTranslator.Phase.CHECKING_MODEL ->
                "Checking ${language ?: "translation"} → English model…"
            LyricsTranslator.Phase.DOWNLOADING_MODEL ->
                "Downloading ${language ?: "translation"} → English model…\n" +
                    "Elapsed ${formatElapsed(elapsedMs ?: 0L)}"
            LyricsTranslator.Phase.WAITING_FOR_SYSTEM ->
                "Waiting to download ${language ?: "translation"} → English model…\n" +
                    "System thermal limit · Elapsed ${formatElapsed(elapsedMs ?: 0L)}"
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
            hideDebugDiagnostics()
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

        if (modelOperation) {
            if (BuildConfig.DEBUG && state.sourceLanguage != null) {
                refreshDebugDiagnostics(state.sourceLanguage, elapsedMs ?: 0L)
            }
            postDelayed(elapsedRefreshRunnable, ELAPSED_REFRESH_MS)
        } else {
            hideDebugDiagnostics()
        }
    }

    private fun retryTranslation() {
        retryButton.isEnabled = false
        LyricsTranslator.uiState.value.sourceLanguage?.let {
            downloadStartedAtByLanguage.remove(it)
        }

        // Clear the explicit per-language retry gate first. Toggling the preference
        // then asks MediaTracker to re-run translation for the currently loaded track.
        // prepareManualRetry() also restarts a failed model monitor independently, so
        // Retry still works if the current track is English or has no lyrics.
        LyricsTranslator.prepareManualRetry()
        prefs.edit().putBoolean(TRANSLATION_ENABLED_KEY, false).apply()
        postDelayed({
            prefs.edit().putBoolean(TRANSLATION_ENABLED_KEY, true).apply()
            retryButton.isEnabled = true
        }, RETRY_TOGGLE_DELAY_MS)
    }

    private fun refreshDebugDiagnostics(sourceLanguage: String, elapsedMs: Long) {
        if (!BuildConfig.DEBUG || debugDiagnosticsJob?.isActive == true) return
        val activity = hostActivity ?: return

        debugDiagnosticsJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            val info = queryDownloadManager(sourceLanguage)
            val thermalStatus = currentThermalStatus()
            val debug = buildDebugText(info, elapsedMs, thermalStatus)
            withContext(Dispatchers.Main) {
                val current = LyricsTranslator.uiState.value
                if (isAttachedToWindow &&
                    isModelOperationPhase(current.phase) &&
                    current.sourceLanguage == sourceLanguage
                ) {
                    debugText.text = debug
                    debugText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun hideDebugDiagnostics() {
        debugDiagnosticsJob?.cancel()
        debugDiagnosticsJob = null
        debugText.visibility = View.GONE
    }

    private fun queryDownloadManager(sourceLanguage: String): DownloadManagerDebugInfo {
        val expectedFiles = expectedModelFileNames(sourceLanguage)
        if (expectedFiles.isEmpty()) {
            return DownloadManagerDebugInfo(
                expectedFiles = emptySet(),
                error = "no filename candidates for '$sourceLanguage'"
            )
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return DownloadManagerDebugInfo(
                expectedFiles = expectedFiles,
                error = "DownloadManager unavailable"
            )

        return try {
            manager.query(DownloadManager.Query()).use { cursor ->
                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
                val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val bytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val modifiedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)

                var bestFile: String? = null
                var bestStatus: Int? = null
                var bestBytes: Long? = null
                var bestTotal: Long? = null
                var bestModified = Long.MIN_VALUE

                while (cursor.moveToNext()) {
                    val uri = if (uriIndex >= 0) cursor.getString(uriIndex).orEmpty() else ""
                    val title = if (titleIndex >= 0) cursor.getString(titleIndex).orEmpty() else ""
                    val uriFile = uri.substringAfterLast('/').substringBefore('?').lowercase(Locale.US)
                    val titleFile = title.lowercase(Locale.US)
                    val matchedFile = expectedFiles.firstOrNull { candidate ->
                        uriFile == candidate || titleFile == candidate ||
                            uri.lowercase(Locale.US).endsWith("/$candidate")
                    } ?: continue

                    val modified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L
                    val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else null
                    val isActive = status == DownloadManager.STATUS_PENDING ||
                        status == DownloadManager.STATUS_RUNNING ||
                        status == DownloadManager.STATUS_PAUSED
                    val bestIsActive = bestStatus == DownloadManager.STATUS_PENDING ||
                        bestStatus == DownloadManager.STATUS_RUNNING ||
                        bestStatus == DownloadManager.STATUS_PAUSED

                    if (bestFile == null || (isActive && !bestIsActive) ||
                        (isActive == bestIsActive && modified >= bestModified)
                    ) {
                        bestFile = matchedFile
                        bestStatus = status
                        bestBytes = if (bytesIndex >= 0) cursor.getLong(bytesIndex) else null
                        bestTotal = if (totalIndex >= 0) cursor.getLong(totalIndex) else null
                        bestModified = modified
                    }
                }

                DownloadManagerDebugInfo(
                    expectedFiles = expectedFiles,
                    matchedFile = bestFile,
                    status = bestStatus,
                    bytesDownloaded = bestBytes,
                    totalBytes = bestTotal
                )
            }
        } catch (e: Exception) {
            DownloadManagerDebugInfo(
                expectedFiles = expectedFiles,
                error = "${e.javaClass.simpleName}: ${e.localizedMessage ?: "query failed"}"
            )
        }
    }

    private fun expectedModelFileNames(sourceLanguage: String): Set<String> {
        val normalized = Locale.forLanguageTag(sourceLanguage).language
            .ifBlank { sourceLanguage.substringBefore('-') }
            .lowercase(Locale.US)
        if (normalized.isBlank() || normalized == "en") return emptySet()

        val sourceCodes = when (normalized) {
            // Some Android/ML Kit download URLs still use legacy Java language codes.
            "he" -> setOf("he", "iw")
            "id" -> setOf("id", "in")
            "yi" -> setOf("yi", "ji")
            else -> setOf(normalized)
        }

        return sourceCodes.mapTo(linkedSetOf()) { sourceCode ->
            listOf(sourceCode, "en")
                .sorted()
                .joinToString("_") + ".zip"
        }
    }

    private fun buildDebugText(
        info: DownloadManagerDebugInfo,
        elapsedMs: Long,
        thermalStatus: Int?
    ): String {
        val downloadLine = when {
            info.error != null -> "DownloadManager: ${info.error}"
            info.matchedFile == null ->
                "DownloadManager: no matching ${info.expectedFiles.joinToString(" / ")} row"
            else -> {
                val downloaded = formatBytes(info.bytesDownloaded)
                val total = formatBytes(info.totalBytes)
                "DownloadManager: $downloaded / $total · ${downloadStatusName(info.status)} · ${info.matchedFile}"
            }
        }

        return buildString {
            append("ML Kit task: pending\n")
            append("Model available: false\n")
            append("Elapsed: ${formatElapsed(elapsedMs)}\n")
            append("Thermal: ${thermalStatusName(thermalStatus)}\n")
            append(downloadLine)
        }
    }

    private fun currentThermalStatus(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.currentThermalStatus
        } catch (_: Exception) {
            null
        }
    }

    private fun thermalStatusName(status: Int?): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "None (0)"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light (1)"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate (2)"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe (3)"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical (4)"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency (5)"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown (6)"
        null -> "Unavailable"
        else -> "status=$status"
    }

    private fun isModelOperationPhase(phase: LyricsTranslator.Phase): Boolean {
        return phase == LyricsTranslator.Phase.DOWNLOADING_MODEL ||
            phase == LyricsTranslator.Phase.WAITING_FOR_SYSTEM
    }

    private fun downloadStatusName(status: Int?): String = when (status) {
        DownloadManager.STATUS_PENDING -> "pending"
        DownloadManager.STATUS_RUNNING -> "running"
        DownloadManager.STATUS_PAUSED -> "paused"
        DownloadManager.STATUS_SUCCESSFUL -> "successful"
        DownloadManager.STATUS_FAILED -> "failed"
        null -> "unknown"
        else -> "status=$status"
    }

    private fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "?"
        return when {
            bytes >= 1024L * 1024L -> String.format(
                Locale.US,
                "%.1f MB",
                bytes.toDouble() / (1024.0 * 1024.0)
            )
            bytes >= 1024L -> String.format(
                Locale.US,
                "%.1f KB",
                bytes.toDouble() / 1024.0
            )
            else -> "$bytes B"
        }
    }

    private fun languageName(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        return Locale.forLanguageTag(code)
            .getDisplayLanguage(Locale.ENGLISH)
            .takeIf { it.isNotBlank() }
            ?: code.uppercase(Locale.ENGLISH)
    }

    private fun currentDownloadElapsedMs(sourceLanguage: String?): Long {
        val key = sourceLanguage ?: UNKNOWN_LANGUAGE_KEY
        val startedAt = downloadStartedAtByLanguage.getOrPut(key) {
            SystemClock.elapsedRealtime()
        }
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        } else {
            "${seconds}s"
        }
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
        private const val ELAPSED_REFRESH_MS = 1_000L
        private const val MAX_ERROR_LENGTH = 90
        private const val VIEW_TAG = "translation_status_view"
        private const val UNKNOWN_LANGUAGE_KEY = "__unknown__"
        private val downloadStartedAtByLanguage = ConcurrentHashMap<String, Long>()

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
