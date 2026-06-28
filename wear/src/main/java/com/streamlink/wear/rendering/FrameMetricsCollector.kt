package com.streamlink.wear.rendering

import android.os.SystemClock
import androidx.compose.runtime.Immutable

@Immutable
data class LiveFrameStats(
    val fps: Int = 0,
    val decodeTimeMs: Float = 0f,
    val renderTimeMs: Float = 0f,
    val totalFrameLatencyMs: Float = 0f
)

object FrameMetricsCollector {
    private const val BUFFER_SIZE = 60
    private val decodeTimes = FloatArray(BUFFER_SIZE)
    private val renderTimes = FloatArray(BUFFER_SIZE)
    private var writeIndex = 0

    private var frameCount = 0
    private var lastFpsUpdateTime = SystemClock.elapsedRealtime()
    private var currentFps = 0

    // Stable object for UI updates without Allocations
    @Volatile
    var currentStats = LiveFrameStats()
        private set

    /**
     * Record microsecond timings per frame safely (Lock-Free Thread-Safe Write)
     */
    fun recordFrame(decodeTime: Float, renderTime: Float) {
        val index = writeIndex
        decodeTimes[index] = decodeTime
        renderTimes[index] = renderTime
        writeIndex = (index + 1) % BUFFER_SIZE

        // Calculate real FPS every 1000 ms
        frameCount++
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastFpsUpdateTime
        if (elapsed >= 1000) {
            currentFps = (frameCount * 1000 / elapsed).toInt()
            frameCount = 0
            lastFpsUpdateTime = now
            
            // Clean rolling average of the current Window
            var avgDecode = 0f
            var avgRender = 0f
            for (i in 0 until BUFFER_SIZE) {
                avgDecode += decodeTimes[i]
                avgRender += renderTimes[i]
            }
            avgDecode /= BUFFER_SIZE
            avgRender /= BUFFER_SIZE

            currentStats = LiveFrameStats(
                fps = currentFps,
                decodeTimeMs = avgDecode,
                renderTimeMs = avgRender,
                totalFrameLatencyMs = avgDecode + avgRender
            )
        }
    }
}
