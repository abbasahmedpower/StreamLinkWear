package com.streamlink.wear.ui

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceView
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.streamlink.wear.player.DirectStreamPlayer
import com.streamlink.wear.security.WatchPinEngine
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
import android.os.PowerManager
import com.streamlink.shared.util.safeSystemService
import com.streamlink.shared.GlobalStreamState
import androidx.lifecycle.repeatOnLifecycle

@AndroidEntryPoint
class WearMainActivity : ComponentActivity() {

    @Inject lateinit var streamPlayer: DirectStreamPlayer
    @Inject lateinit var uxEngine: com.streamlink.wear.ai.SmartWatchUXEngine
    @Inject lateinit var socketClient: com.streamlink.shared.DirectSocketClient
    
    // webRtcSender = null لغاية ما يتعمل نظير WebRTC حقيقي على جانب الساعة (Phase 2).
    // لحد كده الـorchestrator بيشتغل على TCP المحلي بس (نفس السلوك القديم بالظبط)
    // لكن دلوقتي عبر طبقة abstraction جاهزة تستقبل WebRTC sender بسطر واحد لما يتبنى.
    private val fallbackOrchestrator by lazy {
        com.streamlink.shared.network.NetworkFallbackOrchestrator(
            localSender = socketClient,
            webRtcSender = null
        )
    }

    private val touchController by lazy {
        com.streamlink.wear.input.TouchInputController(fallbackOrchestrator)
    }

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
        try {
            if (com.streamlink.shared.SecurityUtils.isRooted() || com.streamlink.shared.SecurityUtils.isEmulator()) {
            android.widget.Toast.makeText(this, "Security Warning: Rooted device or emulator detected.", android.widget.Toast.LENGTH_LONG).show()
        }

        // Keep screen ON will be managed dynamically based on stream state to save battery
        
        // Register ambient lifecycle
        lifecycle.addObserver(ambientObserver)

        // Start background service immediately
        WearForegroundService.start(this)

        sensorManager = safeSystemService(Context.SENSOR_SERVICE)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        val vibrator: Vibrator? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            safeSystemService<android.os.VibratorManager>(Context.VIBRATOR_MANAGER_SERVICE)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            safeSystemService(Context.VIBRATOR_SERVICE)
        }
        
        lifecycleScope.launch {
            this@WearMainActivity.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                var previousState = GlobalStreamState.current
                GlobalStreamState.snapshot.collect { snapshot ->
                    val newState = snapshot.state
                    if (newState != previousState) {
                        when (newState) {
                            GlobalStreamState.State.STREAMING -> {
                                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                            }
                            GlobalStreamState.State.FAILED -> {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1))
                            }
                            GlobalStreamState.State.STOPPED -> {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                            GlobalStreamState.State.DEGRADED -> {
                                vibrator?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
                            }
                            else -> {}
                        }
                        previousState = newState
                    }
                }
            }
        }

        // 1. Generate Secure PIN and set it on the client
        val generatedPin = com.streamlink.wear.security.WatchPinEngine.generateSecurePin()
        socketClient.pairingCode = generatedPin
        
        setContent {
            val streamState by GlobalStreamState.snapshot.collectAsState()
            var isPinScreen by remember { mutableStateOf(true) }
            
            // Auto-hide PIN screen when successfully connected and streaming
            if (streamState.state == GlobalStreamState.State.STREAMING) {
                isPinScreen = false
            }

            if (isPinScreen) {
                WearPinScreen(pinCode = generatedPin)
            } else {
                var surfaceReady by remember { mutableStateOf(false) }
                var overlayVisible by remember { mutableStateOf(true) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            object : SurfaceView(context) {}.apply {
                                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                        streamPlayer.setSurface(holder.surface)
                                        streamPlayer.start(lifecycleScope)
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

                    // Illusionist Surface for 0-latency perceived touch
                    if (!overlayVisible) {
                        WearInteractiveScreen(
                            onTouchEvent = { phase, nx, ny ->
                                val timeUs = System.nanoTime() / 1000L
                                touchController.processEvent(
                                    pointerId = 0L,
                                    phase = phase,
                                    x = nx,
                                    y = ny,
                                    timestampUs = timeUs
                                )
                            }
                        )
                    }

                    // HUD Overlay — tap to show/hide
                    WearStreamOverlay(
                        visible = overlayVisible,
                        onHide  = { overlayVisible = false },
                        onBack      = { socketClient.sendControl(com.streamlink.shared.StreamProtocol.CMD_GLOBAL_ACTION, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) },
                        onHome      = { socketClient.sendControl(com.streamlink.shared.StreamProtocol.CMD_GLOBAL_ACTION, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) },
                        onRecents   = { socketClient.sendControl(com.streamlink.shared.StreamProtocol.CMD_GLOBAL_ACTION, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS) },
                        onAudioOutput = { com.streamlink.wear.ux.AudioOutputPicker.open(this@WearMainActivity) },
                        onRetry = {
                            WearForegroundService.stop(this@WearMainActivity)
                            lifecycleScope.launch {
                                delay(300)
                                WearForegroundService.start(this@WearMainActivity)
                                streamPlayer.start(lifecycleScope)
                            }
                        }
                    )
                }
            }
        }
        } catch (e: Exception) {
            android.util.Log.e("WearMain", "Non-fatal init error — showing degraded UI instead of crashing", e)
            setContent { 
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    androidx.wear.compose.material.Text("Init Failed. Please restart.", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager?.registerListener(sensorListener, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager?.registerListener(sensorListener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
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

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val action = when (keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            android.view.KeyEvent.KEYCODE_STEM_1,
            android.view.KeyEvent.KEYCODE_STEM_PRIMARY -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            else -> return super.onKeyDown(keyCode, event)
        }
        socketClient.sendControl(com.streamlink.shared.StreamProtocol.CMD_GLOBAL_ACTION, action)
        return true
    }
}

@Composable
fun WearPinScreen(pinCode: String) {
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "رمز الاقتران الآمن",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            // PIN displayed in two groups of 3 for easy reading on a small watch face
            Text(
                text = pinCode.chunked(3).joinToString("  "),
                color = Color(0xFF10B981),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "أدخله في تطبيق الهاتف",
                color = Color.White,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}
