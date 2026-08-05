package com.streamlink.app.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import com.streamlink.shared.AdaptiveBufferChannel
import com.streamlink.shared.EncodingProfile
import com.streamlink.shared.FramePacket
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.telemetry.StreamMetricsSource
import com.streamlink.app.core.safeExec
import com.streamlink.app.core.safeRun
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware H.264 encoder backed by MediaCodec.
 *
 * Nano fixes:
 * N1 — outputChannel uses explicit capacity=64 (not Channel.BUFFERED which is
 *       platform-dependent) with onUndeliveredElement releasing FramePackets
 *       to prevent MediaCodec buffer starvation/deadlock on DROP_OLDEST overflow.
 * N2 — Encoder runs on THREAD_PRIORITY_URGENT_DISPLAY HandlerThread.
 * N3 — Consecutive error circuit breaker triggers onEncoderError callback.
 */
class HardwareEncoder(
    private var width: Int = StreamProtocol.WEAR_W_FULL,
    private var height: Int = StreamProtocol.WEAR_H_FULL,
    private val initialBitrateKbps: Int = StreamProtocol.WEAR_BPS_FULL,
    private var targetFps: Int = StreamProtocol.WEAR_FPS_FULL,
    var onEncoderError: (() -> Unit)? = null
) {
    private val tag = "HardwareEncoder"

    // ✅ 5.1: Per-session HardenedFrameProcessor — declared as var so AppModule can
    // inject the shared instance (from MirrorDataPlane) post-construction.
    // Falls back to a local instance for standalone usage (tests, preview, etc.).
    var frameProcessor: com.streamlink.shared.HardenedFrameProcessor =
        com.streamlink.shared.HardenedFrameProcessor()

    // ✅ FIX N1: Explicit capacity + onUndeliveredElement prevents MediaCodec deadlock.
    // When channel drops a FramePacket via DROP_OLDEST, the callback calls packet.release()
    // which returns the buffer index to MediaCodec, preventing buffer starvation.
    val outputChannel: AdaptiveBufferChannel<FramePacket> = AdaptiveBufferChannel(
        capacity = 64,
        onDropped = { packet ->
            packet.release()
            StreamMetricsSource.active?.recordDrop()
        }
    )

    /**
     * Forces the encoder to generate an Instant KeyFrame (I-Frame).
     * Useful for recovering from packet loss or providing immediate video on fresh connections.
     */
    fun forceInstantKeyFrame() {
        if (released.get()) return
        safeExec(tag, "forceInstantKeyFrame") {
            val bundle = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec?.setParameters(bundle)
            Log.i(tag, "Requested Instant KeyFrame")
        }
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    var onSurfaceChanged: ((Surface) -> Unit)? = null

    val encoderSurface: Surface? get() = inputSurface
    val codec: MediaCodec? get() = mediaCodec
    val currentWidth: Int get() = width
    val currentHeight: Int get() = height

    var telemetryRingBuffer: com.streamlink.app.core.telemetry.TelemetryRingBuffer? = null


    // ✅ High-priority encoder thread — matches display frame delivery priority
    private val encoderThread = HandlerThread(
        "SL-Encoder-${width}x${height}",
        Process.THREAD_PRIORITY_URGENT_DISPLAY
    ).also { it.start() }
    private val encoderHandler = Handler(encoderThread.looper)

    private var frameIntervalNs = 1_000_000_000L / targetFps
    private val lastFrameNs = AtomicLong(0L)
    private var currentBitrateKbps = initialBitrateKbps
    private val released = AtomicBoolean(false)
    private val consecutiveErrors = AtomicInteger(0)
    private val maxConsecutiveErrors = 5

    fun initialize(): Boolean {
        if (released.get()) {
            Log.w(tag, "initialize() on released encoder")
            return false
        }
        return safeRun(tag, "initialize", report = true, fallback = false) {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, currentBitrateKbps * 1000)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps)
                }
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
                // ✅ KEY_LATENCY=0 forces zero-delay mode — critical for real-time
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { setInteger(MediaFormat.KEY_LATENCY, 0) }
                        .onFailure { Log.w(tag, "KEY_LATENCY unsupported on this HAL") }
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.setCallback(encoderCallback, encoderHandler)
            codec.start()
            mediaCodec = codec
            consecutiveErrors.set(0)
            Log.i(tag, "Encoder ready: ${width}x${height}@${targetFps}fps ${currentBitrateKbps}Kbps")
            true
        } ?: false
    }

    // ─── Unified Entry Point (C-5 Fix) ──────────────────────────────────────────
    //
    // applyProfile() هي نقطة الكتابة الوحيدة المعتمدة على HardwareEncoder.
    // كل المكونات الخارجية (UnifiedQualityAuthority, SettingsPrefs, CMD_SET_BITRATE)
    // يجب أن تمر عبرها فقط — setBitrate/reconfigure للاستخدام الداخلي فقط.
    //
    // يستخدم Mutex من coroutines لضمان:
    //  - عدم حجب threads (مناسب لـ Coroutine Scheduler)
    //  - Cooldown مشترك بين Resolution Change و Bitrate-Only Change
    //  - Log موحد لكل تغيير

    private val applyProfileMutex = Mutex()
    private var lastAppliedMs = 0L
    private val MIN_PROFILE_INTERVAL_MS = 500L
    private var lastAppliedProfile: EncodingProfile? = null

    /**
     * نقطة الدخول الموحدة لتطبيق أي تغيير على الإنكودر.
     * يُستدعى دائمًا من UnifiedQualityAuthority فقط.
     *
     * @param profile  الحالة الكاملة المطلوبة للإنكودر
     * @param reason   سبب التغيير للـ log (اختياري)
     * @param force    تجاوز الـ Cooldown (للتدخل اليدوي من المستخدم)
     */
    fun applyProfile(
        profile: EncodingProfile,
        reason: String = "auto",
        force: Boolean = false
    ) {
        if (released.get()) return
        // runBlocking آمن هنا لأن applyProfile يُستدعى من encoder thread أو dispatchers.IO
        runBlocking {
            applyProfileMutex.withLock {
                val now = System.currentTimeMillis()
                val last = lastAppliedProfile

                val resolutionChanged = last == null ||
                    last.width != profile.width ||
                    last.height != profile.height ||
                    last.fps != profile.fps

                val bitrateChanged = last == null || last.bitrateKbps != profile.bitrateKbps

                if (!resolutionChanged && !bitrateChanged) return@withLock

                // Cooldown للـ resolution rebuild (expensive) — bypass للتدخل اليدوي
                if (resolutionChanged && !force && (now - lastAppliedMs) < MIN_PROFILE_INTERVAL_MS) {
                    Log.d(tag, "applyProfile: cooldown (${now - lastAppliedMs}ms), skipping resolution change")
                    // لو بس البِتريت اتغير خلال الـ cooldown، طبّقه بدون rebuild
                    if (bitrateChanged) {
                        Log.d(tag, "applyProfile [$reason]: bitrate-only → ${profile.bitrateKbps}kbps")
                        setBitrateInternal(profile.bitrateKbps)
                        lastAppliedProfile = last?.copy(bitrateKbps = profile.bitrateKbps)
                    }
                    return@withLock
                }

                Log.i(tag, "applyProfile [$reason]: ${last?.label ?: "init"} → ${profile.label} " +
                    "(${profile.width}x${profile.height}@${profile.fps}fps, ${profile.bitrateKbps}kbps)")

                if (resolutionChanged) {
                    lastAppliedMs = now
                    rebuildCodecInternal(profile)
                } else if (bitrateChanged) {
                    setBitrateInternal(profile.bitrateKbps)
                }

                lastAppliedProfile = profile
            }
        }
    }

    private var lastReconfigureMs = 0L

    /**
     * داخلي فقط — يُستدعى من applyProfile() أو reconfigure().
     * يوقف الـ Codec الحالي ويُعيد بناءه بالكامل بالـ profile الجديد.
     */
    private fun rebuildCodecInternal(profile: EncodingProfile) {
        Log.i(tag, "rebuildCodec: ${profile.label} (${profile.width}x${profile.height}@${profile.fps}fps ${profile.bitrateKbps}kbps)")
        safeExec(tag, "rebuildCodec.stop")  { mediaCodec?.stop() }
        safeExec(tag, "rebuildCodec.release") { mediaCodec?.release() }
        mediaCodec = null
        safeExec(tag, "rebuildCodec.surfaceRelease") { inputSurface?.release() }
        inputSurface = null

        width             = profile.width
        height            = profile.height
        targetFps         = profile.fps
        currentBitrateKbps = profile.bitrateKbps
        frameIntervalNs   = 1_000_000_000L / targetFps

        if (initialize()) {
            inputSurface?.let { onSurfaceChanged?.invoke(it) }
        }
    }

    /** داخلي فقط — يُستدعى من applyProfile() أو setBitrate() */
    private fun setBitrateInternal(kbps: Int) {
        val clamped = kbps.coerceIn(200, 4000)
        if (clamped == currentBitrateKbps) return
        currentBitrateKbps = clamped
        safeExec(tag, "setBitrateInternal") {
            bitrateBundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped * 1000)
            mediaCodec?.setParameters(bitrateBundle)
            Log.d(tag, "setBitrateInternal → ${clamped}kbps")
        }
    }

    // ─── Legacy API (للتوافق مؤقتًا — سيُزال بعد اكتمال Phase 7) ──────────────
    // تفضيل: استخدم applyProfile() مباشرة من UnifiedQualityAuthority

    @Deprecated("Use UnifiedQualityAuthority.applyProfile() instead for atomic resolution/bitrate changes.")
    fun reconfigure(profile: com.streamlink.shared.ResolutionProfile) {
        if (width == profile.width && height == profile.height && targetFps == profile.fps) return
        val now = System.currentTimeMillis()
        if (now - lastReconfigureMs < 2000L) {
            Log.d(tag, "Ignoring rapid reconfigure request (${now - lastReconfigureMs}ms ago)")
            return
        }
        lastReconfigureMs = now
        rebuildCodecInternal(EncodingProfile.from(profile))
    }


    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            if (released.get()) {
                codec.releaseOutputBuffer(index, false)
                return
            }

            val buffer: ByteBuffer = codec.getOutputBuffer(index) ?: run {
                codec.releaseOutputBuffer(index, false)
                return
            }

            // Config frames contain SPS/PPS, must be passed to HardenedFrameProcessor
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                // ✅ 5.1: Use per-session instance, not global object
                frameProcessor.processAndObtain(buffer, info)
                codec.releaseOutputBuffer(index, false)
                return
            }

            if (info.size <= 0 || paused.get()) {
                codec.releaseOutputBuffer(index, false)
                return
            }

            // Frame rate throttle — don't exceed targetFps (never drop keyframes)
            val now = System.nanoTime()
            val last = lastFrameNs.get()
            val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            if (!isKeyframe && last > 0 && now - last < frameIntervalNs * 0.85) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            lastFrameNs.set(now)

            val packet = buildPacket(buffer, info) {
                codec.releaseOutputBuffer(index, false)
            }

            telemetryRingBuffer?.let { rb ->
                val metrics = rb.acquireNextForWrite(packet.timestampUs.toInt())
                // For surface encoding, we don't have explicit encoderInTimestamp. Use lastFrameNs approximation.
                metrics.encoderInTimestamp = last
                metrics.encoderOutTimestamp = now
                metrics.payloadSize = info.size
                metrics.isKeyframe = isKeyframe
            }

            val sent = outputChannel.trySend(packet)
            if (!sent.isSuccess) {
                // Channel full — FramePacket will be released by onUndeliveredElement
                // (no manual release needed here — avoids double-release)
                Log.v(tag, "Channel full — frame dropped by overflow handler")
            }
            consecutiveErrors.set(0)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            val errors = consecutiveErrors.incrementAndGet()
            Log.e(tag, "Codec error ($errors/$maxConsecutiveErrors): ${e.message}")
            if (errors >= maxConsecutiveErrors) {
                Log.e(tag, "Encoder circuit breaker triggered — calling onEncoderError")
                onEncoderError?.invoke()
            }
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface-mode encoder — no input buffer management needed
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.d(tag, "Output format changed: $format")
        }
    }

    private fun buildPacket(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        releaseCallback: () -> Unit
    ): FramePacket {
        val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        return FramePacket(
            buffer = buffer,
            offset = info.offset,
            size = info.size,
            timestampUs = info.presentationTimeUs,
            isKeyframe = isKeyframe,
            releaseCallback = releaseCallback
        )
    }

    // Nano-level: Pre-allocate Bundles to prevent GC during the Hot Path
    private val bitrateBundle = Bundle()
    private val syncFrameBundle = Bundle().apply {
        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
    }

    @Deprecated("Use UnifiedQualityAuthority.adjustBitrateOnly() instead for synchronized state.")
    fun setBitrate(kbps: Int) {
        if (released.get()) return
        val clamped = kbps.coerceIn(200, 4000)
        if (clamped == currentBitrateKbps) return
        currentBitrateKbps = clamped
        safeExec(tag, "setBitrate", report = true) {
            bitrateBundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped * 1000)
            mediaCodec?.setParameters(bitrateBundle)
            Log.d(tag, "Bitrate → ${clamped}Kbps")
        }
    }

    fun forceKeyframe() {
        if (released.get()) return
        safeExec(tag, "forceKeyframe") {
            mediaCodec?.setParameters(syncFrameBundle)
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        safeExec(tag, "release.stop")    { mediaCodec?.stop() }
        safeExec(tag, "release.release") { mediaCodec?.release() }
        mediaCodec = null
        inputSurface?.release()
        inputSurface = null
        encoderThread.quit()
        outputChannel.close()
        Log.i(tag, "Encoder released")
    }

    private val paused = AtomicBoolean(false)

    fun pause() {
        if (!paused.compareAndSet(false, true)) return
        Log.i(tag, "Encoder paused to save battery/thermal")
    }

    fun resume() {
        if (!paused.compareAndSet(true, false)) return
        Log.i(tag, "Encoder resumed")
        forceKeyframe()
    }

    /**
     * Stage 5: Codec Recovery. Flushes all internal buffers and requests an immediate keyframe.
     */
    fun flushAndRestart() {
        if (released.get()) return
        val flushed = safeRun(tag, "flushAndRestart", report = true) {
            Log.w(tag, "Hardware Watchdog triggered flushAndRestart()")
            mediaCodec?.flush()
            forceKeyframe()
            true
        }
        if (flushed == null) {
            // flush itself failed — signal orchestrator to do a full rebuild
            onEncoderError?.invoke()
        }
    }

    fun setThermalThrottled(throttled: Boolean) {
        val priority = if (throttled) Process.THREAD_PRIORITY_DISPLAY else Process.THREAD_PRIORITY_URGENT_DISPLAY
        safeExec(tag, "setThermalThrottled") {
            Process.setThreadPriority(encoderThread.threadId, priority)
            Log.i(tag, "Encoder thread priority updated (throttled=$throttled)")
        }
    }

    fun currentBitrateKbps(): Int = currentBitrateKbps
    val isReleased: Boolean get() = released.get()
}
