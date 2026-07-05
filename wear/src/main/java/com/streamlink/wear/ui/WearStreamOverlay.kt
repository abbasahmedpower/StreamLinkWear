package com.streamlink.wear.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.streamlink.shared.GlobalStreamState
import kotlinx.coroutines.delay

/**
 * WearStreamOverlay — transparent HUD overlay drawn on top of SurfaceView.
 * Shows FPS, Latency, Bitrate, and AI status.
 * Auto-hides after 4 seconds of inactivity; re-appears on tap.
 *
 * Implements:
 * - Premium Shimmer Skeleton Loading
 * - Empty / Connect States
 * - Error States with retry
 * - Accessibility Audits
 */
@Composable
fun WearStreamOverlay(
    visible: Boolean,
    onHide: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onAudioOutput: () -> Unit,
    onRetry: () -> Unit = {}
) {
    val state by GlobalStreamState.snapshot.collectAsState()

    // Auto-hide after 4 seconds when streaming actively
    LaunchedEffect(visible, state.state) {
        if (visible && state.state == GlobalStreamState.State.STREAMING) {
            delay(4_000)
            onHide()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ─── Case 1: Connecting / Starting (Skeleton Loading) ───
        if (state.state == GlobalStreamState.State.CONNECTING ||
            state.state == GlobalStreamState.State.STREAM_STARTING
        ) {
            ShimmerSkeleton()
        }

        // ─── Case 2: Failed State (Error Screen) ───
        if (state.state == GlobalStreamState.State.FAILED) {
            ErrorScreen(onRetry = onRetry)
        }

        // ─── Case 3: Stopped State (Empty State) ───
        if (state.state == GlobalStreamState.State.STOPPED) {
            EmptyScreen(onRetry = onRetry)
        }

        // ─── HUD Overlay (Status & Controls) ───
        AnimatedVisibility(
            visible = visible && (state.state == GlobalStreamState.State.STREAMING || state.state == GlobalStreamState.State.DEGRADED),
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(500))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top status pill
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Connection Status" },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val isStreaming = state.state == GlobalStreamState.State.STREAMING
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        val dotColor = if (isStreaming) Color(0xFF4CAF50) else Color(0xFFFFB300)
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(dotColor)
                        )
                        Text(
                            text = if (isStreaming) "LIVE" else "DEGRADED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = dotColor
                        )
                    }
                }

                // Bottom strip — metrics
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x991C1C2E))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Performance Telemetry Metrics" },
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OverlayMetric(label = "FPS", value = state.fps.toString(), good = state.fps >= 28)
                    OverlayDivider()
                    OverlayMetric(
                        label = "MS",
                        value = state.latencyMs.toString(),
                        good = state.latencyMs < 100
                    )
                    OverlayDivider()
                    OverlayMetric(
                        label = "Kbps",
                        value = state.bitrateKbps.toString(),
                        good = state.bitrateKbps > 800
                    )
                }

                // Remote Control Buttons
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "◀",
                        fontSize = 14.sp,
                        color = Color.White,
                        modifier = Modifier
                            .clickable { onBack() }
                            .semantics { contentDescription = "Global Back Navigation" }
                    )
                    Text(
                        text = "●",
                        fontSize = 14.sp,
                        color = Color.White,
                        modifier = Modifier
                            .clickable { onHome() }
                            .semantics { contentDescription = "Global Home Navigation" }
                    )
                    Text(
                        text = "▢",
                        fontSize = 14.sp,
                        color = Color.White,
                        modifier = Modifier
                            .clickable { onRecents() }
                            .semantics { contentDescription = "Global Recents Apps Navigation" }
                    )
                    Text(
                        text = "🔊",
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { onAudioOutput() }
                            .semantics { contentDescription = "Select Bluetooth Audio Output Device" }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShimmerSkeleton() {
    val transition = rememberInfiniteTransition()
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF12121A),
            Color(0xFF222230),
            Color(0xFF12121A)
        ),
        start = androidx.compose.ui.geometry.Offset.Zero,
        end = androidx.compose.ui.geometry.Offset(x = translateAnim, y = translateAnim)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.08f))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            )
        }
    }
}

@Composable
private fun ErrorScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F18))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("⚠️", fontSize = 28.sp)
            Text(
                text = "Stream Connection Failed",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF5252)
            )
            Text(
                text = "Ensure signaling server is running.",
                fontSize = 9.sp,
                color = Color(0xFF9E9EB2)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF6200EE))
                    .clickable { onRetry() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Retry connecting to phone stream" }
            ) {
                Text("Retry", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F18))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("📱", fontSize = 32.sp)
            Text(
                text = "Ready to Connect",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Start screen sharing from StreamLink on Phone",
                fontSize = 9.sp,
                color = Color(0xFF8E8E9F)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF03DAC6))
                    .clickable { onRetry() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Manually start discovery scan" }
            ) {
                Text("Discover", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OverlayMetric(label: String, value: String, good: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (good) Color(0xFF4CAF50) else Color(0xFFFF6B6B)
        )
        Text(
            text = label,
            fontSize = 8.sp,
            color = Color(0xFF888899)
        )
    }
}

@Composable
private fun OverlayDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(20.dp)
            .background(Color(0x44FFFFFF))
    )
}
