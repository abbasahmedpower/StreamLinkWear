package com.streamlink.shared

import kotlinx.coroutines.GlobalScope
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecoveryManagerTest {

    @Test
    fun `verify exponential backoff with jitter falls within deterministic bounds`() {
        val recoveryManager = RecoveryManager(GlobalScope)

        val delayAttempt1 = recoveryManager.nextDelay(attempt = 1)
        val delayAttempt2 = recoveryManager.nextDelay(attempt = 2)
        val delayAttempt3 = recoveryManager.nextDelay(attempt = 3)

        // Base delays are 500, 1000, 2000. Jitter is +/- 20%
        assertTrue(delayAttempt1 in 400..600, "Attempt 1 out of bounds: $delayAttempt1")
        assertTrue(delayAttempt2 in 800..1200, "Attempt 2 out of bounds: $delayAttempt2")
        assertTrue(delayAttempt3 in 1600..2400, "Attempt 3 out of bounds: $delayAttempt3")
    }

    @Test
    fun `verify thermal-aware delays inject safety multiplier under high temps`() {
        val recoveryManager = RecoveryManager(GlobalScope)

        val normalDelay = recoveryManager.nextDelay(attempt = 1)
        
        recoveryManager.thermalMultiplier = 2.0f
        val thermalDelay = recoveryManager.nextDelay(attempt = 1)

        assertTrue(thermalDelay > normalDelay * 1.5, "Thermal logic failed to back off aggressively!")
    }

    @Test
    fun `verify automatic cycling through 3 core recovery strategies sequentially`() {
        val recoveryManager = RecoveryManager(GlobalScope)

        assertEquals(RecoveryManager.Strategy.ICE_RESTART, recoveryManager.pickStrategy(1, "timeout"))
        assertEquals(RecoveryManager.Strategy.CODEC_RESET, recoveryManager.pickStrategy(5, "timeout"))
        assertEquals(RecoveryManager.Strategy.FULL_RECONNECT, recoveryManager.pickStrategy(8, "timeout"))
    }
}
