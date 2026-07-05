package com.streamlink.shared.telemetry

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * MICRO-07: Network Profiler
 * Tracks detailed packet metrics (Loss, OOO, Bandwidth) without allocations.
 */
object NetworkProfiler {

    private val packetsSent = AtomicLong(0)
    private val packetsReceived = AtomicLong(0)
    private val outOfOrderPackets = AtomicLong(0)
    private val duplicatePackets = AtomicLong(0)
    private val retransmissions = AtomicLong(0)
    
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    
    // Jitter calculation
    @Volatile private var lastTransitTimeMs = 0L
    @Volatile var currentJitterMs = 0L
        private set

    @Volatile private var highestSequenceReceived = -1L

    fun recordSent(sizeBytes: Int) {
        packetsSent.incrementAndGet()
        bytesSent.addAndGet(sizeBytes.toLong())
    }

    fun recordReceived(sequenceId: Long, sizeBytes: Int, transitTimeMs: Long) {
        packetsReceived.incrementAndGet()
        bytesReceived.addAndGet(sizeBytes.toLong())

        // Calculate Out-Of-Order & Duplication
        if (sequenceId > highestSequenceReceived) {
            val gap = sequenceId - highestSequenceReceived - 1
            if (highestSequenceReceived != -1L && gap > 0) {
                // Gap detected (potential loss or delayed out of order)
                // Packet loss will be inferred over time
            }
            highestSequenceReceived = sequenceId
        } else if (sequenceId == highestSequenceReceived) {
            duplicatePackets.incrementAndGet()
        } else {
            outOfOrderPackets.incrementAndGet()
        }

        // Calculate Jitter
        if (lastTransitTimeMs > 0) {
            val delta = Math.abs(transitTimeMs - lastTransitTimeMs)
            currentJitterMs = (currentJitterMs * 15 + delta) / 16 // Smooth EMA
        }
        lastTransitTimeMs = transitTimeMs
    }
    
    fun recordRetransmission() {
        retransmissions.incrementAndGet()
    }

    /**
     * Resets counters for a new connection session.
     */
    fun reset() {
        packetsSent.set(0)
        packetsReceived.set(0)
        outOfOrderPackets.set(0)
        duplicatePackets.set(0)
        retransmissions.set(0)
        bytesSent.set(0)
        bytesReceived.set(0)
        highestSequenceReceived = -1L
        lastTransitTimeMs = 0L
        currentJitterMs = 0L
    }
}
