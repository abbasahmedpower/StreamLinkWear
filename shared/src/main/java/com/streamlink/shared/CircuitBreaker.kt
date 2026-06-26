package com.streamlink.shared

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Production Circuit Breaker with HALF_OPEN state.
 *
 * State machine:
 * CLOSED → OPEN (on failures ≥ threshold)
 * OPEN → HALF_OPEN (after openDurationMs)
 * HALF_OPEN → CLOSED (on success)
 * HALF_OPEN → OPEN (on failure)
 */
class CircuitBreaker(
    private val failureThreshold: Int = StreamProtocol.CB_FAILURE_THRESHOLD,
    private val openDurationMs: Long = StreamProtocol.CB_OPEN_DURATION_MS
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val state = AtomicReference(State.CLOSED)
    private val failures = AtomicInteger(0)
    private val openedAt = AtomicLong(0L)
    private val halfOpenAttempts = AtomicInteger(0)

    fun isAllowed(): Boolean {
        return when (state.get()) {
            State.CLOSED -> true
            State.OPEN -> {
                val elapsed = SystemClock.elapsedRealtime() - openedAt.get()
                if (elapsed >= openDurationMs) {
                    // Transition to HALF_OPEN
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        halfOpenAttempts.set(0)
                    }
                    true  // Allow one probe request
                } else {
                    false
                }
            }
            State.HALF_OPEN -> halfOpenAttempts.getAndIncrement() == 0  // Only one probe
            null -> true
        }
    }

    fun recordFailure() {
        when (state.get()) {
            State.CLOSED -> {
                val f = failures.incrementAndGet()
                if (f >= failureThreshold) {
                    state.set(State.OPEN)
                    openedAt.set(SystemClock.elapsedRealtime())
                    failures.set(0)
                }
            }
            State.HALF_OPEN -> {
                // Probe failed — back to OPEN
                state.set(State.OPEN)
                openedAt.set(SystemClock.elapsedRealtime())
            }
            else -> {}
        }
    }

    fun recordSuccess() {
        state.set(State.CLOSED)
        failures.set(0)
    }

    fun reset() {
        state.set(State.CLOSED)
        failures.set(0)
    }

    val currentState: State get() = state.get()
    val isOpen: Boolean get() = state.get() == State.OPEN
}
