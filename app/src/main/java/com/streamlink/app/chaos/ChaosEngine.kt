package com.streamlink.app.chaos

import android.util.Log

enum class ChaosScenario(
    val packetLossPercent: Float,
    val rttMs: Int,
    val cpuStress: String,
    val thermalLevel: String
) {
    MILD(2f, 20, "NORMAL", "NORMAL"),
    MODERATE(5f, 80, "HIGH", "NORMAL"),
    SEVERE(15f, 200, "HIGH", "HOT"),
    DISASTER(30f, 500, "THROTTLED", "HOT")
}

class ChaosEngine {
    private val tag = "ChaosEngine"
    
    @Volatile
    var activeScenario: ChaosScenario? = null
        private set

    fun injectScenario(scenario: ChaosScenario) {
        activeScenario = scenario
        Log.w(tag, "🔥 Chaos Scenario INJECTED: ${scenario.name} (Loss: ${scenario.packetLossPercent}%, RTT: ${scenario.rttMs}ms, CPU: ${scenario.cpuStress}, Thermal: ${scenario.thermalLevel})")
    }

    fun stopScenario() {
        activeScenario = null
        Log.i(tag, "Chaos scenario stopped. Restoring system stability.")
    }

    /**
     * Intercepts network decisions and applies simulated losses.
     */
    fun shouldDropPacket(): Boolean {
        val scenario = activeScenario ?: return false
        val randomVal = (0..100).random().toFloat()
        return randomVal < scenario.packetLossPercent
    }

    /**
     * Intercepts RTT values.
     */
    fun getSimulatedRtt(realRttMs: Int): Int {
        val scenario = activeScenario ?: return realRttMs
        return scenario.rttMs
    }
}
