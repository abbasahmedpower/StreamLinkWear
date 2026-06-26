package com.streamlink.shared

import android.media.MediaCodec
import java.nio.ByteBuffer

/**
 * Validates, SPS/PPS-injects and prepares frames for chunking.
 * Ensures I-frames always carry codec parameter sets.
 */
object HardenedFrameProcessor {
    @Volatile private var cachedSps: ByteArray? = null
    @Volatile private var cachedPps: ByteArray? = null

    fun processAndObtain(buf: ByteBuffer, info: MediaCodec.BufferInfo): HardenedFrame? {
        if (info.size <= 0) return null

        val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

        // Cache SPS/PPS from codec config frames
        if (isConfig) {
            cacheParameterSets(buf, info)
            return null  // Don't send config frames directly
        }

        // For keyframes, prepend cached SPS+PPS if available
        return if (isKeyframe && cachedSps != null && cachedPps != null) {
            val combined = buildKeyframeWithParams(buf, info)
            HardenedFrame(
                buffer = combined,
                size = combined.limit(),
                timestampUs = info.presentationTimeUs,
                isKeyframe = true,
                sps = cachedSps,
                pps = cachedPps
            )
        } else {
            val view = buf.duplicate().apply {
                position(info.offset)
                limit(info.offset + info.size)
            }
            HardenedFrame(
                buffer = view,
                size = info.size,
                timestampUs = info.presentationTimeUs,
                isKeyframe = isKeyframe
            )
        }
    }

    private fun cacheParameterSets(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        val view = buf.duplicate()
        view.position(info.offset)
        view.get(data)

        // Parse SPS (NAL type 7) and PPS (NAL type 8)
        var i = 0
        while (i < data.size - 4) {
            if (readInt(data, i) == 0x00000001) {
                val nalType = data[i + 4].toInt() and 0x1F
                val nextStart = findNextStart(data, i + 4)
                val end = if (nextStart == -1) data.size else nextStart
                when (nalType) {
                    7 -> cachedSps = data.copyOfRange(i, end)
                    8 -> cachedPps = data.copyOfRange(i, end)
                }
                i = if (nextStart == -1) data.size else nextStart
            } else {
                i++
            }
        }
    }

    private fun buildKeyframeWithParams(buf: ByteBuffer, info: MediaCodec.BufferInfo): ByteBuffer {
        val sps = cachedSps ?: return buf.duplicate()
        val pps = cachedPps ?: return buf.duplicate()
        val totalSize = sps.size + pps.size + info.size
        val combined = ByteBuffer.allocateDirect(totalSize)
        combined.put(sps)
        combined.put(pps)
        val view = buf.duplicate()
        view.position(info.offset)
        view.limit(info.offset + info.size)
        combined.put(view)
        combined.flip()
        return combined
    }

    private fun findNextStart(data: ByteArray, from: Int): Int {
        for (i in from until data.size - 3) {
            if (readInt(data, i) == 0x00000001) return i
        }
        return -1
    }

    private fun readInt(data: ByteArray, i: Int): Int =
        ((data[i].toInt() and 0xFF) shl 24) or
        ((data[i+1].toInt() and 0xFF) shl 16) or
        ((data[i+2].toInt() and 0xFF) shl 8) or
        (data[i+3].toInt() and 0xFF)
}
