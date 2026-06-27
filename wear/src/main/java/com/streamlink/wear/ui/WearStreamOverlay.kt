package com.streamlink.wear.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 */
@Composable
fun WearStreamOverlay(
    visible: Boolean,
    onHide: () -> Unit
) {
    val state by GlobalStreamState.snapshot.collectAsState()

    // Auto-hide after 4 seconds
    LaunchedEffect(visible) {
        if (visible) {
            delay(4_000)
            onHide()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter  = fadeIn(tween(300)),
        exit   = fadeOut(tween(500))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Top strip — status
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isStreaming = state.state == GlobalStreamState.State.STREAMING

                // Status dot + label
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
                        text = if (isStreaming) "LIVE" else "Connecting…",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = dotColor
                    )
                }
            }

            // Bottom strip — metrics
            if (state.state == GlobalStreamState.State.STREAMING) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0x991C1C2E), Color(0x991C1C2E))
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp),
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
