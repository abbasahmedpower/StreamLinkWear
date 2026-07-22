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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DirectStreamPlayer — H.264 Async Decoder with Zero-Allocation Jitter Buffer.
 */
@Singleton
class DirectStreamPlayer @Inject constructor(
    private val client: DirectSocketClient,
    private val audioEngine: AudioPlaybackEngine
) {
    private val tag = "DirectStreamPlayer"

    private val _discoveryTimedOut = MutableStateFlow(false)
    /** true = mDNS couldn't find the phone within 15s → UI should offer manual IP entry */
    val discoveryTimedOut: StateFlow<Boolean> = _discoveryTimedOut

    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    private var connectJob: Job? = null
    private val released = AtomicBoolean(false)
    private val refCount = AtomicInteger(0)

    // ✅ تتبع زمن البث المتزامن لامتصاص Jitter الشبكة بدقة نانوية
    @Volatile private var jitterBufferMs = 100
    private var firstFrameSystemTimeUs = -1L
    private var firstFramePtsUs = -1L

    private val decoderThread = HandlerThread(
        "SL-Decoder",
        Process.THREAD_PRIORITY_URGENT_DISPLAY
    ).also { it.start() }
    private val decoderHandler = Handler(decoderThread.looper)

    private val assembler = FrameAssembler()
    private val idrReceived = AtomicBoolean(false)

    /**
     * ✅ خفيف الوزن — مسبق التخصيص في Pool لمنع أي GC على الـ Hot Path.
     * يجب أن يكون خارج الـ anonymous object لأن Kotlin لا يسمح بتعريف class داخله.
     */
    private class ScheduledNal(
        var nal: FrameAssembler.AssembledNal? = null,
        var targetSystemTimeUs: Long = 0L
    ) {
        fun reset() {
            nal = null
            targetSystemTimeUs = 0L
        }
    }

    fun setSurface(surface: Surface?) {
        this.surface = surface
    }

    /**
     * تحديث قيمة الـ Buffer ديناميكياً أثناء تشغيل البث دون انقطاع
     */
    fun setJitterBufferMs(ms: Int) {
        this.jitterBufferMs = ms.coerceIn(0, 1000)
        Log.i(tag, "Jitter Buffer dynamically updated to: ${this.jitterBufferMs} ms")
    }

    fun start(scope: CoroutineScope) {
        if (surface == null) {
            Log.e(tag, "Cannot start — no Surface set")
            return
        }
        released.set(false)
        resetJitterBuffer()
        initDecoder()
        audioEngine.start()
        connectJob = scope.launch(Dispatchers.IO) {
            client.connect(
                onStateChange = { connected ->
                    Log.i(tag, "Socket connected=$connected")
                    if (connected) {
                        _discoveryTimedOut.value = false
                    } else {
                        idrReceived.set(false)
                        assembler.reset()
                        resetJitterBuffer()
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
                        setJitterBufferMs(msg.value)
                    }
                },
                onDiscoveryTimedOut = {
                    Log.w(tag, "Auto-discovery timed out — signaling UI for manual IP entry")
                    _discoveryTimedOut.value = true
                }
            )
        }
    }

    /** Called by the UI once the user types the phone's IP manually. */
    fun connectManually(host: String) {
        client.manualHostOverride = host
        _discoveryTimedOut.value = false
        Log.i(tag, "Manual IP override accepted: $host")
    }

    private fun initDecoder() {
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                StreamProtocol.WEAR_W_FULL,
                StreamProtocol.WEAR_H_FULL
            ).apply {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_OPERATING_RATE, StreamProtocol.WEAR_FPS_FULL)
            }

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.setCallback(decoderCallback, decoderHandler)
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
            Log.i(tag, "Decoder started (async callback mode with Jitter Buffer integration)")
        } catch (e: Exception) {
            Log.e(tag, "Decoder init failed", e)
        }
    }

    private fun resetJitterBuffer() {
        firstFrameSystemTimeUs = -1L
        firstFramePtsUs = -1L
        decoderCallback.clear()
    }

    /**
     * ✅ الـ ASYNC Callback الأقوى لمعالجة وضخ البيانات تزامناً مع زمن الـ Playback المستهدف.
     */
    private val decoderCallback = object : MediaCodec.Callback() {
        
        // مسبح كائنات جاهز ومسبق التخصيص لمنع الـ GC نهائياً على مسار البيانات
        private val pendingNals = ArrayDeque<ScheduledNal>(32)
        private val freeInputBuffers = ArrayDeque<Int>(8)
        private val scheduledPool = ArrayDeque<ScheduledNal>(32).apply {
            for (i in 0..31) { add(ScheduledNal()) }
        }

        private val processQueueRunnable = Runnable { processQueue() }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            synchronized(this) {
                freeInputBuffers.addLast(index)
            }
            decoderHandler.post(processQueueRunnable)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            codec.releaseOutputBuffer(index, true)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(tag, "Decoder error: ${e.message} recoverable=${e.isRecoverable}")
            if (e.isRecoverable) codec.reset()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.d(tag, "Output format changed: $format")
        }

        fun enqueueNal(nal: FrameAssembler.AssembledNal) {
            val nowUs = System.nanoTime() / 1000

            // Packet Deadline Check (Drop late frames before decoding to minimize latency)
            if (nal.deadlineUs > 0 && nowUs > nal.deadlineUs) {
                Log.w(tag, "Frame ${nal.nalSeq} exceeded deadline (Late: ${(nowUs - nal.deadlineUs) / 1000}ms). Dropping before Decode!")
                return
            }

            // مزامنة التوقيت مع أول إطار مستلم لتحديد خط الأساس (Baseline)
            if (firstFrameSystemTimeUs == -1L) {
                firstFrameSystemTimeUs = nowUs
                firstFramePtsUs = nal.timestampUs
            }

            // حساب وقت التشغيل الفعلي المستهدف للإطار الحالي مضافاً إليه قيمة الـ Jitter Buffer
            val targetSystemTimeUs = firstFrameSystemTimeUs + 
                    (nal.timestampUs - firstFramePtsUs) + 
                    (jitterBufferMs * 1000L)

            synchronized(this) {
                val scheduled = scheduledPool.removeFirstOrNull() ?: ScheduledNal()
                scheduled.nal = nal
                scheduled.targetSystemTimeUs = targetSystemTimeUs

                if (pendingNals.size < 32) {
                    pendingNals.addLast(scheduled)
                } else {
                    Log.w(tag, "NAL queue full — dropping frame to prevent memory pressure")
                    scheduled.reset()
                    scheduledPool.addLast(scheduled)
                }
            }
            scheduleNextProcess()
        }

        private fun scheduleNextProcess() {
            val nowUs = System.nanoTime() / 1000
            val next = synchronized(this) { pendingNals.firstOrNull() }
            
            if (next != null) {
                val delayMs = ((next.targetSystemTimeUs - nowUs) / 1000).coerceAtLeast(0)
                decoderHandler.removeCallbacks(processQueueRunnable)
                if (delayMs <= 0) {
                    decoderHandler.post(processQueueRunnable)
                } else {
                    // جدولة ذكية عبر نظام الميقاتي الخاص بالخيط لتوفير البطارية والـ CPU
                    decoderHandler.postDelayed(processQueueRunnable, delayMs)
                }
            }
        }

        private fun processQueue() {
            val nowUs = System.nanoTime() / 1000
            val codec = decoder ?: return

            while (true) {
                val scheduled = synchronized(this) {
                    val first = pendingNals.firstOrNull()
                    if (first != null && first.targetSystemTimeUs <= nowUs && freeInputBuffers.isNotEmpty()) {
                        pendingNals.removeFirst()
                    } else {
                        null
                    }
                } ?: break

                val freeIdx = synchronized(this) { freeInputBuffers.removeFirstOrNull() }

                if (freeIdx != null && scheduled.nal != null) {
                    val nal = scheduled.nal ?: return@processQueue
                    submitNalToBuffer(codec, freeIdx, nal)
                }

                synchronized(this) {
                    scheduled.reset()
                    scheduledPool.addLast(scheduled)
                }
            }
            scheduleNextProcess()
        }

        fun clear() {
            synchronized(this) {
                pendingNals.forEach {
                    it.reset()
                    scheduledPool.addLast(it)
                }
                pendingNals.clear()
                freeInputBuffers.clear()
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

        decoderCallback.enqueueNal(nal)
    }

    private fun submitNalToBuffer(codec: MediaCodec, index: Int, nal: FrameAssembler.AssembledNal) {
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
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) {
                Log.w(tag, "Decoder release error: ${e.message}")
            }
            decoder = null
            assembler.reset()
            resetJitterBuffer()
            decoderThread.quit()
            Log.i(tag, "Player released (refCount reached 0)")
        } else {
            Log.d(tag, "Player release skipped (refCount: ${refCount.get()})")
        }
    }
}
