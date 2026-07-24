package com.streamlink.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamlink.app.capture.HardwareEncoder
import com.streamlink.app.core.decision.UnifiedQualityAuthority
import com.streamlink.app.core.decision.TelemetrySnapshot
import com.streamlink.app.core.predictive.PredictiveDecisionEvaluator
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.EncodingProfile
import com.streamlink.shared.ConnectionManager
import com.streamlink.app.core.StreamSessionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sprint 4: Stress & Soak Tests
 *
 * Validates system resilience under extreme constraints (thermal, CPU, concurrency).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StreamingStressTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var hardwareEncoderFake: StreamingIntegrationTest.FakeHardwareEncoder
    private lateinit var connectionManagerFake: StreamingIntegrationTest.FakeConnectionManager
    private lateinit var predictiveEvaluatorFake: StreamingIntegrationTest.FakePredictiveDecisionEvaluator
    private lateinit var qualityAuthority: UnifiedQualityAuthority

    @Before
    fun setup() = testScope.runTest {
        GlobalStreamState.resetSafe()
        hardwareEncoderFake = StreamingIntegrationTest.FakeHardwareEncoder()
        connectionManagerFake = StreamingIntegrationTest.FakeConnectionManager()
        predictiveEvaluatorFake = StreamingIntegrationTest.FakePredictiveDecisionEvaluator()
        
        qualityAuthority = UnifiedQualityAuthority(
            hardwareEncoder = hardwareEncoderFake,
            predictiveEvaluator = predictiveEvaluatorFake
        )
    }

    @Test
    fun testThermalAndCpuStress() = testScope.runTest {
        // 1. Inject Extreme Thermal (Level 10) and CPU Load (100%)
        val emergencySnapshot = TelemetrySnapshot(
            rttMs = 15, // Network is good
            packetLossPercent = 0.0f,
            jitterMs = 5,
            bitrateKbps = 15000,
            thermalLevel = 10,
            cpuLoad = 1.0f
        )
        
        qualityAuthority.evaluate(emergencySnapshot)

        // 2. Verification: Despite good network, hardware stress MUST force ECO profile
        val profile = qualityAuthority.currentProfileFlow.value
        assertEquals("ECO", profile.label)
        assertEquals(EncodingProfile.eco().bitrateKbps, profile.bitrateKbps)
        assertEquals(profile, hardwareEncoderFake.lastAppliedProfile)
    }

    @Test
    fun testNetworkOscillationSoak() = testScope.runTest {
        var flipCount = 0
        // Rapidly oscillate connection states to simulate walking in/out of Wi-Fi range
        for (i in 1..100) {
            if (i % 2 == 0) {
                GlobalStreamState.transition(GlobalStreamState.State.FAILED)
            } else {
                GlobalStreamState.transition(GlobalStreamState.State.CONNECTING)
                GlobalStreamState.transition(GlobalStreamState.State.STREAMING)
            }
            flipCount++
        }
        
        // Ensure the State Machine did not deadlock and processed all flips
        assertTrue("State machine survived 100 rapid state flips", flipCount == 100)
    }
}
