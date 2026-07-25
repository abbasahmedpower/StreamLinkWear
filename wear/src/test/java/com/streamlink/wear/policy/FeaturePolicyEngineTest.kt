package com.streamlink.wear.policy

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase 1.5 — FeaturePolicyEngine Tests
 *
 * The watch must NEVER blindly apply phone preferences.
 * Every feature must satisfy ALL hardware policy conditions.
 */
class FeaturePolicyEngineTest {

    private lateinit var engine: FeaturePolicyEngine

    @BeforeEach
    fun setup() {
        engine = FeaturePolicyEngine()
        // Healthy baseline
        engine.isStreaming     = true
        engine.isScreenOn     = true
        engine.thermalLevel   = 3
        engine.batteryPercent = 80
    }

    @Test
    fun `IMU activates when all conditions pass and phone prefers it`() {
        engine.phonePrefImuGestures = true
        assertTrue(engine.imuGesturesActive.value)
    }

    @Test
    fun `IMU blocks when streaming is false`() {
        engine.phonePrefImuGestures = true
        engine.isStreaming = false
        assertFalse(engine.imuGesturesActive.value)
    }

    @Test
    fun `IMU blocks when screen is off`() {
        engine.phonePrefImuGestures = true
        engine.isScreenOn = false
        assertFalse(engine.imuGesturesActive.value)
    }

    @Test
    fun `IMU blocks when thermal is at warning level`() {
        engine.phonePrefImuGestures = true
        engine.thermalLevel = 7 // >= THERMAL_WARN
        assertFalse(engine.imuGesturesActive.value)
    }

    @Test
    fun `IMU blocks when battery is critically low`() {
        engine.phonePrefImuGestures = true
        engine.batteryPercent = 10 // <= BATTERY_MIN
        assertFalse(engine.imuGesturesActive.value)
    }

    @Test
    fun `DynamicFPS activates when all conditions pass`() {
        engine.phonePrefDynamicFps = true
        assertTrue(engine.dynamicFpsActive.value)
    }

    @Test
    fun `DynamicFPS deactivates when thermal spikes mid-stream`() {
        engine.phonePrefDynamicFps = true
        assertTrue(engine.dynamicFpsActive.value) // active

        engine.thermalLevel = 8 // spike!
        assertFalse(engine.dynamicFpsActive.value) // disabled by policy
    }

    @Test
    fun `phone disabling pref immediately deactivates feature`() {
        engine.phonePrefImuGestures = true
        assertTrue(engine.imuGesturesActive.value)

        engine.phonePrefImuGestures = false
        assertFalse(engine.imuGesturesActive.value)
    }
}
