package com.streamlink.shared.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Telemetry collector that gathers raw metrics and smooths them using EMA.
 */
class MetricsCollector(
    context: Context,
    private val externalScope: CoroutineScope
) {
    // ✅ Fix: Use applicationContext to prevent memory leaks if collector outlives activity
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var rawQueueCongestion = 0.0f
    private var rawAverageDelayMs = 0.0f
    
    // ✅ Fix: Track previous dropped frames to calculate delta safely
    private var lastTotalDroppedFrames = 0L
    private var droppedFramesDelta = 0L

    private val metricsMutex = Mutex()
    private val _networkMetricsFlow = MutableStateFlow(NetworkMetrics())

    val metricsFlow: StateFlow<SystemMetricsState> by lazy {
        combine(
            observeThermalStatus(),
            observeBatteryStatus(),
            _networkMetricsFlow
        ) { thermal, battery, network ->
            SystemMetricsState(
                network = network,
                thermalStatus = thermal,
                batteryLevel = battery.first,
                isCharging = battery.second
            )
        }.stateIn(
            scope = externalScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SystemMetricsState()
        )
    }

    /**
     * Called periodically by the orchestrator (e.g. every 1000ms) with TCP queue stats.
     */
    suspend fun updateTcpStats(queueDepth: Int, totalDroppedFrames: Long, averageDelayMs: Float) {
        metricsMutex.withLock {
            // Calculate delta of dropped frames since last poll
            val delta = totalDroppedFrames - lastTotalDroppedFrames
            droppedFramesDelta = if (delta >= 0) delta else 0
            lastTotalDroppedFrames = totalDroppedFrames

            // Normalize queue depth into a 0.0-1.0 congestion metric
            // Assuming max queue size is around 320 (64 I-Frame + 256 P-Frame)
            val currentCongestion = (queueDepth / 320.0f).coerceIn(0.0f, 1.0f)

            // ✅ Fix: Apply EMA (Exponential Moving Average) correctly
            rawQueueCongestion = exponentialMovingAverage(currentCongestion, rawQueueCongestion, ALPHA_NETWORK)
            rawAverageDelayMs = exponentialMovingAverage(averageDelayMs, rawAverageDelayMs, ALPHA_NETWORK)

            _networkMetricsFlow.value = NetworkMetrics(
                queueCongestion = rawQueueCongestion,
                averageDelayMs = rawAverageDelayMs,
                droppedFramesDelta = droppedFramesDelta // Raw delta used directly, not EMA smoothed!
            )
        }
    }

    private fun observeThermalStatus(): Flow<Int> = callbackFlow {
        // Build.VERSION.SDK_INT >= Q is always true since minSdk is 29. Redundant check removed.
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            trySend(status)
        }
        trySend(powerManager.currentThermalStatus)
        powerManager.addThermalStatusListener(listener)
        awaitClose { powerManager.removeThermalStatusListener(listener) }
    }

    private fun observeBatteryStatus(): Flow<Pair<Int, Boolean>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                
                val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 100
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                                 status == BatteryManager.BATTERY_STATUS_FULL
                
                trySend(Pair(batteryPct, isCharging))
            }
        }
        
        appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        awaitClose { appContext.unregisterReceiver(receiver) }
    }

    private fun exponentialMovingAverage(newValue: Float, oldValue: Float, alpha: Float): Float {
        return (alpha * newValue) + ((1.0f - alpha) * oldValue)
    }

    companion object {
        private const val ALPHA_NETWORK = 0.3f
    }
}
