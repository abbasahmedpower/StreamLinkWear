package com.streamlink.wear.ai

import android.content.Context
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
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * On-device TFLite inference for proactive stream adaptation on the watch.
 *
 * It consumes the same stream_predictor.tflite contract as the phone-side
 * ContextIntelligenceEngine. If the model is absent, it still logs Room events
 * so ai_training/export_from_room.py can build a real dataset later.
 */
@Singleton
class LocalPredictiveEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensor: WristMotionSensor,
    private val logger: AIEventLogger,
    private val socketClient: com.streamlink.shared.DirectSocketClient
) {
    private val tag = "PredictiveEngine"

    private var job: Job? = null
    private var tflite: Interpreter? = null
    private var modelOutputClasses: Int = 0
    private var currentBitrate = 0f

    init {
        for (assetName in MODEL_ASSET_NAMES) {
            try {
                val afd = context.assets.openFd(assetName)
                if (afd.declaredLength < 100) {
                    Log.w(tag, "Model $assetName is too small (${afd.declaredLength} bytes), ignoring it")
                    continue
                }

                val channel = java.io.FileInputStream(afd.fileDescriptor).channel
                val buffer = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
                tflite = Interpreter(buffer)
                modelOutputClasses = tflite?.getOutputTensor(0)?.shape()?.lastOrNull() ?: 0
                Log.i(tag, "TFLite model loaded successfully: $assetName outputs=$modelOutputClasses")
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
        job = scope.launch {
            while (isActive) {
                delay(1_000L)

                val motion = motionProvider()
                val rttMs = networkProvider().toLong().coerceAtLeast(0L)
                val recommendedBitrate = inferBitrate(motion, rttMs)

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
                        "motionIntensity" to motion,
                        "rttMs" to rttMs,
                        "recommendedBitrate" to recommendedBitrate
                    )
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun inferBitrate(motion: Float, rttMs: Long): Float {
        val interpreter = tflite ?: return 0f

        val input = arrayOf(
            floatArrayOf(
                DEFAULT_BATTERY_NORM,
                if (motion > MOTION_THRESHOLD) 1f else 0f,
                (rttMs / 500f).coerceIn(0f, 1f),
                DEFAULT_THERMAL_NORM
            )
        )
        val output = Array(1) {
            FloatArray(modelOutputClasses.takeIf { it > 0 } ?: DEFAULT_MODEL_OUTPUT_CLASSES)
        }

        return try {
            interpreter.run(input, output)
            bitrateFromModelOutput(output[0])
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
            probs.size == 3 -> StreamProtocol.WEAR_BPS_FULL.toFloat()
            maxIdx == 3 -> StreamProtocol.WEAR_BPS_ECO.toFloat()
            maxIdx == 1 -> (StreamProtocol.WEAR_BPS_FULL * 0.75f)
            maxIdx == 2 -> StreamProtocol.WEAR_BPS_FULL.toFloat()
            else -> 0f
        }

        return if (bitrate <= 0f) {
            0f
        } else {
            bitrate.coerceIn(StreamProtocol.WEAR_BPS_ECO.toFloat(), StreamProtocol.WEAR_BPS_FULL.toFloat())
        }
    }

    companion object {
        private const val DEFAULT_MODEL_OUTPUT_CLASSES = 4
        private const val DEFAULT_BATTERY_NORM = 1f
        private const val DEFAULT_THERMAL_NORM = 0f
        private const val MOTION_THRESHOLD = 0.15f
        private val MODEL_ASSET_NAMES = listOf(
            "stream_predictor.tflite",
            "stream_predict_model.tflite",
            "predictive_model.tflite"
        )
    }
}
