package com.streamlink.shared.ai

import com.streamlink.shared.TouchEvent
import com.streamlink.shared.ui.SecondOrderReconciliationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dual-reality fusion hub: predicted layer + real layer → shock-absorbed render output.
 * Singleton observable from phone UI and orchestrator.
 */
object TouchPerceptionHub {
    private val kinematic = KinematicPredictionEngine()
    private val reconcilerX = SecondOrderReconciliationEngine(fFrequency = 5f, zDamping = 0.6f, rInitialResponse = 0f)
    private val reconcilerY = SecondOrderReconciliationEngine(fFrequency = 5f, zDamping = 0.6f, rInitialResponse = 0f)
    private val twinEngine = DigitalTwinEngine()
    private val preCognition = PreCognitionEngine(twinEngine)

    private val _renderState = MutableStateFlow(PerceptionRenderState())
    val renderState: StateFlow<PerceptionRenderState> = _renderState.asStateFlow()

    private var lastTimestampUs = 0L
    private var lastPredicted: PredictedTouch? = null
    private var isPressed = false

    fun onRealTouch(event: TouchEvent) {
        val deltaMs = if (lastTimestampUs > 0L) {
            ((event.timestampUs - lastTimestampUs) / 1000f).coerceIn(0.1f, 100f)
        } else {
            16f
        }
        lastTimestampUs = event.timestampUs

        isPressed = event.phase == com.streamlink.shared.TouchPhase.DOWN ||
            event.phase == com.streamlink.shared.TouchPhase.MOVE

        val predicted = kinematic.updateAndPredict(event.nx, event.ny, deltaMs, 16f)
        lastPredicted = predicted
        twinEngine.ingest(predicted, deltaMs)

        val intentPrediction = preCognition.predict(
            predicted,
            event.seq,
            isPointerDown = isPressed
        )

        val mispredicted = detectMisprediction(predicted, event.nx, event.ny)
        if (mispredicted) twinEngine.penalizeMisprediction() else twinEngine.rewardCorrectPrediction()

        _renderState.value = PerceptionRenderState(
            realX = event.nx,
            realY = event.ny,
            predictedX = predicted.predictedX,
            predictedY = predicted.predictedY,
            renderX = predicted.predictedX,
            renderY = predicted.predictedY,
            isPressed = isPressed,
            isMisprediction = mispredicted,
            intent = intentPrediction.intent,
            confidence = intentPrediction.confidence,
            sequence = event.seq
        )
    }

    fun reconcileFrame(deltaTimeSec: Float) {
        val current = _renderState.value
        val targetX = if (current.isMisprediction) current.realX else current.predictedX
        val targetY = if (current.isMisprediction) current.realY else current.predictedY

        val fusedX = reconcilerX.reconcile(targetX, deltaTimeSec)
        val fusedY = reconcilerY.reconcile(targetY, deltaTimeSec)

        _renderState.value = current.copy(renderX = fusedX, renderY = fusedY)
    }

    fun reset() {
        kinematic.reset()
        reconcilerX.reset()
        reconcilerY.reset()
        lastTimestampUs = 0L
        lastPredicted = null
        isPressed = false
        _renderState.value = PerceptionRenderState()
    }

    private fun detectMisprediction(predicted: PredictedTouch, realX: Float, realY: Float): Boolean {
        val dx = kotlin.math.abs(predicted.predictedX - realX)
        val dy = kotlin.math.abs(predicted.predictedY - realY)
        return dx > 0.04f || dy > 0.04f
    }
}

data class PerceptionRenderState(
    val realX: Float = 0.5f,
    val realY: Float = 0.5f,
    val predictedX: Float = 0.5f,
    val predictedY: Float = 0.5f,
    val renderX: Float = 0.5f,
    val renderY: Float = 0.5f,
    val isPressed: Boolean = false,
    val isMisprediction: Boolean = false,
    val intent: TouchIntent = TouchIntent.IDLE,
    val confidence: Float = 0f,
    val sequence: Int = 0
)
