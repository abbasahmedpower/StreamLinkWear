package com.streamlink.wear.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Text

// Cyberpunk neon palette (matches the phone UI)
private val NeonCyan   = Color(0xFF00FFCC)
private val NeonRed    = Color(0xFFFF1744)
private val DarkBg     = Color(0xFF0A1128)
private val SubText    = Color(0xFF6B8EA6)

/**
 * WearTelemetryScreen
 *
 * A circular Wear OS screen that shows live streaming telemetry data
 * broadcast from the phone's Fuzzy Decision Engine over BLE/Wi-Fi via
 * the Wearable MessageClient.
 *
 * Displays:
 *  - Active Encoder Bitrate (most critical control variable)
 *  - Network Queue Congestion % (with warning color)
 *  - Phone Battery Level
 */
@Composable
fun WearTelemetryScreen(viewModel: WearTelemetryViewModel) {
    val battery     by viewModel.battery.collectAsStateWithLifecycle()
    val congestion  by viewModel.congestionPct.collectAsStateWithLifecycle()
    val bitrate     by viewModel.bitrate.collectAsStateWithLifecycle()
    val isStressed  by viewModel.isStressed.collectAsStateWithLifecycle()

    // Color transitions for stress state — animates smoothly
    val statusColor by animateColorAsState(
        targetValue = if (isStressed) NeonRed else NeonCyan,
        animationSpec = tween(durationMillis = 600),
        label = "status_color"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        statusColor.copy(alpha = 0.07f),
                        DarkBg
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            // --- Node Header ---
            Text(
                text = "HORUS NODE",
                color = statusColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.clickable {
                    viewModel.toggleSimulationMode()
                }
            )

            // --- Active Bitrate (hero metric) ---
            Text(
                text = bitrate,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "ENCODER BITRATE",
                color = SubText,
                fontSize = 8.sp,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            // --- Secondary Row: Battery + Network ---
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Battery
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = battery,
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "ENERGY", color = SubText, fontSize = 7.sp, letterSpacing = 1.sp)
                }

                // Divider dot
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(SubText.copy(alpha = 0.5f))
                )

                // Network congestion
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isStressed) "STRESS!" else "$congestion%",
                        color = if (isStressed) NeonRed else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "QUEUE", color = SubText, fontSize = 7.sp, letterSpacing = 1.sp)
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // --- System Status Indicator ---
            Text(
                text = if (isStressed) "⚠ THROTTLING ACTIVE" else "● NOMINAL",
                color = statusColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}
