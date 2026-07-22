package com.streamlink.shared.transport

import kotlin.math.abs

/**
 * Ensures monotonic clock synchronization between devices to avoid system time adjustments
 * breaking packet deadlines or latency calculations.
 */
class ClockSyncEngine {
    
    @Volatile
    private var offsetNanos: Long = 0L

    /**
     * Call this when receiving a PONG containing the remote time and the calculated RTT.
     */
    fun updateOffset(localSendNanos: Long, localReceiveNanos: Long, remoteReceiveNanos: Long) {
        val rttNanos = localReceiveNanos - localSendNanos
        // Assume symmetric latency for the offset calculation
        val networkLatencyNanos = rttNanos / 2
        
        // Remote time when we sent the ping
        val remoteTimeAtSend = remoteReceiveNanos - networkLatencyNanos
        
        val newOffset = remoteTimeAtSend - localSendNanos
        
        // Smoothing function for offset
        if (offsetNanos == 0L) {
            offsetNanos = newOffset
        } else {
            // Apply EWMA (Exponential Weighted Moving Average)
            offsetNanos = (offsetNanos * 0.9 + newOffset * 0.1).toLong()
        }
    }

    /**
     * Returns the current local Monotonic time.
     */
    fun currentMonotonicNanos(): Long {
        return System.nanoTime()
    }

    /**
     * Converts a remote monotonic timestamp to a local monotonic timestamp.
     */
    fun remoteToLocalNanos(remoteNanos: Long): Long {
        return remoteNanos - offsetNanos
    }

    /**
     * Converts a local monotonic timestamp to a remote monotonic timestamp.
     */
    fun localToRemoteNanos(localNanos: Long): Long {
        return localNanos + offsetNanos
    }
}
