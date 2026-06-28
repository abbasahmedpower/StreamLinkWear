package com.streamlink.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CircuitBreakerTest {

    @Test
    fun `verify circuit breaker trips to OPEN state after max continuous failures`() {
        val circuitBreaker = CircuitBreaker(failureThreshold = 5, openDurationMs = 1000L)
        
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.currentState)
        
        repeat(5) { circuitBreaker.recordFailure() }
        
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.currentState, "Circuit breaker failed to trip to OPEN")
    }
}
