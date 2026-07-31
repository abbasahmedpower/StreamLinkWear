package com.streamlink.wear.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.streamlink.shared.StreamProtocol
import com.streamlink.wear.sensor.WristMotionSensor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.channels.FileChannel
import android.content.res.AssetFileDescriptor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * On-device TFLite inference for proactive stream adaptation on the watch.
 *
 * It consumes the same stream_predictor.tflite contract as the phone-side
 * ContextIntelligenceEngine. If the model is absent, it still logs Room events
 * so ai_training/export_from_room.py can build a real dataset later.
 *
 * Enhancements (Layer-6 fixes):
 *  • ClassBiasAdapter  — lightweight EMA bias correction that personalises
 *    predictions to each user without sending any data off-device (Fix 2).
 *  • MotionCalibrator  — hourly rolling-mean drift correction for the
 *    gyroscope/accelerometer readings (Fix 3).
 *  • Thread Offloading — inference runs on [inferenceDispatcher] off UI thread.
 */
@Singleton
class LocalPredictiveEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensor: WristMotionSensor,
    private val logger: AIEventLogger,
    private val socketClient: com.streamlink.shared.DirectSocketClient
) : Closeable {
    private val tag = "PredictiveEngine"

    private val inferenceDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SL-WearPredictive").also { it.priority = Thread.NORM_PRIORITY - 1; it.isDaemon = true }
    }.asCoroutineDispatcher()

    private val INFERENCE_TIMEOUT_MS = 200L

    @Volatile private var isClosed = false
    private var job: Job? = null
    private var tflite: Interpreter? = null
    private var modelOutputClasses: Int = 0
    private var currentBitrate = 0f
    private var afd: AssetFileDescriptor? = null
    private var channel: FileChannel? = null

    // ── Fix 2: Local EMA bias adapter ────────────────────────────────────────
    private val biasAdapter = ClassBiasAdapter(
        numClasses = DEFAULT_MODEL_OUTPUT_CLASSES,
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    // ── Fix 3: Motion sensor drift calibrator ────────────────────────────────
    private val motionCalibrator = MotionCalibrator(
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    init {
        for (assetName in MODEL_ASSET_NAMES) {
            try {
                val currentAfd = context.assets.openFd(assetName)
                if (currentAfd.declaredLength < 100) {
                    Log.w(tag, "Model $assetName is too small (${currentAfd.declaredLength} bytes), ignoring it")
                    continue
                }

                val currentChannel = java.io.FileInputStream(currentAfd.fileDescriptor).channel
                val buffer = currentChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    currentAfd.startOffset,
                    currentAfd.declaredLength
                )
                tflite = Interpreter(buffer)
                modelOutputClasses = tflite?.getOutputTensor(0)?.shape()?.lastOrNull() ?: 0
                Log.i(tag, "TFLite model loaded successfully: $assetName outputs=$modelOutputClasses")

                // Resize adapter if model reports different class count
                if (modelOutputClasses > 0 && modelOutputClasses != DEFAULT_MODEL_OUTPUT_CLASSES) {
                    biasAdapter.resize(modelOutputClasses)
                }

                afd = currentAfd
                channel = currentChannel
                break
            } catch (_: java.io.FileNotFoundException) {
                // Try next supported filename.
            } catch (e: Exception) {
                Log.w(tag, "Failed to load $assetName: ${e.message}")
            }
        }

        if (tflite == null) {
            Log.i(tag, "No TFLite model found; logging training events only")
        }
    }

    fun start(
        scope: CoroutineScope,
        motionProvider: () -> Float,
        networkProvider: () -> Float
    ) {
        job?.cancel()
        job = scope.launch(inferenceDispatcher) {
            var calibrationTick = 0L

            while (isActive && !isClosed) {
                delay(1_000L)
                calibrationTick++

                // ── Fix 3: feed raw motion into calibrator every second ───────
                val rawMotion = motionProvider().let { if (it.isFinite()) it else 0f }
                motionCalibrator.feed(rawMotion)

                // Trigger hourly drift recalibration
                if (calibrationTick % CALIBRATION_INTERVAL_SEC == 0L) {
                    motionCalibrator.recalibrate()
                    Log.i(tag, "[Calibrate] driftOffset=${motionCalibrator.driftOffset}")
                }

                val correctedMotion = motionCalibrator.correct(rawMotion)
                val rttRaw = networkProvider()
                val rttMs = (if (rttRaw.isFinite()) rttRaw else 0f).toLong().coerceAtLeast(0L)

                val recommendedBitrate = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                    inferBitrate(correctedMotion, rttMs)
                } ?: run {
                    Log.w(tag, "Inference watchdog triggered — skipping tick")
                    0f
                }

                if (recommendedBitrate > 0f &&
                    (currentBitrate == 0f || abs(recommendedBitrate - currentBitrate) / currentBitrate >= 0.1f)
                ) {
                    Log.i(tag, "AI recommends bitrate change: ${currentBitrate.toInt()} -> ${recommendedBitrate.toInt()} kbps")
                    currentBitrate = recommendedBitrate
                    socketClient.sendControl(StreamProtocol.CMD_SET_BITRATE, recommendedBitrate.toInt())
                }

                logger.log(
                    "inference_tick",
                    mapOf(
                        "motionIntensity" to correctedMotion,
                        "rttMs" to rttMs,
                        "recommendedBitrate" to recommendedBitrate
                    )
                )
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Force an immediate drift recalibration (e.g. triggered by a watch face tap). */
    @Synchronized
    fun forceCalibrate() {
        motionCalibrator.recalibrate()
        Log.i(tag, "[Calibrate] Forced — driftOffset=${motionCalibrator.driftOffset}")
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        stop()
        try {
            biasAdapter.persist()
            tflite?.close()
            tflite = null
            channel?.close()
            channel = null
            afd?.close()
            afd = null
            inferenceDispatcher.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing resources: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Synchronized
    private fun inferBitrate(motion: Float, rttMs: Long): Float {
        if (isClosed) return 0f
        val interpreter = tflite ?: return 0f

        val input = arrayOf(
            floatArrayOf(
                DEFAULT_BATTERY_NORM,
                if (motion > MOTION_THRESHOLD) 1f else 0f,
                (rttMs / 500f).coerceIn(0f, 1f),
                DEFAULT_THERMAL_NORM
            )
        )
        val numOut = modelOutputClasses.takeIf { it > 0 } ?: DEFAULT_MODEL_OUTPUT_CLASSES
        val output = Array(1) { FloatArray(numOut) }

        return try {
            interpreter.run(input, output)
            // ── Fix 2: apply EMA bias correction before class selection ──────
            val adjusted = biasAdapter.adjust(output[0])
            biasAdapter.observe(adjusted)
            bitrateFromModelOutput(adjusted)
        } catch (e: Exception) {
            Log.e(tag, "Inference error: ${e.message}")
            0f
        }
    }

    private fun bitrateFromModelOutput(probs: FloatArray): Float {
        val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: return 0f
        val bitrate = when {
            probs.size == 3 && maxIdx == 2 -> StreamProtocol.WEAR_BPS_ECO.toFloat()
            probs.size == 3 && maxIdx == 1 -> (StreamProtocol.WEAR_BPS_FULL * 0.75f)
            probs.size == 3               -> StreamProtocol.WEAR_BPS_FULL.toFloat()
            maxIdx == 3                   -> StreamProtocol.WEAR_BPS_ECO.toFloat()
            maxIdx == 1                   -> (StreamProtocol.WEAR_BPS_FULL * 0.75f)
            maxIdx == 2                   -> StreamProtocol.WEAR_BPS_FULL.toFloat()
            else                          -> 0f
        }

        return if (bitrate <= 0f) {
            0f
        } else {
            bitrate.coerceIn(StreamProtocol.WEAR_BPS_ECO.toFloat(), StreamProtocol.WEAR_BPS_FULL.toFloat())
        }
    }

    // =========================================================================
    // Fix 2 — ClassBiasAdapter (Local EMA personalisation)
    // =========================================================================
    /**
     * Lightweight per-user bias correction using Exponential Moving Averages.
     *
     * Maintains an EMA of observed class probabilities. If the model consistently
     * over-predicts a class relative to the running average, the correction factor
     * pulls the output back towards the user's actual distribution.
     *
     * All state is stored in [SharedPreferences] — no model weights leave the device.
     * Complexity: O(numClasses) per inference call.
     */
    private inner class ClassBiasAdapter(
        numClasses: Int,
        private val prefs: SharedPreferences
    ) {
        // Smoothing factor: 0.05 = slow adapt (stable), 0.20 = fast adapt (reactive)
        private val alpha = 0.05f
        private val resetThreshold = 0.40f   // Reset if accumulated error > 40 %
        private val tag = "BiasAdapter"

        private var emaProbs: FloatArray = loadOrDefault(numClasses)

        /** Restore persisted EMA or start uniform. */
        private fun loadOrDefault(n: Int): FloatArray {
            val stored = prefs.getString(PREFS_BIAS_KEY, null)
            return if (stored != null) {
                try {
                    stored.split(",").map { it.toFloat() }.toFloatArray()
                        .takeIf { it.size == n } ?: FloatArray(n) { 1f / n }
                } catch (_: Exception) { FloatArray(n) { 1f / n } }
            } else {
                FloatArray(n) { 1f / n }
            }
        }

        fun resize(newSize: Int) {
            emaProbs = FloatArray(newSize) { 1f / newSize }
        }

        /**
         * Returns a bias-corrected probability vector.
         * Applies per-class multiplicative correction then re-normalises.
         */
        fun adjust(probs: FloatArray): FloatArray {
            val n = minOf(probs.size, emaProbs.size)
            val corrected = FloatArray(probs.size)
            var sum = 0f
            for (i in 0 until n) {
                // Suppress over-predicted classes; boost under-predicted ones
                val correction = if (emaProbs[i] > 0f) (1f / emaProbs[i]).coerceIn(0.5f, 2.0f) else 1f
                corrected[i] = probs[i] * correction
                sum += corrected[i]
            }
            if (sum <= 0f) return probs
            // Re-normalise to valid probability simplex
            return FloatArray(probs.size) { corrected[it] / sum }
        }

        /** Update EMA with latest adjusted output vector. */
        fun observe(probs: FloatArray) {
            val n = minOf(probs.size, emaProbs.size)
            var totalError = 0f
            for (i in 0 until n) {
                totalError += abs(probs[i] - emaProbs[i])
                emaProbs[i] = alpha * probs[i] + (1f - alpha) * emaProbs[i]
            }
            // If accumulated error is very high, the user's pattern changed — reset
            if (totalError > resetThreshold) {
                Log.i(tag, "Pattern shift detected (error=${"%.2f".format(totalError)}), resetting EMA")
                val uniform = 1f / n
                emaProbs = FloatArray(emaProbs.size) { uniform }
            }
        }

        /** Persist EMA to SharedPreferences for survival across restarts. */
        fun persist() {
            prefs.edit().putString(PREFS_BIAS_KEY, emaProbs.joinToString(",")).apply()
        }
    }

    // =========================================================================
    // Fix 3 — MotionCalibrator (Hourly sensor drift correction)
    // =========================================================================
    /**
     * Rolling-window drift corrector for wrist motion sensor readings.
     *
     * Maintains a circular buffer of the last [WINDOW_SIZE] raw motion samples
     * (1 sample/second ≈ 1 minute of history). Every [recalibrate] call computes
     * the mean of the buffer as the current drift offset and persists it to
     * [SharedPreferences].
     *
     * Usage:
     *   feed(rawValue) every second
     *   recalibrate() every hour (or on demand)
     *   correct(raw) → calibrated value before inference
     */
    private inner class MotionCalibrator(private val prefs: SharedPreferences) {
        private val tag = "MotionCalibrator"
        private val buffer = FloatArray(WINDOW_SIZE)
        private var head = 0
        private var count = 0

        var driftOffset: Float = prefs.getFloat(PREFS_DRIFT_KEY, 0f)
            private set

        /** Add one raw motion sample to the rolling window. */
        fun feed(raw: Float) {
            buffer[head % WINDOW_SIZE] = raw
            head++
            if (count < WINDOW_SIZE) count++
        }

        /**
         * Compute drift offset from the current window and persist.
         * Called every [CALIBRATION_INTERVAL_SEC] seconds by the inference loop.
         */
        fun recalibrate() {
            if (count == 0) return
            val n = minOf(count, WINDOW_SIZE)
            val mean = buffer.take(n).sum() / n
            driftOffset = mean
            prefs.edit().putFloat(PREFS_DRIFT_KEY, driftOffset).apply()
            Log.i(tag, "Recalibrated: window=$n samples, driftOffset=$driftOffset")
        }

        /** Return drift-corrected motion value (clamped to ≥ 0). */
        fun correct(raw: Float): Float = (raw - driftOffset).coerceAtLeast(0f)
    }

    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val DEFAULT_MODEL_OUTPUT_CLASSES = 4
        private const val DEFAULT_BATTERY_NORM         = 1f
        private const val DEFAULT_THERMAL_NORM         = 0f
        private const val MOTION_THRESHOLD             = 0.15f
        private const val CALIBRATION_INTERVAL_SEC     = 3600L  // 1 hour
        private const val WINDOW_SIZE                  = 60      // 60 s rolling window

        private const val PREFS_NAME      = "streamlink_ai"
        private const val PREFS_BIAS_KEY  = "ai_bias_v1"
        private const val PREFS_DRIFT_KEY = "motion_drift_v1"

        private val MODEL_ASSET_NAMES = listOf(
            "stream_predictor.tflite",
            "stream_predict_model.tflite",
            "predictive_model.tflite"
        )
    }
}
