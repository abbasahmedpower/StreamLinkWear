package com.streamlink.shared.transport

/**
 * Calculates the clock offset between the sender (Phone) and receiver (Watch)
 * using a multi-ping NTP-style algorithm. It selects the ping with the minimum RTT
 * for the most accurate offset estimation.
 */
class TimeSynchronizer {

    @Volatile
    var timeOffsetNanos: Long = 0L
        private set

    @Volatile
    var isSynchronized: Boolean = false
        private set

    private var minRttNanos: Long = Long.MAX_VALUE

    /**
     * Called during handshake for each Ping/Pong exchange.
     * Updates the offset if this exchange had a lower RTT than previous ones.
     *
     * @param t1 Sender Nano Time (Request Sent)
     * @param t2 Receiver Nano Time (Request Received)
     * @param t3 Receiver Nano Time (Response Sent)
     * @param t4 Sender Nano Time (Response Received)
     */
    @Synchronized
    fun updateOffset(t1: Long, t2: Long, t3: Long, t4: Long) {
        val roundTripTime = (t4 - t1) - (t3 - t2)
        
        if (roundTripTime < minRttNanos || !isSynchronized) {
            minRttNanos = roundTripTime
            // Offset = ((t2 - t1) + (t3 - t4)) / 2
            timeOffsetNanos = ((t2 - t1) + (t3 - t4)) / 2
            isSynchronized = true
        }
    }

    /**
     * Converts a remote monotonic timestamp (Phone) to local monotonic time (Watch).
     */
    fun toLocalNanoTime(remoteTimestampNanos: Long): Long {
        return remoteTimestampNanos - timeOffsetNanos
    }
}
