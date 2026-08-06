package com.streamlink.app.ui.dashboard

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamlink.shared.GlobalStreamState
import com.streamlink.app.core.telemetry.BatteryPredictor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(batteryPredictor: BatteryPredictor) {
    val state by GlobalStreamState.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    // Periodically update battery prediction
    var batteryEstimate by remember { mutableStateOf("Calculating...") }
    LaunchedEffect(Unit) {
        while (true) {
            batteryEstimate = batteryPredictor.getEstimatedRemainingTime()
            kotlinx.coroutines.delay(60_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("StreamLink Diagnostics Hub") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val report = """
                    Diagnostics Report:
                    State: ${state.state}
                    Latency: ${state.latencyMs}ms
                    Bitrate: ${state.bitrateKbps}kbps
                    FPS: ${state.fps}
                    Battery ETA: $batteryEstimate
                """.trimIndent()
                
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, report)
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(sendIntent, context.getString(com.streamlink.app.R.string.export_diagnostics)))
            }) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = androidx.compose.ui.res.stringResource(com.streamlink.app.R.string.export_diagnostics)
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                DiagnosticCard(
                    title = "Network Transport",
                    items = listOf(
                        "State" to state.state.name,
                        "Latency (RTT)" to "${state.latencyMs} ms",
                        "Bitrate" to "${state.bitrateKbps} kbps"
                    )
                )
            }
            item {
                DiagnosticCard(
                    title = "Hardware Metrics",
                    items = listOf(
                        "Battery Prediction" to batteryEstimate,
                        "Decoder FPS" to "${state.fps}",
                        "Epochs" to "1"
                    )
                )
            }
        }
    }
}

@Composable
private fun DiagnosticCard(title: String, items: List<Pair<String, String>>) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
            items.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
