package com.streamlink.wear.input

import com.streamlink.shared.DirectSocketClient
import com.streamlink.shared.TouchEvent
import com.streamlink.shared.TouchPhase
import com.streamlink.shared.ai.DigitalTwinEngine
import com.streamlink.shared.ai.KinematicPredictionEngine
import com.streamlink.shared.ai.PreCognitionEngine
import java.util.concurrent.atomic.AtomicInteger

/**
 * Maps Compose pointer IDs to 0-9 slots, runs kinematic + twin prediction on wear,
 * and schedules TouchEvents to the DirectSocketClient.
 */
class TouchInputController(
    private val socketClient: DirectSocketClient
) {
    private val pointerSlots = IntArray(10) { -1 }
    private val seqGen = AtomicInteger(0)

    private val kinematicEngine = KinematicPredictionEngine()
    private val twinEngine = DigitalTwinEngine()
    private val preCognitionEngine = PreCognitionEngine(twinEngine)

    private var lastX = 0f
    private var lastY = 0f
    private var lastTimeUs = 0L

    fun processEvent(
        pointerId: Long,
        phase: TouchPhase,
        x: Float,
        y: Float,
        timestampUs: Long
    ) {
        val slot = getOrAllocateSlot(pointerId)
        if (slot == -1) return

        val deltaMs = if (lastTimeUs > 0L) {
            ((timestampUs - lastTimeUs) / 1000f).coerceIn(0.1f, 50f)
        } else {
            16f
        }

        val predicted = kinematicEngine.updateAndPredict(x, y, deltaMs, 16f)
        twinEngine.ingest(predicted, deltaMs)

        val seq = seqGen.incrementAndGet()
        val isPointerDown = phase == TouchPhase.DOWN || phase == TouchPhase.MOVE
        preCognitionEngine.predict(predicted, seq, isPointerDown)

        lastX = x
        lastY = y
        lastTimeUs = timestampUs

        socketClient.sendTouch(
            phase = phase,
            pointerId = slot,
            nx = predicted.predictedX,
            ny = predicted.predictedY,
            seq = seq,
            timestampUs = timestampUs
        )

        if (phase == TouchPhase.UP || phase == TouchPhase.CANCEL) {
            freeSlot(slot)
            kinematicEngine.reset()
        }
    }

    fun twinConfidence(): Float = twinEngine.twin().getConfidence()

    private fun getOrAllocateSlot(pointerId: Long): Int {
        val intId = (pointerId and 0x7FFFFFFF).toInt()

        for (i in pointerSlots.indices) {
            if (pointerSlots[i] == intId) return i
        }

        for (i in pointerSlots.indices) {
            if (pointerSlots[i] == -1) {
                pointerSlots[i] = intId
                return i
            }
        }
        return -1
    }

    private fun freeSlot(slot: Int) {
        if (slot in pointerSlots.indices) {
            pointerSlots[slot] = -1
        }
    }
}
