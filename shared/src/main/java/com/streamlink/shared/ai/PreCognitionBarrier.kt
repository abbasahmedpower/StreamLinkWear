package com.streamlink.shared.ai

import java.util.concurrent.atomic.AtomicInteger

/**
 * Sequence gate for preemptive commands — blocks stale or low-confidence actions.
 */
class PreCognitionBarrier {
    private val lastExecutedSequence = AtomicInteger(0)

    fun evaluateTransaction(incomingSeq: Int, confidence: Float, minConfidence: Float = 0.85f): Boolean {
        if (incomingSeq <= lastExecutedSequence.get()) return false
        if (confidence < minConfidence) return false
        lastExecutedSequence.set(incomingSeq)
        return true
    }

    fun reset() {
        lastExecutedSequence.set(0)
    }
}
