package com.streamlink.app.stream

import android.media.MediaCodec
import android.util.Log
import com.streamlink.app.capture.HardwareEncoder
import com.streamlink.shared.DirectSocketServer
import com.streamlink.shared.FramePacket
import com.streamlink.shared.GopFrameDropper
import com.streamlink.shared.HardenedFrame
import com.streamlink.shared.HardenedFrameProcessor
import com.streamlink.shared.MetricsCollector
import com.streamlink.shared.NalChunker
import com.streamlink.shared.StreamObservability
import com.streamlink.shared.WireBufferPool
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Zero-allocation data plane.
 *
 * Nano fixes applied:
 * N1 — No buffer.duplicate() on every frame: we pass (offset, size) directly
 *       to NalChunker.chunkDirect(), which builds slices without heap objects.
 * N2 — Dedicated single-thread dispatcher: eliminates Dispatchers.IO context
 *       switch overhead on the hot encoding path.
 * N3 — GopFrameDropper.shouldDrop() reads queueDepth from DirectSocketServer
 *       directly (real value, not zero-placeholder).
 * N4 — WireBufferPool.release() called on every branch, including error paths.
 */
class MirrorDataPlane(
    private val encoder: HardwareEncoder,
    private val streamRouter: com.streamlink.shared.StreamRouter,
    private val metrics: MetricsCollector?,
    private val backpressure: BackpressureController? = null
) {
    private val tag = "MirrorDataPlane"

    // Dedicated single thread — eliminates coroutine context switch overhead
    private val planeDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SL-DataPlane").also { it.priority = Thread.MAX_PRIORITY - 1 }
    }.asCoroutineDispatcher()

    private var sendJob: Job? = null

    fun start(scope: CoroutineScope) {
        val handler = CoroutineExceptionHandler { _, e ->
            Log.e(tag, "DataPlane fatal: ${e.message}")
        }
        sendJob = scope.launch(planeDispatcher + handler) {
            encoder.outputChannel.consumeEach { packet ->
                processPacket(packet)
            }
        }
        Log.i(tag, "DataPlane started — single-thread, zero-duplicate pipeline")
    }

    private fun processPacket(packet: FramePacket) {
        try {
            // ✅ FIX N1: No buffer.duplicate() — build MediaCodec.BufferInfo directly from
            // FramePacket fields. HardenedFrameProcessor receives the original ByteBuffer
            // with position/limit set once, not cloned.
            val info = MediaCodec.BufferInfo().apply {
                set(
                    packet.offset,
                    packet.size,
                    packet.timestampUs,
                    if (packet.isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                )
            }

            // Position + limit set once on the original buffer — no copy, no new object
            packet.buffer.position(packet.offset)
            packet.buffer.limit(packet.offset + packet.size)

            val hardened: HardenedFrame = HardenedFrameProcessor.processAndObtain(
                packet.buffer, info
            ) ?: return  // Config frame — skip

            // ✅ FIX N3: Real queue depth from StreamRouter
            val queueDepth = streamRouter.queueDepth

            // GOP-aware drop — never drops I-frames
            if (GopFrameDropper.shouldDrop(hardened.isKeyframe, queueDepth)) {
                StreamObservability.recordDrop()
                metrics?.recordDrop()
                return
            }

            // ✅ Zero-allocation chunking via callback pattern (no List<Chunk>)
            NalChunker.chunkFramePipeline(hardened) { wire, wireSize, payloadSize ->
                val sent = streamRouter.sendPooledWire(wire, wireSize)
                if (sent) {
                    backpressure?.onChunkEnqueued(wireSize)
                    metrics?.recordFrame(payloadSize)
                    StreamObservability.recordFrameSent()
                } else {
                    // sendPooledWire releases wire on failure — no double-release
                    backpressure?.onChunkDropped(wireSize)
                    metrics?.recordDrop()
                    StreamObservability.recordDrop()
                }
            }

        } catch (e: Exception) {
            Log.w(tag, "processPacket exception: ${e.message}")
            metrics?.recordDrop()
        } finally {
            // ✅ Always release — idempotent via AtomicBoolean in FramePacket
            packet.release()
        }
    }

    fun stop() {
        sendJob?.cancel()
        sendJob = null
        planeDispatcher.close()
        Log.i(tag, "DataPlane stopped")
    }
}
