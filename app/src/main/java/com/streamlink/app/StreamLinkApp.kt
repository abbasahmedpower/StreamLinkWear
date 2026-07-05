package com.streamlink.app

import android.app.Application
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.SessionBrain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StreamLinkApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        
        // Crash Hardening: Log fatal crashes cleanly
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("StreamLinkApp", "Fatal crash on thread: ${thread.name}", throwable)
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

        appScope.launch {
            GlobalStreamState.resetSafe()
            SessionBrain.reset()
        }
    }
}
