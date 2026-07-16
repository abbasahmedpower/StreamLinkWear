package com.streamlink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamlink.app.ui.viewmodel.TelemetryViewModel
import com.streamlink.app.ui.components.GlassCard
import com.streamlink.app.ui.components.GridTwoColumns
import com.streamlink.app.ui.components.TelemetryItem

// Mocking the Cyberpunk colors if they don't exist in the project yet
val CyberBackground = Color(0xFF0F172A)
val CyberPrimary = Color(0xFF00FFCC)
val CyberOnPrimary = Color(0xFF003322)

@Composable
fun HorusTelemetryScreen(
    viewModel: TelemetryViewModel,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit
) {
    // Collect the live states from ViewModel
    val metrics by viewModel.metricsState.collectAsStateWithLifecycle()
    val currentBitrate by viewModel.currentBitrate.collectAsStateWithLifecycle()
    val isOptimizerEnabled by viewModel.isOptimizerEnabled.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBackground)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(CyberPrimary.copy(alpha = 0.08f), Color.Transparent),
                        center = this.center.copy(y = 0f),
                        radius = size.width * 0.8f
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .statusBarsPadding()
        ) {
            // --- [ 1. Header & Logo ] ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "HORUS LINK",
                        color = CyberPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Telemetry Hub",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold
            )
            // Update node status based on connection quality
            Text(
                text = if (metrics.network.queueCongestion > 0.8f) "SYSTEM NODES: CRITICAL STRESS" else "SYSTEM NODES: 01 ACTIVE (NOMINAL)",
                color = if (metrics.network.queueCongestion > 0.8f) Color.Red else CyberPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- [ 2. Live Telemetry Cards ] ---
            GlassCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "CONNECTED NODE: WEAR OS",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    GridTwoColumns {
                        // Live Battery Card
                        TelemetryItem(
                            title = "CORE ENERGY",
                            value = "${metrics.batteryLevel}%",
                            progress = metrics.batteryLevel / 100f,
                            icon = "battery"
                        )
                        // Live Network Delay Card
                        TelemetryItem(
                            title = "QUEUE CONGESTION",
                            value = "${(metrics.network.queueCongestion * 100).toInt()}%",
                            progress = metrics.network.queueCongestion,
                            icon = "network"
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Live Active Encoder Bitrate 
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ACTIVE ENCODER BITRATE",
                            color = Color(0xFFBAC9CC),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$currentBitrate Kbps",
                            color = CyberPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- [ 3. AI Switch Controller ] ---
            GlassCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "AI BITRATE OPTIMIZER",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isOptimizerEnabled) "Fuzzy Closed-Loop Active" else "Manual override active",
                            color = Color(0xFFBAC9CC),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = isOptimizerEnabled,
                        onCheckedChange = { viewModel.toggleOptimizer(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyberOnPrimary,
                            checkedTrackColor = CyberPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // --- [ 4. Interactive Action Button ] ---
            Button(
                onClick = onStartCapture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(1.5.dp, CyberPrimary, RoundedCornerShape(8.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberPrimary,
                    contentColor = CyberOnPrimary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "START CASTING SCREEN",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onStopCapture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(1.5.dp, Color.Red, RoundedCornerShape(8.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.Red
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "STOP CASTING",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
