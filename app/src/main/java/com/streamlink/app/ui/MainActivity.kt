package com.streamlink.app.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import com.streamlink.app.core.PipModeState
import com.streamlink.shared.util.safeSystemService
// import androidx.appcompat.app.AppCompatActivity (Inherits from BaseActivity instead)
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.provider.Settings
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import com.streamlink.app.ui.dashboard.TelemetryDashboard
// SettingsScreen is now in the same package (com.streamlink.app.ui.SettingsScreen)
import com.streamlink.shared.util.SystemSettingsStore
import com.streamlink.app.ui.viewmodel.TelemetryViewModel
import com.streamlink.app.ui.viewmodel.TelemetryViewModelFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.app.core.WearTelemetrySender
import com.streamlink.shared.GlobalStreamState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseActivity() {

    @Inject lateinit var orchestrator: StreamingOrchestrator
    
    private lateinit var settingsStore: SystemSettingsStore
    private val OVERLAY_PERMISSION_REQ_CODE = 1001
    
    private val telemetryViewModel: TelemetryViewModel by viewModels {
        TelemetryViewModelFactory(
            orchestrator = orchestrator,
            wearSender = WearTelemetrySender(applicationContext)
        )
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            orchestrator.startStream(
                context = this,
                url = "",
                resultCode = result.resultCode,
                projectionData = result.data,
                isDrm = false,
                networkQuality = 1.0f
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.streamlink.app.BuildConfig.DEBUG && (com.streamlink.shared.SecurityUtils.isRooted() || com.streamlink.shared.SecurityUtils.isEmulator())) {
            android.app.AlertDialog.Builder(this)
                .setTitle("بيئة غير آمنة")
                .setMessage("التطبيق ده مصمم للعمل على أجهزة أصلية غير معدّلة لحماية بيانات الشاشة اللي بتتشارك.")
                .setCancelable(false)
                .setPositiveButton("فهمت") { _, _ -> finish() }
                .show()
            return
        }
        
        settingsStore = SystemSettingsStore.get(applicationContext)
        
        // Observe pairing events — auto-launch PIN screen when a Watch connects
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                com.streamlink.shared.PairingManager.state.collect { pairingState ->
                    when (pairingState) {
                        is com.streamlink.shared.PairingManager.PairingState.WaitingForPin -> {
                            startActivity(Intent(this@MainActivity, MobileQrScannerActivity::class.java))
                        }
                        is com.streamlink.shared.PairingManager.PairingState.PinRejected -> {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "❌ رمز الاقتران خاطئ — يرجى المحاولة مجدداً",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            com.streamlink.shared.PairingManager.reset()
                        }
                        is com.streamlink.shared.PairingManager.PairingState.Paired -> {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "✅ تم الاقتران بنجاح",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            com.streamlink.shared.PairingManager.reset()
                        }
                        else -> {}
                    }
                }
            }
        }

        // Keep PictureInPictureParams in sync with GlobalStreamState so setAutoEnterEnabled
        // (API 31+) only ever fires while a stream is actually live — never on the idle/setup screen.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                GlobalStreamState.snapshot.collect {
                    runCatching { setPictureInPictureParams(buildPipParams()) }
                }
            }
        }

        setContent {
            val settingsPrefs = remember { com.streamlink.app.core.SettingsPrefs.get(this@MainActivity) }
            
            val themeModeString by settingsStore.themeMode.collectAsStateWithLifecycle()
            val themeMode = remember(themeModeString) {
                try {
                    com.streamlink.app.ui.theme.ThemeMode.valueOf(themeModeString)
                } catch (e: IllegalArgumentException) {
                    com.streamlink.app.ui.theme.ThemeMode.SYSTEM
                }
            }

            // ✅ NANO-FIX: تطبيق الثيم على المستوى النظامي (System/AppCompat) عشان الحوارات (Dialogs)
            // والقوائم القديمة تتلون بنفس الثيم المختار في Compose.
            LaunchedEffect(themeMode) {
                val nightMode = when (themeMode) {
                    com.streamlink.app.ui.theme.ThemeMode.LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    com.streamlink.app.ui.theme.ThemeMode.DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    com.streamlink.app.ui.theme.ThemeMode.SYSTEM -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
            }

            com.streamlink.app.ui.theme.StreamLinkTheme(themeMode = themeMode) {
                MainScreenLayout(
                    settingsStore = settingsStore,
                    themeMode = themeMode,
                    onThemeModeChange = { newMode ->
                        settingsStore.setThemeMode(newMode.name)
                    },
                    onRequireOverlayPermission = { checkAndRequestOverlayPermission() },
                    viewModel = telemetryViewModel,
                    onStartCapture = { requestScreenCapture() },
                    onStopCapture = { orchestrator.stopStream(this@MainActivity) }
                )
            }
        }
    }

    private fun checkAndRequestOverlayPermission(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this, 
                "يرجى تفعيل صلاحية الظهور في الأعلى لتشغيل وضع تعتيم الشاشة (Privacy Blackout)", 
                Toast.LENGTH_LONG
            ).show()
            
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            return false
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "تم تفعيل صلاحية التعتيم بنجاح! 🛡️", Toast.LENGTH_SHORT).show()
            } else {
                // إغلاق خيار الـ Blackout في الإعدادات تلقائياً لو رفض المستخدم الرخصة
                settingsStore.setPrivacyBlackout(false)
                Toast.makeText(this, "تم رفض الصلاحية، لن يعمل وضع التعتيم التام.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestScreenCapture() {
        val mpm: MediaProjectionManager? = safeSystemService(Context.MEDIA_PROJECTION_SERVICE)
        if (mpm == null) {
            android.widget.Toast.makeText(this, "Screen capture service unavailable on this device.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /**
     * Portrait 9:16 matches the phone's own screen far more often than the video content's
     * true aspect ratio (which varies per-app) — the system clamps to it anyway, and a fixed
     * ratio avoids a jarring PiP-window resize mid-stream if the mirrored app rotates.
     */
    private fun buildPipParams(): PictureInPictureParams {
        val isStreaming = GlobalStreamState.snapshot.value.state == GlobalStreamState.State.STREAMING
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: let the system auto-enter PiP on user-leave instead of racing it below.
            builder.setAutoEnterEnabled(isStreaming)
        }
        return builder.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Manual fallback for API 29-30, where setAutoEnterEnabled doesn't exist yet.
        // Only ever enter PiP for a live stream — never for the idle/setup screen.
        val isStreaming = GlobalStreamState.snapshot.value.state == GlobalStreamState.State.STREAMING
        if (isStreaming && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(buildPipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipModeState.update(isInPictureInPictureMode)
    }

    override fun onDestroy() {
        super.onDestroy()
        orchestrator.stopStream(this)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Premium Phone UI
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenLayout(
    settingsStore: SystemSettingsStore,
    themeMode: com.streamlink.app.ui.theme.ThemeMode,
    onThemeModeChange: (com.streamlink.app.ui.theme.ThemeMode) -> Unit,
    onRequireOverlayPermission: () -> Boolean,
    viewModel: TelemetryViewModel,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Info, contentDescription = "Console") },
                    label = { Text("الكونسول") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("الإعدادات") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // كونسول البث الرئيسي وعداد المقاييس الفوري
                        TelemetryDashboard(viewModel = viewModel)
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        val state by GlobalStreamState.snapshot.collectAsStateWithLifecycle()
                        val isStreaming  = state.state == GlobalStreamState.State.STREAMING
                        val isConnecting = state.state == GlobalStreamState.State.CONNECTING ||
                                state.state == GlobalStreamState.State.STREAM_STARTING

                        if (isStreaming || isConnecting) {
                            Button(
                                onClick = onStopCapture,
                                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Stop Casting", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                PulsingCastButton(onClick = onStartCapture)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                1 -> {
                    // شاشة الإعدادات المتقدمة
                    SettingsScreen(
                        settingsStore = settingsStore,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange
                    )
                    
                    // إذا حاول المستخدم تفعيل الـ Privacy Blackout، نتحقق من الرخصة فوراً
                    val isPrivacyBlackoutEnabled by settingsStore.isPrivacyBlackoutEnabled.collectAsStateWithLifecycle()
                    LaunchedEffect(isPrivacyBlackoutEnabled) {
                        if (isPrivacyBlackoutEnabled) {
                            val granted = onRequireOverlayPermission()
                            if (!granted) {
                                // إعادة التوجيه الفوري لمنع الكراش
                                settingsStore.setPrivacyBlackout(false)
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun StreamLinkPhoneScreen(
    orchestrator: StreamingOrchestrator,
    onStartCapture: () -> Unit,
    onStop: () -> Unit,
    onInfoClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val state by GlobalStreamState.snapshot.collectAsStateWithLifecycle()
    val isStreaming  = state.state == GlobalStreamState.State.STREAMING
    val isConnecting = state.state == GlobalStreamState.State.CONNECTING ||
            state.state == GlobalStreamState.State.STREAM_STARTING

    var aiEnabled by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isStreaming) {
            PhoneRenderSurface(modifier = Modifier.fillMaxSize())
        }

        // Top Right Actions
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            IconButton(onClick = onSettingsClick) {
                Text("⚙️", fontSize = 22.sp)
            }
            IconButton(onClick = onInfoClick) {
                Text("ℹ️", fontSize = 24.sp)
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp)
        ) {
            // Logo / Title
            Text(
                text = "StreamLink",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Screen Mirror to Wear OS",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // Network Quality Bar
            NetworkQualityBar(
                latencyMs = state.latencyMs,
                bitrateKbps = state.bitrateKbps
            )

            // Status Card
            StreamStatusCard(
                isStreaming = isStreaming,
                isConnecting = isConnecting,
                bitrateKbps = state.bitrateKbps,
                fps = state.fps,
                latencyMs = state.latencyMs
            )

            // AI Toggle Row
            AiToggleRow(enabled = aiEnabled, onToggle = { aiEnabled = it })

            // Main Action Button
            if (isStreaming || isConnecting) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Stop Casting", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                PulsingCastButton(onClick = onStartCapture)
            }
        }
    }
}

@Composable
private fun NetworkQualityBar(latencyMs: Long, bitrateKbps: Int) {
    val quality = when {
        latencyMs == 0L          -> "—"
        latencyMs < 50 && bitrateKbps > 1500 -> "Excellent"
        latencyMs < 100          -> "Good"
        latencyMs < 180          -> "Degraded"
        else                     -> "Poor"
    }
    val (barColor, barFraction) = when (quality) {
        "Excellent" -> Pair(com.streamlink.app.ui.theme.SemanticColors.Excellent, 1.00f)
        "Good"      -> Pair(com.streamlink.app.ui.theme.SemanticColors.Good, 0.72f)
        "Degraded"  -> Pair(com.streamlink.app.ui.theme.SemanticColors.Degraded, 0.44f)
        "Poor"      -> Pair(com.streamlink.app.ui.theme.SemanticColors.Poor, 0.18f)
        else        -> Pair(com.streamlink.app.ui.theme.SemanticColors.Neutral, 0.00f)
    }
    val animFraction by animateFloatAsState(
        targetValue = barFraction,
        animationSpec = tween(600),
        label = "quality_bar"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Network Quality", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Text(quality, fontSize = 11.sp, color = barColor, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(animFraction).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor)
                )
            }
        }
    }
}

@Composable
private fun AiToggleRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("AI Bitrate Optimizer", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = if (enabled) "Adaptive bitrate — optimizing in real-time" else "Manual / fixed bitrate",
                    fontSize = 10.sp,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor   = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor   = MaterialTheme.colorScheme.primary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        }
    }
}

@Composable
private fun StreamStatusCard(
    isStreaming: Boolean,
    isConnecting: Boolean,
    bitrateKbps: Int,
    fps: Int,
    latencyMs: Long
) {
    val statusColor = when {
        isStreaming  -> com.streamlink.app.ui.theme.SemanticColors.Streaming
        isConnecting -> com.streamlink.app.ui.theme.SemanticColors.Connecting
        else         -> com.streamlink.app.ui.theme.SemanticColors.Idle
    }
    val statusLabel = when {
        isStreaming  -> "● LIVE"
        isConnecting -> "⏳ Connecting…"
        else         -> "○ Idle"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = statusLabel,
                color = statusColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            if (isStreaming) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MetricPill(label = "Bitrate", value = "${bitrateKbps}kbps")
                    MetricPill(label = "FPS", value = fps.toString())
                    MetricPill(label = "Latency", value = "${latencyMs}ms")
                }
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun PulsingCastButton(onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "cast_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Button(
        onClick = onClick,
        modifier = Modifier
            .scale(scale)
            .fillMaxWidth()
            .height(64.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
    ) {
        Text(
            "▶  Start Casting Screen",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}
