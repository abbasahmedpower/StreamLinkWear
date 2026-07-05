package com.streamlink.shared.telemetry

import android.util.Log

/**
 * MICRO-02: Frame Pipeline Metrics
 * Tracks the nanosecond timestamps of each frame as it moves through the pipeline.
 * Zero-allocation design: Uses primitive arrays and a circular buffer.
 */
object FrameTimeline {

    private const val TAG = "FrameTimeline"
    private const val CAPACITY = 1024
    private const val MASK = CAPACITY - 1

    // Pipeline stages
    const val STAGE_CAPTURE = 0
    const val STAGE_ENCODE = 1
    const val STAGE_NETWORK = 2
    const val STAGE_DECODE = 3
    const val STAGE_RENDER = 4
    
    private const val STAGES_COUNT = 5

    // 2D Array represented as 1D for memory locality: timeline[stage][index] -> stage * CAPACITY + index
    private val timeline = LongArray(STAGES_COUNT * CAPACITY)

    /**
     * Records the timestamp for a specific frame at a specific pipeline stage.
     * 
     * @param frameId The unique sequential ID of the frame (or its presentationTimeUs).
     * @param stage One of the STAGE_* constants.
     */
    fun mark(frameId: Long, stage: Int) {
        if (stage !in 0 until STAGES_COUNT) return
        val index = (frameId and MASK.toLong()).toInt()
        val offset = (stage * CAPACITY) + index
        timeline[offset] = System.nanoTime()
    }

    /**
     * Calculates the total latency (in milliseconds) for a frame from Capture to Render.
     * Returns -1 if the frame data is incomplete or overwritten.
     */
    fun getPipelineLatencyMs(frameId: Long): Float {
        val index = (frameId and MASK.toLong()).toInt()
        val captureTime = timeline[STAGE_CAPTURE * CAPACITY + index]
        val renderTime = timeline[STAGE_RENDER * CAPACITY + index]
        
        if (captureTime <= 0L || renderTime <= 0L || renderTime < captureTime) {
            return -1f // Incomplete or overwritten
        }
        
        return (renderTime - captureTime) / 1_000_000f
    }
    
    /**
     * Extracts latency breakdowns between stages.
     */
    fun dumpBreakdown(frameId: Long) {
        val index = (frameId and MASK.toLong()).toInt()
        val c = timeline[STAGE_CAPTURE * CAPACITY + index]
        val e = timeline[STAGE_ENCODE * CAPACITY + index]
        val n = timeline[STAGE_NETWORK * CAPACITY + index]
        val d = timeline[STAGE_DECODE * CAPACITY + index]
        val r = timeline[STAGE_RENDER * CAPACITY + index]
        
        if (c > 0 && e > 0 && n > 0 && d > 0 && r > 0) {
            val encodeMs = (e - c) / 1_000_000f
            val networkMs = (n - e) / 1_000_000f
            val decodeMs = (d - n) / 1_000_000f
            val renderMs = (r - d) / 1_000_000f
            val totalMs = (r - c) / 1_000_000f
            Log.d(TAG, "Frame $frameId -> Enc:${encodeMs}ms Net:${networkMs}ms Dec:${decodeMs}ms Rnd:${renderMs}ms | Total: ${totalMs}ms")
        }
    }
}
