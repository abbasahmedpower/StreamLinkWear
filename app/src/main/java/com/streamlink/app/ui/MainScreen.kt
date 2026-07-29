package com.streamlink.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamlink.app.ui.dashboard.TelemetryDashboard
import com.streamlink.app.ui.dashboard.DiagnosticsScreen
import com.streamlink.app.ui.theme.SemanticColors
import com.streamlink.app.ui.theme.ThemeMode
import com.streamlink.shared.util.SystemSettingsStore
import com.streamlink.app.ui.viewmodel.TelemetryViewModel
import com.streamlink.app.core.telemetry.BatteryPredictor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenLayout(
    settingsStore: SystemSettingsStore,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    viewModel: TelemetryViewModel,
    mainState: MainState,
    onIntent: (MainIntent) -> Unit
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
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Info, contentDescription = "Diagnostics") },
                    label = { Text("الفحص") }
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
                        TelemetryDashboard(viewModel = viewModel)
                        Spacer(modifier = Modifier.weight(1f))
                        
                        if (mainState.isStreaming || mainState.isConnecting) {
                            Button(
                                onClick = { onIntent(MainIntent.StopStream) },
                                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Stop Casting", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                PulsingCastButton(onClick = { onIntent(MainIntent.StartCaptureRequested) })
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                1 -> {
                    SettingsScreen(
                        settingsStore = settingsStore,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange
                    )
                    
                    LaunchedEffect(mainState.isPrivacyBlackoutEnabled) {
                        if (mainState.isPrivacyBlackoutEnabled) {
                            onIntent(MainIntent.RequestOverlayPermission)
                        }
                    }
                }
                2 -> {
                    val context = LocalContext.current
                    val predictor = remember { BatteryPredictor(context).apply { startTracking() } }
                    DiagnosticsScreen(batteryPredictor = predictor)
                }
            }
        }
    }
}

@Composable
fun StreamLinkPhoneScreen(
    state: MainState,
    onIntent: (MainIntent) -> Unit,
    onInfoClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (state.isStreaming) {
            PhoneRenderSurface(modifier = Modifier.fillMaxSize())
        }

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

            NetworkQualityBar(
                latencyMs = state.latencyMs,
                bitrateKbps = state.bitrateKbps
            )

            StreamStatusCard(
                isStreaming = state.isStreaming,
                isConnecting = state.isConnecting,
                bitrateKbps = state.bitrateKbps,
                fps = state.fps,
                latencyMs = state.latencyMs
            )

            AiToggleRow(
                enabled = state.aiOptimizerEnabled, 
                onToggle = { onIntent(MainIntent.SetAiOptimizer(it)) }
            )

            if (state.isStreaming || state.isConnecting) {
                Button(
                    onClick = { onIntent(MainIntent.StopStream) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Stop Casting", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                PulsingCastButton(onClick = { onIntent(MainIntent.StartCaptureRequested) })
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
        "Excellent" -> Pair(SemanticColors.Excellent, 1.00f)
        "Good"      -> Pair(SemanticColors.Good, 0.72f)
        "Degraded"  -> Pair(SemanticColors.Degraded, 0.44f)
        "Poor"      -> Pair(SemanticColors.Poor, 0.18f)
        else        -> Pair(SemanticColors.Neutral, 0.00f)
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
        isStreaming  -> SemanticColors.Streaming
        isConnecting -> SemanticColors.Connecting
        else         -> SemanticColors.Idle
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
