package com.streamlink.app.core.telemetry

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class TelemetryAggregator(
    private val ringBuffer: TelemetryRingBuffer,
    private val intervalMs: Long = 500L
) {
    // Use SharedFlow for events (replay=0)
    private val _aggregatedStats = MutableSharedFlow<AggregatedStats>(replay = 0)
    val aggregatedStats: SharedFlow<AggregatedStats> = _aggregatedStats.asSharedFlow()

    private var aggregatorJob: Job? = null

    fun start(scope: CoroutineScope) {
        aggregatorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                processBatch()
                delay(intervalMs)
            }
        }
    }

    private fun processBatch() {
        var framesProcessed = 0
        var totalEncodeTime = 0L
        var totalPayload = 0
        var droppedCount = 0

        var metrics = ringBuffer.consumeNextForRead()
        while (metrics != null) {
            framesProcessed++
            if (metrics.dropped) {
                droppedCount++
            } else {
                val encodeDuration = metrics.encoderOutTimestamp - metrics.encoderInTimestamp
                totalEncodeTime += encodeDuration
                totalPayload += metrics.payloadSize
            }
            metrics = ringBuffer.consumeNextForRead() // Next frame
        }

        if (framesProcessed > 0) {
            // Push to Decision Engine
            _aggregatedStats.tryEmit(AggregatedStats(
                avgEncodeTimeNs = totalEncodeTime / framesProcessed,
                totalBytesPerInterval = totalPayload,
                drops = droppedCount,
                frames = framesProcessed
            ))
        }
    }

    fun stop() {
        aggregatorJob?.cancel()
    }
}

data class AggregatedStats(
    val avgEncodeTimeNs: Long = 0,
    val totalBytesPerInterval: Int = 0,
    val drops: Int = 0,
    val frames: Int = 0
)
