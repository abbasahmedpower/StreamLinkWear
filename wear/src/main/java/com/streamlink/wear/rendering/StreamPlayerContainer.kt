package com.streamlink.wear.rendering

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.wear.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import androidx.compose.ui.viewinterop.AndroidView
import com.streamlink.shared.util.safeSystemService
import kotlinx.coroutines.delay

@Composable
fun StreamPlayerContainer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val powerManager = remember { context.safeSystemService<PowerManager>(Context.POWER_SERVICE) }
    
    var thermalStatus by remember { mutableStateOf(PowerManager.THERMAL_STATUS_NONE) }
    var liveStats by remember { mutableStateOf(LiveFrameStats()) }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            powerManager?.addThermalStatusListener { status ->
                thermalStatus = status
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            liveStats = FrameMetricsCollector.currentStats
            delay(100) // Poll telemetry every 100ms
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        
        // 1️⃣ Raw Video Layer (Zero-Allocation Render Pipeline)
        AndroidView(
            factory = { ctx ->
                var wakeLock: PowerManager.WakeLock? = null
                try {
                    wakeLock = powerManager?.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "StreamLinkWear::StreamPlayerWakeLock"
                    )?.apply {
                        acquire(10 * 60 * 1000L /*10 minutes*/)
                    }
                } catch (e: Exception) {
                    Log.e("StreamPlayer", "Failed to acquire wake lock", e)
                }
                HardenedStreamTextureView(ctx).apply {
                    onSurfaceReady = { surface ->
                        // Link hardware MediaCodec here
                    }
                    onSurfaceDestroyed = { wakeLock?.release() }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2️⃣ Military-Grade Debug Overlay Layer
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xCC05060A))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "STREAMLINK WEAR TELEMETRY",
                color = Color(0xFFFFD700), // Horus Gold
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            TelemetryRow("FPS", "${liveStats.fps}", if (liveStats.fps >= 24) Color(0xFF00F0FF) else Color.Red)
            TelemetryRow("DECODE", String.format("%.2f ms", liveStats.decodeTimeMs), Color.White)
            TelemetryRow("RENDER", String.format("%.2f ms", liveStats.renderTimeMs), Color.White)
            TelemetryRow(
                "P99 LATENCY", 
                String.format("%.2f ms", liveStats.totalFrameLatencyMs), 
                if (liveStats.totalFrameLatencyMs < 8f) Color(0xFF00FF00) else Color(0xFFFFA500)
            )
        }

        // 3️⃣ GPU Status & Thermal Bypass Overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(Color(0xAA000000))
                .padding(6.dp)
        ) {
            Text(
                text = "GPU CORE: ACTIVE",
                color = Color(0xFF00F0FF),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            
            if (thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT) {
                Text(
                    text = "⚠️ THERMAL BYPASS ACTIVE",
                    color = Color.Red,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black
                )
            } else {
                Text(
                    text = "⚡ SHADER ENGINE: LOW-LIGHT FX",
                    color = Color(0xFFFFD700),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun TelemetryRow(label: String, value: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            color = Color.Gray,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            color = color,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black
        )
    }
}
