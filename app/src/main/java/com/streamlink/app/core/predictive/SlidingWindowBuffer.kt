package com.streamlink.app.core.predictive

data class NetworkSample(
    val timestamp: Long,
    val rtt: Int,
    val jitter: Int,
    val packetLoss: Float,
    val bitrate: Int
)

class SlidingWindowBuffer(private val capacity: Int = 10) {
    // Pre-allocate array of dummy samples to avoid GC
    private val history = Array(capacity) {
        NetworkSample(0L, 0, 0, 0f, 0)
    }
    
    private var head = 0
    private var size = 0

    fun push(sample: NetworkSample) {
        // We replace the object in the array to minimize allocations.
        // Wait, if we use a data class, passing a new instance allocates.
        // In a true zero-GC path we would use a mutable class like FrameMetrics,
        // but for now we store the sample.
        history[head] = sample
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    // Returns data in chronological order for analysis
    fun getOrderedValues(out: Array<NetworkSample>): Int {
        if (size == 0) return 0
        val currentSize = size
        for (i in 0 until currentSize) {
            val index = (head - currentSize + i + capacity) % capacity
            out[i] = history[index]
        }
        return currentSize
    }
}
