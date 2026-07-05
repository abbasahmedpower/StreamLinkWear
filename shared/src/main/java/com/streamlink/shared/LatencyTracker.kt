package com.streamlink.shared

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LatencyTracker — tracks end-to-end latency per frame across the pipeline.
 *
 * Pipeline stages tracked:
 *   ENCODE_START → ENCODE_DONE → CHUNK_QUEUED → CHUNK_SENT → DECODED
 *
 * Usage:
 *   val id = tracker.onEncodeStart()
 *   ... encode ...
 *   tracker.onEncodeDone(id)
 *   tracker.onChunkQueued(id)
 *   tracker.onChunkSent(id)
 *   tracker.onDecoded(id)  // Called by watch side via feedback message
 */
@Singleton
class LatencyTracker @Inject constructor() {
    private val tag = "LatencyTracker"

    data class FrameTimes(
        val frameId: Long,
        val encodeStartMs: Long   = 0,
        val encodeDoneMs: Long    = 0,
        val chunkQueuedMs: Long   = 0,
        val chunkSentMs: Long     = 0,
        val decodedMs: Long       = 0
    ) {
        val encodeLatencyMs  get() = if (encodeDoneMs > 0) encodeDoneMs - encodeStartMs else -1L
        val networkLatencyMs get() = if (chunkSentMs  > 0) chunkSentMs  - chunkQueuedMs  else -1L
        val e2eLatencyMs     get() = if (decodedMs    > 0) decodedMs    - encodeStartMs  else -1L
    }

    private val frames = ConcurrentHashMap<Long, FrameTimes>(256)
    private val frameIdGen = AtomicLong(0)

    // Running stats
    private val encodeLatencies = ArrayDeque<Long>(50)
    private val networkLatencies = ArrayDeque<Long>(50)
    private val e2eLatencies = ArrayDeque<Long>(50)

    private val totalFrames = AtomicLong(0)
    private val lateFrames  = AtomicLong(0)     // e2e > 100ms

    private var lastE2EMs = 0L
    private var jitter = 0f

    private val statLock = Any()

    fun onEncodeStart(): Long {
        val id = frameIdGen.incrementAndGet()
        frames[id] = FrameTimes(frameId = id, encodeStartMs = now())
        return id
    }

    fun onEncodeDone(frameId: Long) = update(frameId) { copy(encodeDoneMs = now()) }
    fun onChunkQueued(frameId: Long) = update(frameId) { copy(chunkQueuedMs = now()) }
    fun onChunkSent(frameId: Long) = update(frameId) { copy(chunkSentMs = now()) }

    fun onDecoded(frameId: Long) {
        update(frameId) { copy(decodedMs = now()) }
        frames[frameId]?.let { finalizeFrame(it) }
        frames.remove(frameId)
    }

    private fun finalizeFrame(f: FrameTimes) {
        totalFrames.incrementAndGet()
        if (f.e2eLatencyMs > 100) lateFrames.incrementAndGet()

        synchronized(statLock) {
            if (f.encodeLatencyMs  > 0) record(encodeLatencies, f.encodeLatencyMs)
            if (f.networkLatencyMs > 0) record(networkLatencies, f.networkLatencyMs)
            if (f.e2eLatencyMs     > 0) {
                record(e2eLatencies, f.e2eLatencyMs)
                if (lastE2EMs > 0) {
                    val diff = Math.abs(f.e2eLatencyMs - lastE2EMs)
                    jitter = jitter + (diff - jitter) / 16f
                }
                lastE2EMs = f.e2eLatencyMs
            }
        }

        if (f.e2eLatencyMs > 150) {
            Log.w(tag, "High E2E latency: ${f.e2eLatencyMs}ms (encode=${f.encodeLatencyMs}ms net=${f.networkLatencyMs}ms)")
        }
    }

    data class LatencyReport(
        val avgEncodeMs: Long,
        val avgNetworkMs: Long,
        val avgE2EMs: Long,
        val p95E2EMs: Long,
        val jitterMs: Long,
        val lateFramePct: Float,
        val totalFrames: Long
    ) {
        override fun toString() =
            "E2E avg=${avgE2EMs}ms p95=${p95E2EMs}ms jitter=${jitterMs}ms | enc=${avgEncodeMs}ms net=${avgNetworkMs}ms | late=${lateFramePct}%"
    }

    fun report(): LatencyReport = synchronized(statLock) {
        LatencyReport(
            avgEncodeMs   = if (encodeLatencies.isEmpty())  0 else encodeLatencies.sum()  / encodeLatencies.size,
            avgNetworkMs  = if (networkLatencies.isEmpty()) 0 else networkLatencies.sum() / networkLatencies.size,
            avgE2EMs      = if (e2eLatencies.isEmpty())     0 else e2eLatencies.sum()     / e2eLatencies.size,
            p95E2EMs      = percentile95(e2eLatencies),
            jitterMs      = jitter.toLong(),
            lateFramePct  = if (totalFrames.get() == 0L) 0f else
                            lateFrames.get().toFloat() / totalFrames.get() * 100,
            totalFrames   = totalFrames.get()
        )
    }

    fun logReport() = Log.i(tag, report().toString())

    // Evict frames older than 5s (lost/dropped)
    fun evictStale(maxAgeMs: Long = 5_000) {
        val cutoff = now() - maxAgeMs
        val stale = frames.entries.filter { it.value.encodeStartMs < cutoff }
        stale.forEach { frames.remove(it.key) }
        if (stale.isNotEmpty()) Log.d(tag, "Evicted ${stale.size} stale frame records")
    }

    private fun update(frameId: Long, block: FrameTimes.() -> FrameTimes) {
        frames.computeIfPresent(frameId) { _, v -> v.block() }
    }

    private fun record(deque: ArrayDeque<Long>, value: Long) {
        if (deque.size >= 50) deque.removeFirst()
        deque.addLast(value)
    }

    private fun percentile95(data: ArrayDeque<Long>): Long {
        if (data.isEmpty()) return 0L
        val sorted = data.sorted()
        val idx = (sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun now() = System.currentTimeMillis()
}
