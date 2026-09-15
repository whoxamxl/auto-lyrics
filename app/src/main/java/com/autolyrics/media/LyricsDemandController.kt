package com.autolyrics.media

/**
 * Process-wide demand state for lyric work.
 *
 * Demand is active while the phone app process is in the foreground or while
 * the device is projecting to Android Auto. MediaListenerService keeps
 * monitoring sessions independently and only forwards them to MediaTracker
 * while this demand is active.
 */
internal object LyricsDemandController {
    private var phoneForeground = false
    private var carProjectionConnected = false
    private val listeners = linkedSetOf<(Boolean) -> Unit>()

    @Volatile
    var isActive: Boolean = false
        private set

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    fun setPhoneForeground(foreground: Boolean) {
        if (phoneForeground == foreground) return
        phoneForeground = foreground
        publishIfChanged()
    }

    fun setCarProjectionConnected(connected: Boolean) {
        if (carProjectionConnected == connected) return
        carProjectionConnected = connected
        publishIfChanged()
    }

    private fun publishIfChanged() {
        val next = phoneForeground || carProjectionConnected
        if (next == isActive) return

        isActive = next
        listeners.toList().forEach { it(next) }
    }

    internal fun resetForTest() {
        phoneForeground = false
        carProjectionConnected = false
        isActive = false
        listeners.clear()
    }
}
