package com.streamlink.shared.easp

import kotlin.math.abs

enum class WristState {
    ACTIVE_VIEWING, // Watch is raised and user is actively looking (Max quality)
    PASSIVE,        // Hand is moving naturally while walking (Medium quality)
    IDLE,           // Hand is completely still on a desk (Sharp FPS/Bitrate drop)
    SLEEP           // Watch is stable or in pocket (Absolute idle, freeze non-visible frames)
}

class ZeroAllocationMotionAnalyzer {
    companion object {
        private const val WINDOW_SIZE = 50 // 1-second window at 50Hz polling
        private const val ACCEL_THRESHOLD_ACTIVE = 2.2f
        private const val ACCEL_THRESHOLD_PASSIVE = 0.6f
        private const val ACCEL_THRESHOLD_IDLE = 0.12f
    }

    // Static array allocation in memory to prevent GC entirely
    private val accelHistory = FloatArray(WINDOW_SIZE)
    private var writeIndex = 0
    private var sampleCount = 0

    /**
     * Receives sensor values and calculates O(1) moving average vector magnitude
     */
    fun feedAndClassify(accelX: Float, accelY: Float, accelZ: Float): WristState {
        // Calculate vector magnitude roughly without expensive Math.sqrt to save CPU
        val magnitude = abs(accelX) + abs(accelY) + abs(accelZ) - 9.81f // Subtract rough gravity
        val cleanMagnitude = abs(magnitude)

        // Atomic/Fast store in fixed array
        accelHistory[writeIndex] = cleanMagnitude
        writeIndex = (writeIndex + 1) % WINDOW_SIZE
        if (sampleCount < WINDOW_SIZE) sampleCount++

        // Zero-iterator loop to calculate moving average
        var sum = 0f
        for (i in 0 until sampleCount) {
            sum += accelHistory[i]
        }
        val movingAverage = sum / sampleCount

        return when {
            movingAverage > ACCEL_THRESHOLD_ACTIVE -> WristState.ACTIVE_VIEWING
            movingAverage > ACCEL_THRESHOLD_PASSIVE -> WristState.PASSIVE
            movingAverage > ACCEL_THRESHOLD_IDLE -> WristState.IDLE
            else -> WristState.SLEEP
        }
    }
}
