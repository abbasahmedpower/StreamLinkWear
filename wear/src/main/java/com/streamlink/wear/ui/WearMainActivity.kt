package com.streamlink.wear.ui

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceView
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import com.streamlink.wear.player.DirectStreamPlayer
import com.streamlink.wear.service.WearForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.streamlink.shared.GlobalStreamState
import androidx.lifecycle.repeatOnLifecycle

@AndroidEntryPoint
class WearMainActivity : ComponentActivity() {

    @Inject lateinit var streamPlayer: DirectStreamPlayer
    @Inject lateinit var uxEngine: com.streamlink.wear.ai.SmartWatchUXEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isAmbient = false
    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    private var lastRotationX = 0f
    private var lastAccelZ = 0f

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                lastAccelZ = event.values[2]
            } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                lastRotationX = event.values[0]
            }
            uxEngine.processWristMetrics(lastRotationX, lastAccelZ)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Ambient mode observer — activates when watch screen dims.
     * We DON'T stop streaming in ambient; we reduce it to ECO quality.
     */
    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                isAmbient = true
                Log.i("WearMain", "Entering ambient mode — keeping stream alive at ECO quality")
                // Keep ForegroundService alive, reduce visual quality
                // QualityController already handles this via thermalLevel/isUserMoving signals
            }

            override fun onExitAmbient() {
                isAmbient = false
                Log.i("WearMain", "Exiting ambient mode — restoring full quality")
                // Renderer will auto-recover; request IDR for clean frame
                // The stream is already running (never stopped)
            }

            override fun onUpdateAmbient() {
                // Called every 60s in ambient — update any ambient UI if needed
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen ON during active streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Register ambient lifecycle
        lifecycle.addObserver(ambientObserver)

        // Start background service immediately
        WearForegroundService.start(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        
        lifecycleScope.launch {
            this@WearMainActivity.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                var previousState = GlobalStreamState.current
                GlobalStreamState.snapshot.collect { snapshot ->
                    val newState = snapshot.state
                    if (newState != previousState) {
                        when (newState) {
                            GlobalStreamState.State.STREAMING -> {
                                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                            }
                            GlobalStreamState.State.FAILED -> {
                                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1))
                            }
                            GlobalStreamState.State.DEGRADED -> {
                                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
                            }
                            else -> {}
                        }
                        previousState = newState
                    }
                }
            }
        }

        setContent {
            var surfaceReady by remember { mutableStateOf(false) }
            var overlayVisible by remember { mutableStateOf(true) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { overlayVisible = !overlayVisible }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        SurfaceView(context).apply {
                            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                    streamPlayer.setSurface(holder.surface)
                                    streamPlayer.start(scope)
                                    surfaceReady = true
                                    Log.i("WearMain", "Surface ready — streaming started")
                                }
                                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {
                                    Log.d("WearMain", "Surface changed ${w}x${h}")
                                }
                                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                    streamPlayer.setSurface(null)
                                    surfaceReady = false
                                }
                            })
                        }
                    }
                )

                // HUD Overlay — tap to show/hide
                WearStreamOverlay(
                    visible = overlayVisible,
                    onHide  = { overlayVisible = false }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager?.registerListener(sensorListener, accelSensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager?.registerListener(sensorListener, gyroSensor, SensorManager.SENSOR_DELAY_UI)
        // Wrist raise restores from ambient — no action needed,
        // AmbientLifecycleObserver.onExitAmbient() handles it
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(sensorListener)
    }

    override fun onStop() {
        super.onStop()
        // Activity goes background — ForegroundService keeps streaming
        // DO NOT release streamPlayer here
        Log.i("WearMain", "Activity stopped — streaming continues in ForegroundService")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only release when explicitly stopped by user
        if (isFinishing) {
            WearForegroundService.stop(this)
            streamPlayer.release()
        }
    }
}
