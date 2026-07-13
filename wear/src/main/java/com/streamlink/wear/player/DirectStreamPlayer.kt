package com.streamlink.wear.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import com.streamlink.shared.DirectSocketClient
import com.streamlink.shared.FrameAssembler
import com.streamlink.shared.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * DirectStreamPlayer — H.264 async decoder.
 *
 * Fixed:
 * - Uses MediaCodec.Callback ASYNC mode (zero blocking on hot path)
 * - FrameAssembler reassembles multi-chunk NALs before feeding decoder
 * - Reuses single MediaCodec.BufferInfo instance
 * - Dedicated decoder thread at URGENT_DISPLAY priority
 * - IDR-only start: waits for keyframe before decoding to avoid green frames
 */
class DirectStreamPlayer @Inject constructor(
    private val client: DirectSocketClient,
    private val audioEngine: AudioPlaybackEngine
) {
    private val tag = "DirectStreamPlayer"

    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    private var connectJob: Job? = null
    private val released = AtomicBoolean(false)
    private val refCount = java.util.concurrent.atomic.AtomicInteger(0)

    // ✅ Dedicated high-priority decoder thread
    private val decoderThread = HandlerThread(
        "SL-Decoder",
        Process.THREAD_PRIORITY_URGENT_DISPLAY
    ).also { it.start() }
    private val decoderHandler = Handler(decoderThread.looper)

    // ✅ NAL assembler (handles multi-chunk NALs)
    private val assembler = FrameAssembler()

    // ✅ Wait for first IDR before starting decode (avoids green/corrupt frames)
    private val idrReceived = AtomicBoolean(false)

    // ✅ Single reused BufferInfo (no per-frame allocation)
    private val bufferInfo = MediaCodec.BufferInfo()

    // ✅ Jitter buffer target — بيتزامن Live من الموبايل عبر CMD_SET_BUFFER_JITTER_MS.
    // 0 = نفس السلوك القديم zero-buffer المباشر.
    @Volatile private var bufferTargetMs: Long = 150L

    private data class TimedNal(val nal: FrameAssembler.AssembledNal, val arrivalUptimeMs: Long)
    private val jitterQueue = ArrayDeque<TimedNal>(64)
    private val jitterQueueLock = Any()

    fun setSurface(surface: Surface?) {
        this.surface = surface
    }

    fun start(scope: CoroutineScope) {
        if (surface == null) {
            Log.e(tag, "Cannot start — no Surface set")
            return
        }
        released.set(false)
        initDecoder()
        audioEngine.start()
        connectJob = scope.launch(Dispatchers.IO) {
            client.connect(
                onStateChange = { connected ->
                    Log.i(tag, "Socket connected=$connected")
                    if (!connected) {
                        idrReceived.set(false)
                        assembler.reset()
                        synchronized(jitterQueueLock) { jitterQueue.clear() }
                    }
                },
                onChunk = { chunk ->
                    if (chunk.nalType.toInt() == StreamProtocol.PAYLOAD_TYPE_AUDIO_PCM16.toInt()) {
                        audioEngine.onAudioChunk(chunk.data, chunk.dataSize, chunk.timestampUs)
                    } else {
                        val assembled = assembler.onChunk(chunk) ?: return@connect
                        feedDecoder(assembled)
                    }
                },
                onControlMessage = { msg ->
                    if (msg.command == StreamProtocol.CMD_SET_BUFFER_JITTER_MS) {
                        bufferTargetMs = msg.value.toLong().coerceIn(0L, 800L)
                        Log.i(tag, "Jitter buffer target updated → ${bufferTargetMs}ms")
                    }
                }
            )
        }
    }

    private fun initDecoder() {
        try {
            // Use the agreed resolution from StreamProtocol
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                StreamProtocol.WEAR_W_FULL,
                StreamProtocol.WEAR_H_FULL
            ).apply {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_OPERATING_RATE, StreamProtocol.WEAR_FPS_FULL)
            }

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            // ✅ ASYNC mode — no blocking dequeueInputBuffer ever
            codec.setCallback(decoderCallback, decoderHandler)
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
            Log.i(tag, "Decoder started (async callback mode)")
        } catch (e: Exception) {
            Log.e(tag, "Decoder init failed", e)
        }
    }

    /**
     * ✅ ASYNC callback — called by decoder thread when input buffer is free.
     * No blocking, no polling. Zero stutter.
     */
    private val decoderCallback = object : MediaCodec.Callback() {
        // Queue of assembled NALs waiting for free input buffers
        private val pendingNals = ArrayDeque<FrameAssembler.AssembledNal>(32)

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            val nal = synchronized(pendingNals) { pendingNals.removeFirstOrNull() } ?: run {
                // No pending NAL — buffer will be released on next feedDecoder call
                synchronized(freeInputBuffers) { freeInputBuffers.add(index) }
                return
            }
            submitNalToBuffer(codec, index, nal)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo
        ) {
            // Render immediately — the buffer info reuse is safe here (Callback delivers one at a time)
            codec.releaseOutputBuffer(index, true)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(tag, "Decoder error: ${e.message} recoverable=${e.isRecoverable}")
            if (e.isRecoverable) codec.reset()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.d(tag, "Output format changed: $format")
        }

        // ✅ Feed NAL to a free input buffer
        private val freeInputBuffers = ArrayDeque<Int>(8)

        fun enqueueNal(nal: FrameAssembler.AssembledNal) {
            val freeIdx = synchronized(freeInputBuffers) { freeInputBuffers.removeFirstOrNull() }
            if (freeIdx != null) {
                val c = decoder ?: return
                submitNalToBuffer(c, freeIdx, nal)
            } else {
                synchronized(pendingNals) {
                    if (pendingNals.size < 32) pendingNals.addLast(nal)
                    else Log.w(tag, "NAL queue full — dropping frame")
                }
            }
        }
    }

    private fun feedDecoder(nal: FrameAssembler.AssembledNal) {
        if (released.get()) return

        if (!idrReceived.get()) {
            if (!nal.isKeyframe) return
            idrReceived.set(true)
            Log.i(tag, "First IDR received — decoding started")
        }

        if (bufferTargetMs <= 0L) {
            decoderCallback.enqueueNal(nal)   // نفس السلوك القديم zero-buffer
            return
        }

        val timed = TimedNal(nal, android.os.SystemClock.uptimeMillis())
        synchronized(jitterQueueLock) {
            if (jitterQueue.size >= 64) {
                jitterQueue.removeFirstOrNull()
                Log.w(tag, "Jitter queue overflow — dropped oldest buffered NAL")
            }
            jitterQueue.addLast(timed)
        }
        decoderHandler.postDelayed({ drainDueNals() }, bufferTargetMs)
    }

    private fun drainDueNals() {
        val now = android.os.SystemClock.uptimeMillis()
        val due = ArrayList<FrameAssembler.AssembledNal>(4)
        synchronized(jitterQueueLock) {
            while (jitterQueue.isNotEmpty() && now - jitterQueue.first().arrivalUptimeMs >= bufferTargetMs) {
                due.add(jitterQueue.removeFirst().nal)
            }
        }
        due.forEach { decoderCallback.enqueueNal(it) }
    }

    private fun submitNalToBuffer(
        codec: MediaCodec,
        index: Int,
        nal: FrameAssembler.AssembledNal
    ) {
        try {
            val buf = codec.getInputBuffer(index) ?: return
            buf.clear()
            buf.put(nal.data, 0, nal.size)
            val flags = if (nal.isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            codec.queueInputBuffer(index, 0, nal.size, nal.timestampUs, flags)
        } catch (e: Exception) {
            Log.w(tag, "submitNalToBuffer error: ${e.message}")
        }
    }

    fun acquire() {
        refCount.incrementAndGet()
    }

    fun release() {
        if (refCount.decrementAndGet() <= 0) {
            if (!released.compareAndSet(false, true)) return
            audioEngine.stop()
            connectJob?.cancel()
            client.close()
            synchronized(jitterQueueLock) { jitterQueue.clear() }
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) {
                Log.w(tag, "Decoder release error: ${e.message}")
            }
            decoder = null
            assembler.reset()
            decoderThread.quit()
            Log.i(tag, "Player released (refCount reached 0)")
        } else {
            Log.d(tag, "Player release skipped (refCount: ${refCount.get()})")
        }
    }
}
