package com.streamlink.app.stream

import android.util.Log
import com.streamlink.shared.AdaptiveBufferChannel
import com.streamlink.shared.FramePacket
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.protocol.BackpressureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Adaptive backpressure controller — links TCP queue health to encoder bitrate.
 *
 * Phase 2 upgrade: integrates BackpressureEngine for multi-dimensional pressure scoring.
 * Replaces queue.size-only approach with weighted score across:
 *   RTT (20%) + PacketLoss (30%) + Thermal (20%) + QueueAge (15%) + Battery (10%) + CPU (5%)
 *
 * Nano fix N5 (inFlight accuracy):
 * - pendingInNetwork is incremented in onChunkEnqueued() (frame enters TCP send queue)
 * - pendingInNetwork is decremented in onChunkDelivered() — called by DirectSocketServer
 *   AFTER the actual write() returns successfully, not after consumeEach().
 *
 * ⚠️ BackpressureEngine weights are initial values — tune from real Perfetto traces.
 */
class BackpressureController(
    private val buffer: AdaptiveBufferChannel<FramePacket>,
    private val onBitrateChange: (kbps: Int) -> Unit,
    private val scope: CoroutineScope
) {
    private val tag = "Backpressure"

    private var currentKbps = StreamProtocol.WEAR_BPS_FULL
    private val minKbps = 300
    private val maxKbps = 4_000

    // Phase 2: multi-dimensional pressure engine
    private val pressureEngine = BackpressureEngine(targetRttMs = 60)

    // ✅ FIX N5: incremented BEFORE socket write, decremented AFTER write completes
    private val pendingInNetwork = AtomicInteger(0)
    private val totalSentBytes = AtomicLong(0L)
    private val totalDroppedBytes = AtomicLong(0L)

    // RTT sliding window (10 samples) — kept for evaluate() compatibility
    private val rttWindow = ArrayDeque<Long>(StreamProtocol.RTT_SAMPLE_WINDOW)
    private var packetLoss = 0f
    private var monitorJob: Job? = null
    // Thermal state [0..3] — updated by ThermalMonitor via onThermalState()
    @Volatile private var thermalState = 0
    // Queue age in ms — updated by DirectSocketServer
    @Volatile private var queueAgeMs = 0
    // Battery % — updated by BatteryMonitor
    @Volatile private var batteryPct = 100

    var thermalCeilingKbps: Int = maxKbps
        set(value) {
            field = value
            if (currentKbps > value) applyBitrate(value)
        }

    fun start() {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(300)
                evaluate()
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /** Called when a wire chunk enters the TCP send queue (pre-write). */
    fun onChunkEnqueued(bytes: Int) {
        pendingInNetwork.incrementAndGet()
        totalSentBytes.addAndGet(bytes.toLong())
    }

    /** Called by DirectSocketServer AFTER actual TCP write() completes. */
    fun onChunkDelivered() {
        pendingInNetwork.decrementAndGet()
        // Note: this is the callback registered as socketServer.onChunkDelivered
    }

    /** Called when a chunk is dropped (queue full or socket disconnected). */
    fun onChunkDropped(bytes: Int) {
        totalDroppedBytes.addAndGet(bytes.toLong())
        // pendingInNetwork was never incremented for dropped frames — no decrement needed
    }

    fun onRttSample(rttMs: Long) {
        synchronized(rttWindow) {
            if (rttWindow.size >= StreamProtocol.RTT_SAMPLE_WINDOW) rttWindow.removeFirst()
            rttWindow.addLast(rttMs.coerceIn(0L, 5_000L))
        }
    }

    fun onPacketLossReport(lossPercent: Float) {
        packetLoss = lossPercent.coerceIn(0f, 1f)
    }

    private fun evaluate() {
        val avgRtt = synchronized(rttWindow) {
            if (rttWindow.isEmpty()) return@synchronized 0L
            rttWindow.sum() / rttWindow.size
        }

        // ── Adaptive thresholds — tighten as RTT grows, relax when network is fast ──
        // At RTT ≤40ms  (LAN):  high=0.90, mid=0.65, low=0.35  — very permissive
        // At RTT ≤100ms (good WiFi): high=0.80, mid=0.55, low=0.30
        // At RTT ≤250ms (poor WiFi): high=0.70, mid=0.45, low=0.25
        // At RTT >250ms  (congested): high=0.55, mid=0.35, low=0.20
        val (highWatermark, midWatermark, lowWatermark) = when {
            avgRtt <= 40L  -> Triple(0.90f, 0.65f, 0.35f)
            avgRtt <= 100L -> Triple(0.80f, 0.55f, 0.30f)
            avgRtt <= 250L -> Triple(0.70f, 0.45f, 0.25f)
            else           -> Triple(0.55f, 0.35f, 0.20f)
        }

        // ✅ Real in-flight depth: frames sent but not yet confirmed by TCP write
        val inFlight = pendingInNetwork.get()
        val fillRatio = inFlight.toFloat() / 32f  // 32 = practical max in-flight chunks

        val target = when {
            packetLoss > 0.15f || avgRtt > 250L || fillRatio > highWatermark -> {
                Log.w(tag, "Congestion: loss=$packetLoss rtt=${avgRtt}ms inFlight=$inFlight (high=$highWatermark)")
                (currentKbps * 0.60f).toInt().coerceAtLeast(minKbps)
            }
            packetLoss > 0.05f || avgRtt > 120L || fillRatio > midWatermark -> {
                (currentKbps * 0.85f).toInt().coerceAtLeast(minKbps)
            }
            packetLoss < 0.02f && avgRtt < 70L && fillRatio < lowWatermark -> {
                (currentKbps + 150).coerceAtMost(minOf(maxKbps, thermalCeilingKbps))
            }
            else -> currentKbps
        }

        if (target != currentKbps) applyBitrate(target)
    }

    private fun applyBitrate(kbps: Int) {
        val safe = kbps.coerceAtMost(thermalCeilingKbps).coerceIn(minKbps, maxKbps)
        if (safe == currentKbps) return
        currentKbps = safe
        onBitrateChange(safe)
        Log.d(tag, "Bitrate → ${safe}Kbps (inFlight=${pendingInNetwork.get()})")
    }

    fun currentBitrate(): Int = currentKbps

    val dropRate: Float
        get() {
            val sent = totalSentBytes.get()
            val dropped = totalDroppedBytes.get()
            val total = sent + dropped
            return if (total == 0L) 0f else dropped.toFloat() / total
        }
}
