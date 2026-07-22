package com.streamlink.app.core.telemetry

import java.util.concurrent.atomic.AtomicInteger

class TelemetryRingBuffer(private val capacity: Int = 1024) {
    
    // Pre-allocate the entire buffer once at startup (Zero GC during stream)
    private val buffer = Array(capacity) { FrameMetrics() }
    
    // Masking is faster than modulo (%) for powers of 2
    private val mask = capacity - 1 
    
    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)

    init {
        require((capacity and mask) == 0) { "Capacity must be a power of 2 for bitwise masking." }
    }

    /**
     * Called by Capture/Encoder threads. Ultra-fast, no locks, no allocations.
     */
    fun acquireNextForWrite(frameSeq: Int): FrameMetrics {
        val currentWrite = writeIndex.getAndIncrement()
        val index = currentWrite and mask
        val metrics = buffer[index]
        metrics.reset(frameSeq)
        return metrics
    }

    /**
     * Called by the Aggregator thread to consume and calculate stats.
     */
    fun consumeNextForRead(): FrameMetrics? {
        val currentRead = readIndex.get()
        if (currentRead == writeIndex.get()) {
            return null // Buffer is empty (Aggregator caught up)
        }
        val index = currentRead and mask
        readIndex.incrementAndGet()
        return buffer[index]
    }
}
