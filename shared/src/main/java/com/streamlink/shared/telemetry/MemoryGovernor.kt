package com.streamlink.shared.telemetry

import android.content.ComponentCallbacks2
import android.util.Log

/**
 * Handles system memory pressure callbacks (onTrimMemory, onLowMemory).
 * Signals the StreamingOrchestrator or other components to drop buffers.
 */
object MemoryGovernor {

    private const val TAG = "MemoryGovernor"

    /**
     * Registered listener that can respond to memory pressure (e.g. StreamingOrchestrator).
     */
    var onEmergencyFlushRequested: (() -> Unit)? = null

    fun handleMemoryPressure(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.e(TAG, "CRITICAL memory pressure! Flushing all non-essential buffers.")
                onEmergencyFlushRequested?.invoke()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.w(TAG, "Moderate memory pressure. Trimming background caches.")
                // We could drop video quality here or flush older P-frames
                onEmergencyFlushRequested?.invoke()
            }
            else -> {
                Log.d(TAG, "Minor memory pressure (level: $level). Ignored.")
            }
        }
    }
}
