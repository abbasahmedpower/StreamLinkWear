package com.streamlink.wear.input

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * كاشف حركات المعصم فائق الخفة وموفر للطاقة لتسهيل التحكم اللمسافر (Touchless) في ظروف العمل الشاقة.
 */
class ImuGestureDetector(
    private val sensorManager: SensorManager,
    private val onGestureDetected: (GestureType) -> Unit
) : SensorEventListener {

    enum class GestureType {
        SCROLL_DOWN,
        SCROLL_UP,
        BACK
    }

    private var gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    // عتبات الحركة (Thresholds) تم ضبطها لتجنب الحركات الخاطئة أثناء المشي الطبيعي
    private val gyroThreshold = 6.5f // rad/s
    private val accelThreshold = 12.0f // m/s^2
    private var lastGestureTime = 0L
    private val cooldownMs = 800L // فترة انتظار لمنع التكرار السريع الخاطئ

    fun start() {
        // استخدام SENSOR_DELAY_UI لتقليل استهلاك البطارية والحد من إيقاظ المعالج الرئيسي
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (now - lastGestureTime < cooldownMs) return

        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val yRotation = event.values[1] // الدوران حول محور Y (التواء المعصم)

            if (abs(yRotation) > gyroThreshold) {
                lastGestureTime = now
                if (yRotation > 0) {
                    onGestureDetected(GestureType.SCROLL_DOWN)
                } else {
                    onGestureDetected(GestureType.SCROLL_UP)
                }
            }
        } else if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val zAcceleration = event.values[2] // حركة سحب اليد المفاجئة للخلف

            if (abs(zAcceleration) > accelThreshold) {
                lastGestureTime = now
                onGestureDetected(GestureType.BACK)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
