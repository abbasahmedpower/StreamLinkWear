package com.streamlink.wear.ai

import com.streamlink.wear.sensor.WristMotionSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

import com.streamlink.shared.StreamProtocol

/**
 * LocalPredictiveEngine — on-device TFLite inference for proactive stream adaptation.
 * Uses wrist motion + latency to predict the optimal StreamAction.
 */
@Singleton
class LocalPredictiveEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensor: WristMotionSensor,
    private val logger: AIEventLogger,
    private val socketClient: com.streamlink.shared.DirectSocketClient
) {
    private var job: Job? = null
    private var tflite: Interpreter? = null
    private val tag = "PredictiveEngine"
    
    // State to track last sent bitrate to avoid jitter
    private var currentBitrate = 0f

    init {
        try {
            val assetFileDescriptor = context.assets.openFd("predictive_model.tflite")
            if (assetFileDescriptor.declaredLength < 100) {
                Log.w(tag, "Model size is too small (${assetFileDescriptor.declaredLength} bytes), ignoring dummy model.")
                tflite = null
            } else {
                val fileInputStream = java.io.FileInputStream(assetFileDescriptor.fileDescriptor)
                val fileChannel = fileInputStream.channel
                val startOffset = assetFileDescriptor.startOffset
                val declaredLength = assetFileDescriptor.declaredLength
                val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
                tflite = Interpreter(buffer)
                Log.i(tag, "TFLite model loaded successfully")
            }
        } catch (e: Exception) {
            Log.w(tag, "Dummy/missing TFLite model: ${e.message}")
        }
    }

    fun start(
        scope: CoroutineScope,
        motionProvider: () -> Float,
        networkProvider: () -> Float
    ) {
        job = scope.launch {
            while (true) {
                delay(1_000L)
                val motion = motionProvider()
                val network = networkProvider()
                
                var recommendedBitrate = 0f
                if (tflite != null) {
                    try {
                        val input = arrayOf(floatArrayOf(motion, network))
                        val output = Array(1) { FloatArray(1) }
                        tflite?.run(input, output)
                        recommendedBitrate = output[0][0]
                        
                        // Sanity clamp based on StreamProtocol limits
                        val minBitrate = StreamProtocol.WEAR_BPS_ECO.toFloat()
                        val maxBitrate = StreamProtocol.WEAR_BPS_FULL.toFloat()
                        recommendedBitrate = recommendedBitrate.coerceIn(minBitrate, maxBitrate)
                        
                        // Apply 10% threshold to avoid excessive signaling
                        if (currentBitrate == 0f || Math.abs(recommendedBitrate - currentBitrate) / currentBitrate >= 0.1f) {
                            Log.i(tag, "AI recommends bitrate change: ${currentBitrate.toInt()} -> ${recommendedBitrate.toInt()} kbps")
                            currentBitrate = recommendedBitrate
                            socketClient.sendControl(StreamProtocol.CMD_SET_BITRATE, recommendedBitrate.toInt())
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Inference error: ${e.message}")
                    }
                }
                
                logger.log("inference_tick", mapOf(
                    "motionIntensity" to motion,
                    "rttMs" to network,
                    "recommendedBitrate" to recommendedBitrate
                ))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
