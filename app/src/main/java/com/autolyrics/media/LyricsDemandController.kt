package com.autolyrics.media

/**
 * Process-wide demand state for lyric work.
 *
 * Demand is active while at least one phone Activity is started or while the
 * device is projecting to Android Auto. MediaListenerService keeps monitoring
 * sessions independently and only forwards them to MediaTracker while this
 * demand is active.
 */
internal object LyricsDemandController {
    private var startedPhoneActivities = 0
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

    fun onPhoneActivityStarted() {
        startedPhoneActivities += 1
        publishIfChanged()
    }

    fun onPhoneActivityStopped() {
        if (startedPhoneActivities > 0) {
            startedPhoneActivities -= 1
        }
        publishIfChanged()
    }

    fun setCarProjectionConnected(connected: Boolean) {
        if (carProjectionConnected == connected) return
        carProjectionConnected = connected
        publishIfChanged()
    }

    private fun publishIfChanged() {
        val next = startedPhoneActivities > 0 || carProjectionConnected
        if (next == isActive) return

        isActive = next
        listeners.toList().forEach { it(next) }
    }

    internal fun resetForTest() {
        startedPhoneActivities = 0
        carProjectionConnected = false
        isActive = false
        listeners.clear()
    }
}
