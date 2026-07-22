package com.streamlink.app.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamlink.shared.telemetry.TelemetryCollector
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamlink.shared.GlobalStreamState

@Composable
fun TelemetryDashboard(viewModel: com.streamlink.app.ui.viewmodel.TelemetryViewModel) {
    val state by com.streamlink.shared.GlobalStreamState.snapshot.collectAsStateWithLifecycle()
    val metrics by viewModel.metricsState.collectAsStateWithLifecycle()
    val bitrate by viewModel.currentBitrateRaw.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "🛡️ مؤشرات الأداء الفولاذي (Real-time Telemetry)",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryMetricItem("FPS", "${state.fps}", Color.Green)
                TelemetryMetricItem("Drops", "${metrics.network.droppedFramesDelta}", 
                    if (metrics.network.droppedFramesDelta > 2) Color.Red else Color.Gray)
                
                // تلوين مؤشر الـ Latency ديناميكياً بناءً على جودة الشبكة
                val latencyColor by animateColorAsState(
                    targetValue = when {
                        state.latencyMs == 0L -> Color.Gray
                        state.latencyMs < 50 -> Color.Green // أداء خارق
                        state.latencyMs < 120 -> Color.Yellow // أداء مقبول
                        else -> Color.Red // شبكة تعيسة تحتاج تدخل
                    }
                )
                TelemetryMetricItem("Latency", "${state.latencyMs}ms", latencyColor)
                
                val bitrateMbps = bitrate / 1000f
                TelemetryMetricItem("Bitrate", String.format("%.2f Mbps", bitrateMbps), Color.Cyan)
            }
        }
    }
}

@Composable
fun RowScope.TelemetryMetricItem(label: String, value: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f)
    ) {
        Text(text = label, color = Color.Gray, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

data class TelemetryData(
    val fps: Int,
    val droppedFps: Int,
    val avgLatencyMs: Float,
    val bandwidthMbps: Float
)
