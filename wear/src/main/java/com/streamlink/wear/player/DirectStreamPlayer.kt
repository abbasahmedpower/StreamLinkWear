package com.streamlink.wear.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.streamlink.shared.DirectSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * DirectStreamPlayer — receives H.264 stream from phone via DirectSocketClient
 * and renders it on the Wear OS display surface.
 */
class DirectStreamPlayer @Inject constructor(
    private val client: DirectSocketClient
) {
    private val tag = "DirectStreamPlayer"

    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    private var decodeJob: Job? = null

    fun setSurface(surface: Surface?) {
        this.surface = surface
    }

    fun start(scope: CoroutineScope) {
        if (surface == null) {
            Log.e(tag, "Cannot start player without a Surface")
            return
        }

        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280
            ).apply {
                // Low latency settings
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
            }

            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder?.configure(format, surface, null, 0)
            decoder?.start()

            decodeJob = scope.launch(Dispatchers.IO) {
                client.connect(
                    onStateChange = { connected ->
                        Log.i(tag, "Client connected: \$connected")
                    },
                    onChunk = { chunk ->
                        feedDecoder(chunk)
                    }
                )
            }
            Log.i(tag, "Player started successfully")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start player", e)
        }
    }

    private fun feedDecoder(chunk: DirectSocketClient.WireChunk) {
        val codec = decoder ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val buffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                buffer?.clear()
                buffer?.put(chunk.data, 0, chunk.dataSize)
                
                val flags = if (chunk.isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(
                    inputIndex, 0, chunk.dataSize, chunk.timestampUs, flags
                )
            }

            val info = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                // Render to surface
                codec.releaseOutputBuffer(outputIndex, true)
                outputIndex = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(tag, "Decode error", e)
        }
    }

    fun release() {
        decodeJob?.cancel()
        client.close()
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing decoder", e)
        }
        decoder = null
        Log.i(tag, "Player released")
    }
}
