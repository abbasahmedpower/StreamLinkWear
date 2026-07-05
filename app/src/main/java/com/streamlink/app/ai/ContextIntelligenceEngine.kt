package com.streamlink.app.ai

import android.content.Context
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
import java.util.concurrent.Executors

/**
 * Predictive intelligence layer. It uses a TFLite model when present and falls
 * back to the deterministic DecisionEngine when the model is missing or invalid.
 */
class ContextIntelligenceEngine(
    private val context: Context,
    private val decisionEngine: DecisionEngine = DecisionEngine(),
    private val scope: CoroutineScope
) {
    private val tag = "Intelligence"

    private var tflite: Interpreter? = null
    private var modelOutputClasses: Int = 0

    init {
        for (assetName in MODEL_ASSET_NAMES) {
            try {
                val assetFileDescriptor = context.assets.openFd(assetName)
                if (assetFileDescriptor.declaredLength < 100) {
                    Log.w(tag, "Model $assetName is too small (${assetFileDescriptor.declaredLength} bytes), ignoring it")
                    continue
                }

                val fileInputStream = java.io.FileInputStream(assetFileDescriptor.fileDescriptor)
                val fileChannel = fileInputStream.channel
                val modelBuffer = fileChannel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.declaredLength
                )

                tflite = Interpreter(modelBuffer)
                modelOutputClasses = tflite?.getOutputTensor(0)?.shape()?.lastOrNull() ?: 0
                Log.i(tag, "TFLite model loaded successfully: $assetName outputs=$modelOutputClasses")
                break
            } catch (_: java.io.FileNotFoundException) {
                // Try the next supported filename.
            } catch (e: Exception) {
                Log.e(tag, "Failed to load TFLite model $assetName, trying fallback", e)
            }
        }

        if (tflite == null) {
            Log.i(tag, "No stream predictor model found; running in heuristic-only mode")
        }
    }

    private val intelligenceDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SL-Intelligence").also { t ->
            t.priority = Thread.NORM_PRIORITY - 1
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
        val aiAction = inferAction(metrics)

        val action = if (trendAnalyzer.predictDegradationIn500ms()) {
            Log.d(tag, "Pre-emptive quality reduction: trend detected")
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
                    Log.d(tag, "DEGRADED: rtt=${metrics.rttMs}ms loss=${metrics.packetLossRate}")
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
                    Log.d(tag, "STREAMING: rtt=${metrics.rttMs}ms recovered")
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

    private fun inferAction(metrics: StreamMetrics): StreamAction {
        val interpreter = tflite ?: return decisionEngine.decide(metrics)

        val inputs = arrayOf(
            floatArrayOf(
                metrics.batteryLevel.coerceIn(0, 100) / 100f,
                if (metrics.isUserMoving) 1f else 0f,
                (metrics.rttMs / 500f).coerceIn(0f, 1f),
                (metrics.thermalLevel / 10f).coerceIn(0f, 1f)
            )
        )
        val outputs = Array(1) {
            FloatArray(modelOutputClasses.takeIf { it > 0 } ?: DEFAULT_MODEL_OUTPUT_CLASSES)
        }

        return try {
            interpreter.run(inputs, outputs)
            actionFromModelOutput(outputs[0])
        } catch (e: Exception) {
            Log.w(tag, "TFLite inference failed; falling back to heuristic: ${e.message}")
            decisionEngine.decide(metrics)
        }
    }

    private fun actionFromModelOutput(probs: FloatArray): StreamAction {
        val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: return StreamAction.STABLE

        return if (probs.size == 3) {
            when (maxIdx) {
                0 -> StreamAction.PRELOAD
                1 -> StreamAction.REDUCE_QUALITY
                else -> StreamAction.DROP_FPS
            }
        } else {
            when (maxIdx) {
                0 -> StreamAction.IDLE
                1 -> StreamAction.PRELOAD
                2 -> StreamAction.INCREASE_QUALITY
                else -> StreamAction.REDUCE_QUALITY
            }
        }
    }

    companion object {
        private const val DEFAULT_MODEL_OUTPUT_CLASSES = 4
        private val MODEL_ASSET_NAMES = listOf(
            "stream_predictor.tflite",
            "stream_predict_model.tflite"
        )
    }
}
