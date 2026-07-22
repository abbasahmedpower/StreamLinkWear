package com.streamlink.app.core.telemetry

import android.os.SystemClock
import android.util.Log

/**
 * Tracks Cold, Warm, and Hot start metrics.
 * Ensures we meet the strict < 700ms Startup Time performance budget.
 */
object StartupProfiler {
    private const val TAG = "StartupProfiler"

    private var appInitTimeMs = 0L
    private var firstFrameTimeMs = 0L
    
    private const val STARTUP_BUDGET_MS = 700L

    fun onAppProcessStarted() {
        appInitTimeMs = SystemClock.elapsedRealtime()
    }

    fun onFirstFrameRendered() {
        if (firstFrameTimeMs == 0L) {
            firstFrameTimeMs = SystemClock.elapsedRealtime()
            val startupTime = firstFrameTimeMs - appInitTimeMs
            
            if (startupTime > STARTUP_BUDGET_MS) {
                Log.w(TAG, "⚠️ WARNING: Startup budget exceeded! Actual: ${startupTime}ms | Budget: ${STARTUP_BUDGET_MS}ms")
            } else {
                Log.i(TAG, "✅ Startup Profile: ${startupTime}ms (Within ${STARTUP_BUDGET_MS}ms budget)")
            }
        }
    }
}
