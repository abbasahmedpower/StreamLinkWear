package com.streamlink.app.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
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
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.shared.GlobalStreamState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var orchestrator: StreamingOrchestrator

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
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary   = Color(0xFF7C4DFF),
                    secondary = Color(0xFF03DAC6),
                    background = Color(0xFF0A0A12),
                    surface   = Color(0xFF13131F)
                )
            ) {
                var showInfoScreen by remember { mutableStateOf(false) }

                if (showInfoScreen) {
                    InfoScreen(onBack = { showInfoScreen = false })
                } else {
                    StreamLinkPhoneScreen(
                        orchestrator = orchestrator,
                        onStartCapture = { requestScreenCapture() },
                        onStop = { orchestrator.stopStream(this@MainActivity) },
                        onInfoClick = { showInfoScreen = true }
                    )
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        super.onDestroy()
        orchestrator.stopStream(this)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Premium Phone UI
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StreamLinkPhoneScreen(
    orchestrator: StreamingOrchestrator,
    onStartCapture: () -> Unit,
    onStop: () -> Unit,
    onInfoClick: () -> Unit
) {
    val state by GlobalStreamState.snapshot.collectAsStateWithLifecycle()
    val isStreaming = state.state == GlobalStreamState.State.STREAMING
    val isConnecting = state.state == GlobalStreamState.State.CONNECTING ||
            state.state == GlobalStreamState.State.STREAM_STARTING

    val bgBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A0A12), Color(0xFF13131F))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
    ) {
        // Info Button (Top Right)
        IconButton(
            onClick = onInfoClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text("ℹ️", fontSize = 24.sp)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp)
        ) {
            // Logo / Title
            Text(
                text = "StreamLink",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF7C4DFF)
            )
            Text(
                text = "Screen Mirror to Wear OS",
                fontSize = 14.sp,
                color = Color(0xFF888899)
            )

            Spacer(Modifier.height(16.dp))

            // Status Indicator
            StreamStatusCard(
                isStreaming = isStreaming,
                isConnecting = isConnecting,
                bitrateKbps = state.bitrateKbps,
                fps = state.fps,
                latencyMs = state.latencyMs
            )

            Spacer(Modifier.height(8.dp))

            // Main Action Button
            if (isStreaming || isConnecting) {
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679)),
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
private fun StreamStatusCard(
    isStreaming: Boolean,
    isConnecting: Boolean,
    bitrateKbps: Int,
    fps: Int,
    latencyMs: Long
) {
    val statusColor = when {
        isStreaming  -> Color(0xFF4CAF50)
        isConnecting -> Color(0xFFFFB300)
        else         -> Color(0xFF555566)
    }
    val statusLabel = when {
        isStreaming  -> "● LIVE"
        isConnecting -> "⏳ Connecting…"
        else         -> "○ Idle"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF1C1C2E),
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
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFF888899), fontSize = 11.sp)
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
            containerColor = Color(0xFF7C4DFF)
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
    ) {
        Text(
            "▶  Start Casting Screen",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}
