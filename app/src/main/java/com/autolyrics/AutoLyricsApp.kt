package com.autolyrics

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.connection.CarConnection
import com.autolyrics.auto.LyricsBrowserService
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.media.LyricsDemandController
import com.autolyrics.media.MediaTracker

class AutoLyricsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LyricsTranslator.init(this)
        MediaTracker.init(this)

        val mediaTracker = MediaTracker.getInstance(this)
        val lyricsDemand = LyricsDemandController(mediaTracker::setLyricsDemandActive)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                lyricsDemand.onPhoneActivityStarted()
            }

            override fun onActivityResumed(activity: Activity) {
                if (activity is AppCompatActivity) {
                    // Install status first; inserting the target row afterwards pushes
                    // status below it, yielding Toggle -> Language -> Status.
                    TranslationStatusView.install(activity)
                    TranslationTargetView.install(activity)
                    TranslationMetadataBinder.install(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                lyricsDemand.onPhoneActivityStopped()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // Keep provider resolution active for the whole Android Auto projection
        // session, even when the user is viewing a different AA app. Media-session
        // monitoring itself remains active regardless of this demand signal.
        CarConnection(this).type.observeForever { connectionType ->
            lyricsDemand.setCarProjectionConnected(
                connectionType == CarConnection.CONNECTION_TYPE_PROJECTION
            )
        }

        // Preserve the existing MediaBrowserService startup behavior so Android
        // Auto discovery/automatic availability is unchanged by this fetch gate.
        try {
            startService(Intent(this, LyricsBrowserService::class.java))
        } catch (_: Exception) { }
    }
}
