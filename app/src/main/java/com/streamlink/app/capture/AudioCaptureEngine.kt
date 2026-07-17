package com.streamlink.app.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import com.streamlink.shared.DirectSocketServer
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.WireBufferPool
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioCaptureEngine v2 — يلتقط صوت النظام (Playback Audio Capture, API 29+)
 * باستخدام نفس الـMediaProjection بتاع تصوير الشاشة، ويبعت PCM16 خام فريم كل 20ms
 * على نفس الـwire protocol بتاع الفيديو (nalType = PAYLOAD_TYPE_AUDIO_PCM16).
 *
 * PCM خام بدل AAC/Opus عمدًا: صفر MediaCodec إضافي، صفر latency queue إضافي،
 * والبيتريت أصلًا صغير (384kbps).
 */
@Singleton
class AudioCaptureEngine @Inject constructor(
    private val socketServer: DirectSocketServer
) {
    private val tag = "AudioCaptureEngine"
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private val globalAudioSeq = AtomicInteger(0)

    @SuppressLint("MissingPermission")
    fun start(mediaProjection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(tag, "Playback Audio Capture محتاج Android 10+ — اتجاهل على الإصدار ده")
            return
        }
        if (running.get()) return

        val minBufBytes = AudioRecord.getMinBufferSize(
            StreamProtocol.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes <= 0) {
            Log.e(tag, "getMinBufferSize فشل — إعدادات مش مدعومة على الجهاز ده")
            return
        }

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(StreamProtocol.AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufBytes * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            Log.e(tag, "AudioRecord init failed: ${e.message}")
            return
        }

        audioRecord?.startRecording()
        running.set(true)
        captureThread = Thread({ captureLoop() }, "SL-AudioCapture").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
            start()
        }
        Log.i(tag, "Audio capture started (${StreamProtocol.AUDIO_SAMPLE_RATE}Hz mono PCM16)")
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // using 960 bytes for AUDIO_FRAME_BYTES as per constants
        val frameBytes = 960 
        val pcmBuf = ByteArray(frameBytes)

        while (running.get()) {
            val record = audioRecord ?: break
            val read = record.read(pcmBuf, 0, frameBytes)
            if (read <= 0) continue

            val wire = WireBufferPool.acquire()
            val wireSize = encodeAudioWireFrame(wire, pcmBuf, read)
            socketServer.sendPooledWire(wire, wireSize)
        }
    }

    /** نفس شكل هيدر الفيديو (25 بايت) بالظبط — الفرق بس في nalType */
    private fun encodeAudioWireFrame(wire: ByteArray, pcm: ByteArray, payloadSize: Int): Int {
        val buffer = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(StreamProtocol.MAGIC_NUMBER)
        buffer.put(StreamProtocol.PROTOCOL_VERSION)
        buffer.putInt(globalAudioSeq.getAndIncrement())     // نطاق seq مستقل عن الفيديو
        buffer.putShort(0)                                   // chunkIdx = 0
        buffer.putShort(1)                                   // totalChunks = 1 دايمًا
        buffer.put(0x00)                                      // flags (مش keyframe)
        buffer.put(StreamProtocol.PAYLOAD_TYPE_AUDIO_PCM16)
        buffer.putShort(payloadSize.toShort())
        buffer.putLong(System.nanoTime() / 1000L)
        System.arraycopy(pcm, 0, wire, StreamProtocol.WIRE_HEADER_SIZE, payloadSize)
        return StreamProtocol.WIRE_HEADER_SIZE + payloadSize
    }

    fun stop() {
        running.set(false)
        captureThread?.join(500)
        captureThread = null
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping audio record: ${e.message}")
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing audio record: ${e.message}")
        }
        audioRecord = null
        Log.i(tag, "Audio capture stopped")
    }
}
