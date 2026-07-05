package com.streamlink.wear.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.streamlink.shared.util.safeSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/** Detects wrist movement magnitude using the accelerometer. */
@Singleton
class WristMotionSensor @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    private val sensorManager: SensorManager? = context.safeSystemService(Context.SENSOR_SERVICE)

    @Volatile var currentMagnitude: Float = 0f
        private set

    fun start() {
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        accel?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyro?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        currentMagnitude = sqrt(x * x + y * y + z * z)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
