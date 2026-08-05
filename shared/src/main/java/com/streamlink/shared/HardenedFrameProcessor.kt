package com.streamlink.shared

import android.media.MediaCodec
import java.nio.ByteBuffer

/**
 * Validates, SPS/PPS-injects and prepares frames for chunking.
 * Ensures I-frames always carry codec parameter sets.
 *
 * ✅ 5.1: Converted from `object` (process-global singleton) to `class` (per-session instance).
 *
 * Why this matters:
 *  - As an `object`, cachedParams was shared across all sessions in the process. In a
 *    multi-session scenario (NEXUS TITAN multi-device) or when the encoder is restarted
 *    mid-session (e.g., resolution change), stale SPS/PPS from a previous session/config
 *    could contaminate the new stream.
 *  - Vendor encoders sometimes emit SPS and PPS in *separate* CODEC_CONFIG callbacks
 *    (observed on Qualcomm/Exynos). The old code required both to arrive in a single
 *    callback or it would cache nothing. The new pendingSps/pendingPps handles partial arrival.
 *
 * Ownership: instantiate one HardenedFrameProcessor per MediaCodec lifecycle. Call reset()
 * whenever the encoder is restarted (e.g., resolution change, bitrate reconfigure).
 */
class HardenedFrameProcessor {
    private data class SpsPpsPair(val sps: ByteArray, val pps: ByteArray)

    private val cachedParams = java.util.concurrent.atomic.AtomicReference<SpsPpsPair>()

    // ✅ 5.1: Partial-arrival support — some vendor encoders send SPS and PPS separately
    @Volatile private var pendingSps: ByteArray? = null
    @Volatile private var pendingPps: ByteArray? = null

    /**
     * Call when the encoder is restarted (resolution change, bitrate reconfigure, etc.)
     * to clear stale codec parameters before the new CODEC_CONFIG frames arrive.
     */
    fun reset() {
        cachedParams.set(null)
        pendingSps = null
        pendingPps = null
    }

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
        val params = cachedParams.get()
        return if (isKeyframe && params != null) {
            val (combined, releaseCallback) = buildKeyframeWithParams(buf, info, params)
            // Adaptive Deadline Buffer: extend frame deadline to 120ms to absorb network jitter
            val deadlineUs = info.presentationTimeUs + 120_000L
            HardenedFrame(
                buffer = combined,
                size = combined.limit(),
                timestampUs = info.presentationTimeUs,
                deadlineUs = deadlineUs,
                isKeyframe = true,
                sps = params.sps,
                pps = params.pps,
                releaseCallback = releaseCallback
            )
        } else {
            val view = buf.duplicate().apply {
                position(info.offset)
                limit(info.offset + info.size)
            }
            // Adaptive Deadline Buffer: extend frame deadline to 120ms to absorb network jitter
            val deadlineUs = info.presentationTimeUs + 120_000L
            HardenedFrame(
                buffer = view,
                size = info.size,
                timestampUs = info.presentationTimeUs,
                deadlineUs = deadlineUs,
                isKeyframe = isKeyframe,
                releaseCallback = null
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
        var newSps: ByteArray? = null
        var newPps: ByteArray? = null
        while (i < data.size - 4) {
            if (readInt(data, i) == 0x00000001) {
                val nalType = data[i + 4].toInt() and 0x1F
                val nextStart = findNextStart(data, i + 4)
                val end = if (nextStart == -1) data.size else nextStart
                when (nalType) {
                    7 -> newSps = data.copyOfRange(i, end)
                    8 -> newPps = data.copyOfRange(i, end)
                }
                i = if (nextStart == -1) data.size else nextStart
            } else {
                i++
            }
        }

        // ✅ 5.1: Merge newly parsed NALs with any pending ones from a previous partial delivery
        val sps = newSps ?: pendingSps
        val pps = newPps ?: pendingPps

        if (sps != null && pps != null) {
            cachedParams.set(SpsPpsPair(sps, pps))
            pendingSps = null
            pendingPps = null
        } else {
            // Partial arrival — save what we got for the next CODEC_CONFIG callback
            if (newSps != null) pendingSps = newSps
            if (newPps != null) pendingPps = newPps
        }
    }

    private fun buildKeyframeWithParams(buf: ByteBuffer, info: MediaCodec.BufferInfo, params: SpsPpsPair): Pair<ByteBuffer, () -> Unit> {
        val sps = params.sps
        val pps = params.pps
        val totalSize = sps.size + pps.size + info.size

        val combined = com.streamlink.shared.pool.DynamicByteBufferPool.acquire(totalSize)
        combined.put(sps)
        combined.put(pps)
        val view = buf.duplicate()
        view.position(info.offset)
        view.limit(info.offset + info.size)
        combined.put(view)
        combined.flip()

        val releaseCallback = {
            com.streamlink.shared.pool.DynamicByteBufferPool.release(combined)
        }

        return Pair(combined, releaseCallback)
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
