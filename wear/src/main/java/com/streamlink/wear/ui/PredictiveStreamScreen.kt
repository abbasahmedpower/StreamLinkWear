package com.streamlink.wear.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.*
import com.streamlink.shared.StreamAction
import com.streamlink.wear.R

@Composable
fun PredictiveStreamScreen(viewModel: StreamViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WearTheme.Background),
        contentAlignment = Alignment.Center
    ) {
        when {
            uiState.isStreaming && !uiState.isDegraded -> ActiveStreamView(uiState)
            uiState.isDegraded                         -> DegradedStreamView(uiState)
            uiState.isConnecting                       -> ConnectingView()
            uiState.isRecovering                       -> RecoveringView(uiState)
            uiState.errorMessage.isNotEmpty()          -> ErrorView(uiState.errorMessage)
            else                                       -> IdleView(uiState.predictedAction)
        }
    }
}

// ── Active Stream ─────────────────────────────────────────────────────────────
@Composable
private fun ActiveStreamView(state: WearUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(8.dp)
    ) {
        // Pulsing green dot
        PulsingDot(color = WearTheme.StreamGreen)

        Spacer(Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.streaming),
            color = WearTheme.StreamGreen,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )

        Spacer(Modifier.height(4.dp))

        // Bitrate
        if (state.bitrateKbps > 0) {
            Text(
                text = stringResource(R.string.bitrate_kbps, state.bitrateKbps),
                color = WearTheme.TextMuted,
                fontSize = 11.sp
            )
        }

        // Latency
        if (state.latencyMs > 0) {
            Text(
                text = stringResource(R.string.latency_ms, state.latencyMs),
                color = if (state.latencyMs < 80) WearTheme.TextMuted else WearTheme.WarnOrange,
                fontSize = 10.sp
            )
        }

        // Network profile badge
        if (state.sessionNetworkProfile == "RELAY") {
            Spacer(Modifier.height(4.dp))
            Chip(
                onClick = {},
                label = { Text("RELAY", fontSize = 9.sp) },
                colors = ChipDefaults.chipColors(
                    backgroundColor = WearTheme.WarnOrange.copy(alpha = 0.2f)
                ),
                modifier = Modifier.height(20.dp)
            )
        }
    }
}

// ── Degraded Stream ──────────────────────────────────────────────────────────
@Composable
private fun DegradedStreamView(state: WearUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        StaticDot(color = WearTheme.WarnOrange)
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.eco_mode),
            color = WearTheme.WarnOrange,
            fontSize = 12.sp
        )
        if (state.bitrateKbps > 0) {
            Text(
                text = "${state.bitrateKbps}k · ${state.fps}fps",
                color = WearTheme.TextMuted,
                fontSize = 10.sp
            )
        }
    }
}

// ── Connecting ───────────────────────────────────────────────────────────────
@Composable
private fun ConnectingView() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            strokeWidth = 3.dp,
            indicatorColor = WearTheme.ConnectBlue
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.connecting),
            color = WearTheme.TextPrimary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ── Recovering ───────────────────────────────────────────────────────────────
@Composable
private fun RecoveringView(state: WearUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("⟳", color = WearTheme.WarnOrange, fontSize = 28.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.reconnecting),
            color = WearTheme.WarnOrange,
            fontSize = 12.sp
        )
        if (state.reconnectCount > 0) {
            Text(
                text = "#${state.reconnectCount}",
                color = WearTheme.TextMuted,
                fontSize = 10.sp
            )
        }
    }
}

// ── Error ─────────────────────────────────────────────────────────────────────
@Composable
private fun ErrorView(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Text("✕", color = WearTheme.ErrorRed, fontSize = 22.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = message.take(50),
            color = WearTheme.TextPrimary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
    }
}

// ── Idle ──────────────────────────────────────────────────────────────────────
@Composable
private fun IdleView(predicted: StreamAction) {
    val isPreloading = predicted == StreamAction.PRELOAD

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isPreloading) {
            PulsingDot(color = WearTheme.ConnectBlue)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Ready",
                color = WearTheme.ConnectBlue,
                fontSize = 12.sp
            )
        } else {
            StaticDot(color = Color(0xFF333333))
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.idle),
                color = WearTheme.TextMuted,
                fontSize = 12.sp
            )
        }
    }
}

// ── Shared Composables ────────────────────────────────────────────────────────
@Composable
private fun PulsingDot(color: Color, size: Int = 10) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotScale"
    )
    Box(
        modifier = Modifier
            .size(size.dp)
            .scale(scale)
            .background(color, CircleShape)
    )
}

@Composable
private fun StaticDot(color: Color, size: Int = 8) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(color, CircleShape)
    )
}
