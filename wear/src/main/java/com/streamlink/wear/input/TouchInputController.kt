package com.streamlink.wear.input

import com.streamlink.shared.TouchEvent
import com.streamlink.shared.TouchPhase
import com.streamlink.shared.ai.DigitalTwinEngine
import com.streamlink.shared.ai.KinematicPredictionEngine
import com.streamlink.shared.ai.PreCognitionEngine
import com.streamlink.shared.network.NetworkFallbackOrchestrator
import java.util.concurrent.atomic.AtomicInteger

/**
 * Maps Compose pointer IDs to 0-9 slots, runs kinematic + twin prediction on wear,
 * and schedules TouchEvents عبر NetworkFallbackOrchestrator (TCP محلي أولاً،
 * WebRTC لما يبقى متاح — بدل الاتصال المباشر بـDirectSocketClient).
 */
class TouchInputController(
    private val fallbackOrchestrator: NetworkFallbackOrchestrator
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

        fallbackOrchestrator.dispatchTouch(
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

    /**
     * Synthesizes a vertical scroll event — called by ImuGestureDetector when
     * an air-gesture wrist-flick is detected. Dispatches a normalized UP/DOWN
     * touch swipe via the fallback orchestrator.
     *
     * @param down true = scroll down (swipe up gesture), false = scroll up
     */
    fun simulateScroll(down: Boolean) {
        val timeUs = System.nanoTime() / 1000L
        val centerX = 0.5f
        val startY  = if (down) 0.3f else 0.7f
        val endY    = if (down) 0.7f else 0.3f

        // Synthesize a quick swipe: DOWN → MOVE → UP
        processEvent(pointerId = Long.MAX_VALUE, phase = TouchPhase.DOWN,   x = centerX, y = startY, timestampUs = timeUs)
        processEvent(pointerId = Long.MAX_VALUE, phase = TouchPhase.MOVE,   x = centerX, y = (startY + endY) / 2f, timestampUs = timeUs + 8_000L)
        processEvent(pointerId = Long.MAX_VALUE, phase = TouchPhase.UP,     x = centerX, y = endY,    timestampUs = timeUs + 16_000L)
    }
}

