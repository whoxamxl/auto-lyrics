package com.autolyrics.media

/**
 * Tracks whether lyrics are currently useful to a visible phone UI or an
 * Android Auto projection session.
 *
 * Media-session tracking remains independent from this state. The callback is
 * only for gating lyrics/cache/provider work in [MediaTracker].
 */
internal class LyricsDemandController(
    private val onActiveChanged: (Boolean) -> Unit
) {
    private var startedPhoneActivities = 0
    private var carProjectionConnected = false

    var isActive: Boolean = false
        private set

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
        onActiveChanged(next)
    }
}
