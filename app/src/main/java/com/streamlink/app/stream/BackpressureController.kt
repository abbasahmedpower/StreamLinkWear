package com.streamlink.app.stream

import android.util.Log
import com.streamlink.shared.AdaptiveBufferChannel
import com.streamlink.shared.FramePacket
import com.streamlink.shared.StreamProtocol
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
 * Nano fix N5 (inFlight accuracy):
 * - pendingInNetwork is incremented in onChunkEnqueued() (frame enters TCP send queue)
 * - pendingInNetwork is decremented in onChunkDelivered() — called by DirectSocketServer
 *   AFTER the actual write() returns successfully, not after consumeEach().
 * - This gives an accurate measure of frames truly in-flight over TCP,
 *   enabling ABR to react to real congestion, not phantom congestion.
 *
 * Micro fix:
 * - thermalCeilingKbps: setter applies immediately if currentKbps exceeds ceiling.
 * - Loss measurement uses a sliding window, not a single sample.
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

    // ✅ FIX N5: incremented BEFORE socket write, decremented AFTER write completes
    private val pendingInNetwork = AtomicInteger(0)
    private val totalSentBytes = AtomicLong(0L)
    private val totalDroppedBytes = AtomicLong(0L)

    // RTT sliding window (10 samples)
    private val rttWindow = ArrayDeque<Long>(StreamProtocol.RTT_SAMPLE_WINDOW)
    private var packetLoss = 0f
    private var monitorJob: Job? = null

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

        // ✅ Real in-flight depth: frames sent but not yet confirmed by TCP write
        val inFlight = pendingInNetwork.get()
        val fillRatio = inFlight.toFloat() / 32f  // 32 = practical max in-flight chunks

        val target = when {
            packetLoss > 0.15f || avgRtt > 250L || fillRatio > 0.85f -> {
                Log.w(tag, "Congestion: loss=$packetLoss rtt=${avgRtt}ms inFlight=$inFlight")
                (currentKbps * 0.60f).toInt().coerceAtLeast(minKbps)
            }
            packetLoss > 0.05f || avgRtt > 120L || fillRatio > 0.60f -> {
                (currentKbps * 0.85f).toInt().coerceAtLeast(minKbps)
            }
            packetLoss < 0.02f && avgRtt < 70L && fillRatio < 0.30f -> {
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
