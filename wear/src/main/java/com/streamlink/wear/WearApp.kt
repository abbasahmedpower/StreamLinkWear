package com.streamlink.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Crash Hardening: Log fatal crashes cleanly
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("WearApp", "Fatal crash on thread: ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
    }
}
