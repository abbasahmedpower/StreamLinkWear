package com.streamlink.app.core.telemetry

import android.util.Log

/**
 * Enterprise GPU Budget Enforcer
 * Estimates GPU rendering time and dropped frames due to GPU bottlenecks.
 */
object GPUBudgetMonitor {
    private const val TAG = "GPUBudget"

    private const val GPU_FRAME_BUDGET_MS = 16.6f // targeting 60fps
    
    private var droppedGpuFrames = 0L

    fun reportFrameRendered(renderTimeMs: Float) {
        if (renderTimeMs > GPU_FRAME_BUDGET_MS) {
            droppedGpuFrames++
            if (droppedGpuFrames % 30 == 0L) {
                Log.w(TAG, "⚠️ WARNING: GPU Frame budget exceeded! Average render > ${GPU_FRAME_BUDGET_MS}ms. Dropped so far: $droppedGpuFrames")
            }
        }
    }

    fun getDroppedGpuFrames(): Long = droppedGpuFrames
}
