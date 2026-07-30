package com.streamlink.shared.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Telemetry collector that gathers raw metrics and smooths them using EMA.
 * Implements [StreamMetricsSource] as the Single Source of Truth for telemetry.
 */
class MetricsCollector(
    context: Context,
    private val externalScope: CoroutineScope
) : StreamMetricsSource {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var rawQueueCongestion = 0.0f
    private var rawAverageDelayMs = 0.0f
    
    private var lastTotalDroppedFrames = 0L
    private var droppedFramesDelta = 0L

    private val metricsMutex = Mutex()
    private val _networkMetricsFlow = MutableStateFlow(NetworkMetrics())

    // StreamMetricsSource counters
    private val framesDecodedCounter = AtomicLong(0L)
    private val framesDroppedCounter = AtomicLong(0L)
    private val bytesSentCounter = AtomicLong(0L)
    private val currentRtt = AtomicLong(0L)

    private val _metricsSnapshotFlow = MutableStateFlow(MetricsSnapshot())
    override val metricsSnapshotFlow: StateFlow<MetricsSnapshot> = _metricsSnapshotFlow.asStateFlow()

    override val fps: Int
        get() = (framesDecodedCounter.get() % 60).toInt()

    override val dropRate: Float
        get() {
            val total = framesDecodedCounter.get() + framesDroppedCounter.get()
            return if (total == 0L) 0f else framesDroppedCounter.get().toFloat() / total.toFloat()
        }

    override val bandwidthMbps: Float
        get() = (bytesSentCounter.get() * 8f) / 1_000_000f

    override val currentRttMs: Long
        get() = currentRtt.get()

    override fun recordFrame(bytes: Int) {
        framesDecodedCounter.incrementAndGet()
        bytesSentCounter.addAndGet(bytes.toLong())
        updateSnapshot()
    }

    override fun recordDrop() {
        framesDroppedCounter.incrementAndGet()
        updateSnapshot()
    }

    override fun updateRtt(rttMs: Long) {
        currentRtt.set(rttMs)
        updateSnapshot()
    }

    override fun reset() {
        framesDecodedCounter.set(0L)
        framesDroppedCounter.set(0L)
        bytesSentCounter.set(0L)
        currentRtt.set(0L)
        updateSnapshot()
    }

    fun start() {
        // Telemetry collection lifecycle hook
    }

    fun stop() {
        reset()
    }

    private fun updateSnapshot() {
        _metricsSnapshotFlow.value = MetricsSnapshot(
            decoded = framesDecodedCounter.get(),
            dropped = framesDroppedCounter.get(),
            rtt = currentRtt.get()
        )
    }

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
            val delta = totalDroppedFrames - lastTotalDroppedFrames
            droppedFramesDelta = if (delta >= 0) delta else 0
            lastTotalDroppedFrames = totalDroppedFrames

            val currentCongestion = (queueDepth / 320.0f).coerceIn(0.0f, 1.0f)

            rawQueueCongestion = exponentialMovingAverage(currentCongestion, rawQueueCongestion, ALPHA_NETWORK)
            rawAverageDelayMs = exponentialMovingAverage(averageDelayMs, rawAverageDelayMs, ALPHA_NETWORK)

            _networkMetricsFlow.value = NetworkMetrics(
                queueCongestion = rawQueueCongestion,
                averageDelayMs = rawAverageDelayMs,
                droppedFramesDelta = droppedFramesDelta
            )
        }
    }

    private fun observeThermalStatus(): Flow<Int> = callbackFlow {
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
