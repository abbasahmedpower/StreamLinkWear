package com.streamlink.app.ai

import android.util.Log
import com.streamlink.shared.DecisionEngine
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.StreamAction
import com.streamlink.shared.StreamMetrics
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.TrendAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import android.content.Context

/**
 * Predictive intelligence layer — evaluates context every 500ms and drives
 * GlobalStreamState before failures become visible to the user.
 *
 * Nano fix N4:
 * - Replaces withContext(Dispatchers.IO) inside the loop with a dedicated
 *   single-thread dispatcher allocated once.
 * - The dispatcher thread has NORM_PRIORITY-1 (background) so it doesn't
 *   compete with the encoder's URGENT_DISPLAY thread.
 * - TrendAnalyzer pre-empts degradation 500ms before threshold crossing.
 */
class ContextIntelligenceEngine(
    private val context: Context,
    private val decisionEngine: DecisionEngine = DecisionEngine(),
    private val scope: CoroutineScope
) {
    private val tag = "Intelligence"

    // ✅ TensorFlow Lite Integration
    private var tflite: Interpreter? = null

    init {
        try {
            val modelBuffer = loadModelFile(context, "stream_predictor.tflite")
            tflite = Interpreter(modelBuffer)
            Log.i(tag, "✅ TFLite model loaded successfully")
        } catch (e: Exception) {
            Log.e(tag, "⚠️ Failed to load TFLite model, falling back to heuristic engine", e)
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    // ✅ FIX N4: One dedicated thread — no context switch overhead per cycle
    private val intelligenceDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SL-Intelligence").also { t ->
            t.priority = Thread.NORM_PRIORITY - 1  // Background — yield to encoder
            t.isDaemon = true
        }
    }.asCoroutineDispatcher()

    private val trendAnalyzer = TrendAnalyzer(windowSize = 5)
    private var evaluationJob: Job? = null

    fun start(metricsProvider: suspend () -> StreamMetrics) {
        evaluationJob?.cancel()
        evaluationJob = scope.launch(intelligenceDispatcher) {
            Log.i(tag, "Intelligence engine started on dedicated thread")
            while (isActive) {
                try {
                    val metrics = metricsProvider()
                    trendAnalyzer.record(metrics.rttMs, metrics.packetLossRate)
                    evaluateAndApply(metrics)
                } catch (e: Exception) {
                    Log.w(tag, "Evaluation error: ${e.message}")
                }
                delay(500)
            }
        }
    }

    fun stop() {
        evaluationJob?.cancel()
        evaluationJob = null
        intelligenceDispatcher.close()
        Log.i(tag, "Intelligence engine stopped")
    }

    suspend fun evaluateAndApply(metrics: StreamMetrics) {
        // ✅ Real AI Inference (if model loaded)
        val aiAction = if (tflite != null) {
            val inputs = floatArrayOf(
                metrics.rttMs.toFloat(), 
                metrics.packetLossRate.toFloat(), 
                metrics.thermalLevel.toFloat(), 
                metrics.batteryLevel.toFloat()
            )
            val outputs = Array(1) { FloatArray(3) } // [PRELOAD, REDUCE, DROP]
            try {
                tflite?.run(inputs, outputs)
                val probs = outputs[0]
                val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 1
                when (maxIdx) {
                    0 -> StreamAction.PRELOAD
                    1 -> StreamAction.REDUCE_QUALITY
                    else -> StreamAction.DROP_FPS
                }
            } catch (e: Exception) {
                decisionEngine.decide(metrics)
            }
        } else {
            decisionEngine.decide(metrics)
        }

        // ✅ Trend-based prediction: fires 500ms before threshold crossing
        val action = if (trendAnalyzer.predictDegradationIn500ms()) {
            Log.d(tag, "Pre-emptive quality reduction — trend detected")
            StreamAction.REDUCE_QUALITY
        } else {
            aiAction
        }

        val current = GlobalStreamState.snapshot.value

        when (action) {
            StreamAction.PRELOAD -> {
                if (current.state == GlobalStreamState.State.IDLE) {
                    GlobalStreamState.transition(GlobalStreamState.State.PRELOADING) {
                        copy(
                            bitrateKbps = StreamProtocol.WEAR_BPS_ECO,
                            predictedAction = action
                        )
                    }
                }
            }

            StreamAction.REDUCE_QUALITY, StreamAction.DROP_FPS -> {
                if (current.state == GlobalStreamState.State.STREAMING) {
                    GlobalStreamState.transition(GlobalStreamState.State.DEGRADED) {
                        copy(
                            fps = decisionEngine.performanceCap(action),
                            bitrateKbps = (bitrateKbps * 0.75)
                                .toInt()
                                .coerceAtLeast(StreamProtocol.WEAR_BPS_ECO),
                            predictedAction = action
                        )
                    }
                    Log.d(tag, "→ DEGRADED: rtt=${metrics.rttMs}ms loss=${metrics.packetLossRate}")
                }
            }

            StreamAction.INCREASE_QUALITY -> {
                if (current.state == GlobalStreamState.State.DEGRADED &&
                    !trendAnalyzer.isRisingRapidly()
                ) {
                    GlobalStreamState.transition(GlobalStreamState.State.STREAMING) {
                        copy(
                            fps = StreamProtocol.WEAR_FPS_FULL,
                            predictedAction = action
                        )
                    }
                    Log.d(tag, "→ STREAMING: rtt=${metrics.rttMs}ms recovered")
                }
            }

            StreamAction.RECONNECT -> {
                if (current.state == GlobalStreamState.State.STREAMING) {
                    GlobalStreamState.transition(GlobalStreamState.State.RECOVERING) {
                        copy(predictedAction = action)
                    }
                }
            }

            StreamAction.PAUSE -> {
                if (current.state in setOf(
                        GlobalStreamState.State.STREAMING,
                        GlobalStreamState.State.DEGRADED
                    )
                ) {
                    GlobalStreamState.transition(GlobalStreamState.State.DEGRADED) {
                        copy(
                            fps = StreamProtocol.WEAR_FPS_ECO,
                            bitrateKbps = StreamProtocol.WEAR_BPS_ECO,
                            predictedAction = action
                        )
                    }
                }
            }

            StreamAction.STABLE, StreamAction.IDLE -> {
                GlobalStreamState.update { copy(predictedAction = action) }
            }
            else -> {}
        }
    }
}
