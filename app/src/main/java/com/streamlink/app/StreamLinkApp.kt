package com.streamlink.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.streamlink.app.diagnostics.CrashReporter
import com.streamlink.shared.diagnostics.StartupDiagnostics
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@HiltAndroidApp
class StreamLinkApp : Application(), ComponentCallbacks2 {

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        StartupDiagnostics.step("App.onCreate")
        super.onCreate()

        // ✅ Install CrashReporter FIRST — before any other initialization
        CrashReporter.install(this)
        StartupDiagnostics.ok("CrashReporter installed")

        // ✅ Start ANR Watchdog
        com.streamlink.shared.diagnostics.ANRWatchDog().start()
        StartupDiagnostics.ok("ANRWatchDog started")

        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()   // violations logged to Logcat only — no red flash
                    .build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StartupDiagnostics.ok("StrictMode configured")
        }
        StartupDiagnostics.ok("App init complete")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w("StreamLinkApp", "onTrimMemory: level=$level")
        com.streamlink.shared.telemetry.MemoryGovernor.handleMemoryPressure(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.e("StreamLinkApp", "onLowMemory: CRITICAL MEMORY PRESSURE")
        com.streamlink.shared.telemetry.MemoryGovernor.handleMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
