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

@Composable
fun TelemetryDashboard() {
    // سحب البيانات دورياً كل 500 مللي ثانية لمنع Recomposition الجائر
    val telemetryState by produceState(
        initialValue = TelemetryData(0, 0, 0f, 0f)
    ) {
        while (true) {
            value = TelemetryData(
                fps = TelemetryCollector.currentFps,
                droppedFps = TelemetryCollector.currentDroppedFps,
                avgLatencyMs = TelemetryCollector.averageLatencyMs,
                bandwidthMbps = TelemetryCollector.bandwidthMbps
            )
            delay(500) // التهدئة لضمان ثبات البطارية
        }
    }

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
                TelemetryMetricItem("FPS", "${telemetryState.fps}", Color.Green)
                TelemetryMetricItem("Dropped", "${telemetryState.droppedFps}", 
                    if (telemetryState.droppedFps > 2) Color.Red else Color.Gray)
                
                // تلوين مؤشر الـ Latency ديناميكياً بناءً على جودة الشبكة
                val latencyColor by animateColorAsState(
                    targetValue = when {
                        telemetryState.avgLatencyMs < 15 -> Color.Green // أداء خارق
                        telemetryState.avgLatencyMs < 40 -> Color.Yellow // أداء مقبول
                        else -> Color.Red // شبكة تعيسة تحتاج تدخل
                    }
                )
                TelemetryMetricItem("Latency", "${telemetryState.avgLatencyMs.toInt()}ms", latencyColor)
                TelemetryMetricItem("Bitrate", String.format("%.2f Mbps", telemetryState.bandwidthMbps), Color.Cyan)
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
