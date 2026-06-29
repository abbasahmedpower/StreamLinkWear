package com.streamlink.shared.ui

import kotlin.math.PI

/**
 * Second-order dynamics reconciler — absorbs misprediction shocks without naive linear lerp snap.
 * Based on spring-damper response (frequency + damping).
 */
class SecondOrderReconciliationEngine(
    fFrequency: Float,
    zDamping: Float,
    rInitialResponse: Float
) {
    private var xp = 0f
    private var y = 0f
    private var yd = 0f

    private val k1: Float
    private val k2: Float
    private val k3: Float

    init {
        val w = 2f * PI.toFloat() * fFrequency
        k1 = zDamping / w
        k2 = 1f / (w * w)
        k3 = rInitialResponse * zDamping / w
    }

    fun reset(value: Float = 0f) {
        xp = value
        y = value
        yd = 0f
    }

    fun reconcile(targetRealValue: Float, deltaTimeSec: Float): Float {
        if (deltaTimeSec <= 0f) return y

        val x = targetRealValue
        val xd = (x - xp) / deltaTimeSec
        xp = x

        val k2Stable = maxOf(
            k2,
            deltaTimeSec * deltaTimeSec / 2f + deltaTimeSec * k1 / 2f,
            deltaTimeSec * k1
        )
        y += deltaTimeSec * yd
        yd += deltaTimeSec * (x + k3 * xd - y - k1 * yd) / k2Stable

        return y
    }
}
