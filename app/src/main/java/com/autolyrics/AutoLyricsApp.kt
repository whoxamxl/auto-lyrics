package com.autolyrics

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.autolyrics.auto.LyricsBrowserService
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.media.MediaTracker

class AutoLyricsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LyricsTranslator.init(this)
        MediaTracker.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                if (activity is AppCompatActivity) {
                    TranslationStatusView.install(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        try {
            startService(Intent(this, LyricsBrowserService::class.java))
        } catch (_: Exception) { }
    }
}
