package com.streamlink.wear.ui

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.asImageBitmap
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
    
    // Telemetry overlay ViewModel — receives real-time data from the phone
    private val telemetryViewModel: WearTelemetryViewModel by viewModels()
    
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

    private val manualIpLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data == null) {
            Log.i("WearMain", "Manual IP entry cancelled (no data)")
            return@registerForActivityResult
        }
        val typed = androidx.core.app.RemoteInput.getResultsFromIntent(data)
            ?.getCharSequence(EXTRA_IP_INPUT)?.toString()?.trim()
        when {
            typed == null -> Log.i("WearMain", "Manual IP entry cancelled")
            isValidIpv4(typed) -> {
                streamPlayer.connectManually(typed)
                Log.i("WearMain", "Manual IP accepted: $typed")
            }
            else -> {
                android.widget.Toast.makeText(this, "IP غير صالح — جرب تاني", android.widget.Toast.LENGTH_SHORT).show()
                Log.w("WearMain", "Rejected invalid manual IP input: $typed")
            }
        }
    }

    private fun launchManualIpEntry() {
        val remoteInputs = arrayOf(
            android.app.RemoteInput.Builder(EXTRA_IP_INPUT)
                .setLabel("IP بتاع الموبايل")
                .build()
        )
        val intent = android.content.Intent("android.support.wearable.input.action.REMOTE_INPUT")
        intent.putExtra("android.support.wearable.input.extra.REMOTE_INPUTS", remoteInputs)
        manualIpLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (com.streamlink.shared.SecurityUtils.isRooted() || com.streamlink.shared.SecurityUtils.isEmulator()) {
            android.widget.Toast.makeText(this, "Security Warning: Rooted device or emulator detected.", android.widget.Toast.LENGTH_LONG).show()
        }

        // Keep screen ON will be managed dynamically based on stream state to save battery
        
        // Register ambient lifecycle
        lifecycle.addObserver(ambientObserver)

        streamPlayer.acquire()

        // Start background service immediately
        WearForegroundService.start(this)
        
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
            val discoveryTimedOut by streamPlayer.discoveryTimedOut.collectAsState()
            var isPinScreen by remember { mutableStateOf(true) }

            // Auto-hide PIN screen when successfully connected and streaming
            if (streamState.state == GlobalStreamState.State.STREAMING) {
                isPinScreen = false
            }

            var surfaceReady by remember { mutableStateOf(false) }
            var overlayVisible by remember { mutableStateOf(true) }

            Box(modifier = Modifier.fillMaxSize()) {
                // Base layer — always composed so surfaceCreated() fires immediately
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

                if (discoveryTimedOut && streamState.state != GlobalStreamState.State.STREAMING) {
                    DiscoveryFallbackBanner(
                        onTap = { launchManualIpEntry() },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                if (!isPinScreen) {
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

                // --- Telemetry HUD Overlay (long-press to toggle) ---
                var showTelemetry by remember { mutableStateOf(false) }
                if (!isPinScreen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onLongPress = { showTelemetry = !showTelemetry })
                            }
                    )
                    if (showTelemetry) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xDD0A1128))
                        ) {
                            WearTelemetryScreen(viewModel = telemetryViewModel)
                        }
                    }
                }

                if (isPinScreen) {
                    val ip = remember { com.streamlink.shared.NetworkUtils.getLocalIpAddress() ?: "127.0.0.1" }

                    // Auto-refresh QR every 60 seconds to avoid stale sessions
                    var currentPin by remember { mutableStateOf(generatedPin) }
                    var refreshTick by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) {
                        while (isPinScreen) {
                            delay(60_000L)
                            val newPin = com.streamlink.wear.security.WatchPinEngine.generateSecurePin()
                            socketClient.pairingCode = newPin
                            currentPin = newPin
                            refreshTick++
                            Log.i("WearMain", "QR refreshed — new PIN generated (tick=$refreshTick)")
                        }
                    }

                    val payload = remember(currentPin) {
                        com.streamlink.shared.ConnectionPayload(
                            ip = ip,
                            port = com.streamlink.shared.StreamProtocol.DIRECT_SOCKET_PORT,
                            pairingCode = currentPin
                        ).toJson()
                    }
                    WearPairingScreen(qrPayload = payload, pinCode = currentPin)
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
        // Wrist raise restores from ambient — no action needed,
        // AmbientLifecycleObserver.onExitAmbient() handles it
    }

    override fun onPause() {
        super.onPause()
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
        }
        streamPlayer.release()
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
fun WearPairingScreen(qrPayload: String, pinCode: String) {
    MaterialTheme {
        val qrBitmap = remember(qrPayload) {
            com.streamlink.wear.ui.QrGenerator.generateQrCode(qrPayload, 200)?.asImageBitmap()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "امسح الكود بهاتفك",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (qrBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = qrBitmap,
                    contentDescription = "QR Code",
                    modifier = Modifier.height(110.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Fallback PIN displayed in two groups of 3
            Text(
                text = pinCode.chunked(3).joinToString("  "),
                color = Color(0xFF10B981),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

private const val EXTRA_IP_INPUT = "extra_manual_ip"

private fun isValidIpv4(input: String): Boolean {
    val parts = input.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        val n = part.toIntOrNull()
        n != null && n in 0..255 && part == n.toString()
    }
}

@Composable
fun DiscoveryFallbackBanner(onTap: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(bottom = 10.dp)
            .background(
                Color(0xCC1E293B),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
            )
            .clickable { onTap() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = "مش لاقي الموبايل تلقائيًا\nاضغط لإدخال IP يدويًا",
            color = Color(0xFFF59E0B),
            fontSize = 9.sp,
            textAlign = TextAlign.Center
        )
    }
}
