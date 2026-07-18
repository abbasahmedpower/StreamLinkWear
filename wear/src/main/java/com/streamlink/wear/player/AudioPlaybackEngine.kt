package com.streamlink.wear.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.audio.LockFreeAudioRingBuffer
import java.util.concurrent.atomic.AtomicBoolean
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioPlaybackEngine v2 — يستقبل فريمات PCM16 من نفس الـsocket بتاع الفيديو
 * ويشغلها على سماعة/بلوتوث الساعة عبر AudioTrack، مع LockFreeAudioRingBuffer
 * كـjitter buffer (بيستنى 60ms قبل أول تشغيل عشان يمتص تذبذب الشبكة).
 */
@Singleton
class AudioPlaybackEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "AudioPlaybackEngine"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 960 bytes for AUDIO_FRAME_BYTES
    private val ringBuffer = LockFreeAudioRingBuffer(
        capacity = 32, // حتى 640ms buffer قبل ما يقطع
        frameSizeBytes = 960
    )
    private var audioTrack: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private var playbackThread: Thread? = null
    private val prebufferFrames = 3 // 60ms

    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> audioTrack?.pause()
            AudioManager.AUDIOFOCUS_GAIN -> audioTrack?.play()
        }
    }

    fun start() {
        if (running.get()) return
        val minBuf = AudioTrack.getMinBufferSize(
            StreamProtocol.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        
        audioFocusRequest = focusRequest
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(tag, "Audio focus denied, cannot start playback")
            return
        }

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
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
        
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        
        Log.i(tag, "Audio playback stopped")
    }
}
