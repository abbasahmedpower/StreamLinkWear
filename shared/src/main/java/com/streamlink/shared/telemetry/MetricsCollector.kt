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
import java.util.concurrent.atomic.AtomicInteger
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

    // ── StreamMetricsSource counters (cumulative — never reset except by reset()) ──
    private val framesDecodedCounter = AtomicLong(0L)
    private val framesDroppedCounter = AtomicLong(0L)
    private val bytesSentCounter = AtomicLong(0L)
    private val reconnectsCounter = AtomicLong(0L)
    private val currentRtt = AtomicLong(0L)

    // ── Windowed-rate counters (absorbed from the old telemetry.TelemetryCollector) ──
    // Zero-allocation, primitive atomics, recomputed at most every 500ms — same
    // philosophy as the collector this replaces, so hot-path cost is unchanged.
    private val windowFrameCount = AtomicInteger(0)
    private val windowByteCount = AtomicLong(0)
    private val windowDecodeSumMs = AtomicLong(0L) // stored as micros-of-Float bits would lose precision; use ms*1000 int accumulation instead
    private val windowRenderSumMs = AtomicLong(0L)
    private val windowTimingSamples = AtomicInteger(0)
    @Volatile private var lastWindowTimeMs = System.currentTimeMillis()

    @Volatile override var fps: Int = 0
        private set
    @Volatile override var bandwidthMbps: Float = 0f
        private set
    @Volatile private var decodeTimeMs: Float = 0f
    @Volatile private var renderTimeMs: Float = 0f

    private val _metricsSnapshotFlow = MutableStateFlow(MetricsSnapshot())
    override val metricsSnapshotFlow: StateFlow<MetricsSnapshot> = _metricsSnapshotFlow.asStateFlow()

    override val dropRate: Float
        get() {
            val total = framesDecodedCounter.get() + framesDroppedCounter.get()
            return if (total == 0L) 0f else framesDroppedCounter.get().toFloat() / total.toFloat()
        }

    override val currentRttMs: Long
        get() = currentRtt.get()

    override fun recordFrame(bytes: Int) {
        framesDecodedCounter.incrementAndGet()
        bytesSentCounter.addAndGet(bytes.toLong())
        windowFrameCount.incrementAndGet()
        windowByteCount.addAndGet(bytes.toLong())
        recomputeWindowedRatesIfDue()
        updateSnapshot()
    }

    override fun recordDrop() {
        framesDroppedCounter.incrementAndGet()
        updateSnapshot()
    }

    override fun recordReconnect() {
        reconnectsCounter.incrementAndGet()
        updateSnapshot()
    }

    override fun recordFrameTiming(decodeMs: Float, renderMs: Float) {
        windowDecodeSumMs.addAndGet((decodeMs * 1000).toLong())
        windowRenderSumMs.addAndGet((renderMs * 1000).toLong())
        windowTimingSamples.incrementAndGet()
        recomputeWindowedRatesIfDue()
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
        reconnectsCounter.set(0L)
        currentRtt.set(0L)
        windowFrameCount.set(0)
        windowByteCount.set(0L)
        windowDecodeSumMs.set(0L)
        windowRenderSumMs.set(0L)
        windowTimingSamples.set(0)
        fps = 0
        bandwidthMbps = 0f
        decodeTimeMs = 0f
        renderTimeMs = 0f
        updateSnapshot()
    }

    fun start() {
        // This collector is the single canonical StreamMetricsSource. Publishing
        // it here lets plain Android Views (e.g. HardenedStreamTextureView, built
        // via AndroidView { } factories with no Hilt access) reach it through
        // StreamMetricsSource.active without a parallel singleton per concern.
        StreamMetricsSource.active = this
    }

    fun stop() {
        if (StreamMetricsSource.active === this) {
            StreamMetricsSource.active = null
        }
        reset()
    }

    /**
     * Recomputes fps / bandwidth / decode / render rates from the current window,
     * then resets window accumulators. Runs at most once every 500ms — called
     * opportunistically from the hot path (recordFrame/recordFrameTiming) instead
     * of a dedicated timer thread, exactly like the collector it replaces.
     */
    private fun recomputeWindowedRatesIfDue() {
        val now = System.currentTimeMillis()
        val elapsedMs = now - lastWindowTimeMs
        if (elapsedMs < 500) return
        val elapsedSeconds = elapsedMs / 1000f

        val frames = windowFrameCount.getAndSet(0)
        val bytes = windowByteCount.getAndSet(0)
        val decodeSum = windowDecodeSumMs.getAndSet(0L)
        val renderSum = windowRenderSumMs.getAndSet(0L)
        val timingSamples = windowTimingSamples.getAndSet(0)

        fps = (frames / elapsedSeconds).toInt()
        bandwidthMbps = (bytes * 8) / (elapsedSeconds * 1_000_000f)
        if (timingSamples > 0) {
            decodeTimeMs = (decodeSum / 1000f) / timingSamples
            renderTimeMs = (renderSum / 1000f) / timingSamples
        }
        lastWindowTimeMs = now
    }

    private fun updateSnapshot() {
        _metricsSnapshotFlow.value = MetricsSnapshot(
            decoded = framesDecodedCounter.get(),
            dropped = framesDroppedCounter.get(),
            rtt = currentRtt.get(),
            reconnects = reconnectsCounter.get(),
            decodeTimeMs = decodeTimeMs,
            renderTimeMs = renderTimeMs
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
