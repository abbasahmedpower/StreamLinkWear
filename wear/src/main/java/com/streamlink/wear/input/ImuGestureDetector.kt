package com.streamlink.wear.input

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Ultra-lightweight and energy-efficient IMU Wrist Gesture Detector.
 * Implements Low-Pass Signal Filtering (alpha = 0.15f) and Debounce Logic (350ms)
 * to eliminate false positives caused by walking, arm movement, or sensor jitter.
 */
class ImuGestureDetector(
    private val sensorManager: SensorManager,
    var sensitivity: Sensitivity = Sensitivity.MEDIUM,
    private val onGestureDetected: (GestureType) -> Unit
) : SensorEventListener {

    enum class GestureType {
        SCROLL_DOWN,
        SCROLL_UP,
        BACK
    }

    enum class Sensitivity(val gyroThreshold: Float, val accelThreshold: Float) {
        LOW(6.0f, 10.0f),
        MEDIUM(4.0f, 7.5f),
        HIGH(2.5f, 5.0f)
    }

    private var gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private var lastGestureTime = 0L
    private val cooldownMs = 350L // 350ms debounce window

    // Low-Pass Filter Smoothing Alpha (0.15 = 85% previous value + 15% new signal)
    private val lowPassAlpha = 0.15f
    private var smoothedGyroY = 0f
    private var smoothedAccelZ = 0f

    fun start() {
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        smoothedGyroY = 0f
        smoothedAccelZ = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (now - lastGestureTime < cooldownMs) return

        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val rawY = event.values[1]
            // Low-pass filter for Gyro Y-axis (wrist twist)
            smoothedGyroY += lowPassAlpha * (rawY - smoothedGyroY)

            if (abs(smoothedGyroY) > sensitivity.gyroThreshold) {
                lastGestureTime = now
                if (smoothedGyroY > 0) {
                    onGestureDetected(GestureType.SCROLL_DOWN)
                } else {
                    onGestureDetected(GestureType.SCROLL_UP)
                }
                smoothedGyroY = 0f // Reset filter state post-trigger
            }
        } else if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val rawZ = event.values[2]
            // Low-pass filter for Accel Z-axis (hand jerk back)
            smoothedAccelZ += lowPassAlpha * (rawZ - smoothedAccelZ)

            if (abs(smoothedAccelZ) > sensitivity.accelThreshold) {
                lastGestureTime = now
                onGestureDetected(GestureType.BACK)
                smoothedAccelZ = 0f // Reset filter state post-trigger
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
