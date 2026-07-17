package com.streamlink.wear.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.audio.LockFreeAudioRingBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioPlaybackEngine v2 — يستقبل فريمات PCM16 من نفس الـsocket بتاع الفيديو
 * ويشغلها على سماعة/بلوتوث الساعة عبر AudioTrack، مع LockFreeAudioRingBuffer
 * كـjitter buffer (بيستنى 60ms قبل أول تشغيل عشان يمتص تذبذب الشبكة).
 */
@Singleton
class AudioPlaybackEngine @Inject constructor() {
    private val tag = "AudioPlaybackEngine"

    // 960 bytes for AUDIO_FRAME_BYTES
    private val ringBuffer = LockFreeAudioRingBuffer(
        capacity = 32, // حتى 640ms buffer قبل ما يقطع
        frameSizeBytes = 960
    )
    private var audioTrack: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private var playbackThread: Thread? = null
    private val prebufferFrames = 3 // 60ms

    fun start() {
        if (running.get()) return
        val minBuf = AudioTrack.getMinBufferSize(
            StreamProtocol.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(StreamProtocol.AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 960 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        running.set(true)
        playbackThread = Thread({ playbackLoop() }, "SL-AudioPlayback").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
            start()
        }
        Log.i(tag, "Audio playback started")
    }

    /** بينده من onChunk في DirectStreamPlayer لما nalType == PAYLOAD_TYPE_AUDIO_PCM16 */
    fun onAudioChunk(data: ByteArray, size: Int, timestampUs: Long) {
        if (running.get()) ringBuffer.write(data, size, timestampUs)
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ByteArray(960)

        while (running.get() && ringBuffer.availableFrames() < prebufferFrames) {
            Thread.sleep(5) // prebuffer قبل أول تشغيل
        }
        while (running.get()) {
            val len = ringBuffer.read(frame)
            if (len <= 0) { Thread.sleep(2); continue }
            audioTrack?.write(frame, 0, len)
        }
    }

    fun stop() {
        running.set(false)
        playbackThread?.join(300)
        playbackThread = null
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping audio track: ${e.message}")
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing audio track: ${e.message}")
        }
        audioTrack = null
        ringBuffer.clear()
        Log.i(tag, "Audio playback stopped")
    }
}
