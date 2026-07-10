package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * ConnectionManager — single authority for connection lifecycle & auto-reconnect.
 *
 * Design goals:
 *   1. Exponential Backoff: delays grow as 2^attempt seconds, capped at [MAX_DELAY_MS].
 *   2. Jitter: randomised ±20% to avoid thundering-herd on simultaneous reconnects.
 *   3. State Machine: all reconnect decisions are driven by [GlobalStreamState],
 *      avoiding "connected Boolean" anti-pattern.
 *   4. Max Retries: gives up after [MAX_RETRIES] consecutive failures → FAILED state.
 *   5. Reset: calling [reset] cancels any pending reconnect and returns to IDLE.
 *
 * Usage:
 *   1. Inject ConnectionManager via Hilt (Singleton).
 *   2. Call [watchState] inside your ViewModel/Orchestrator to start monitoring.
 *   3. Provide a [reconnectAction] suspend lambda that performs the actual reconnect.
 *
 *   Example:
 *     connectionManager.watchState(scope) {
 *         socketServer.start()
 *         GlobalStreamState.transition(GlobalStreamState.State.CONNECTING)
 *     }
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val scope: CoroutineScope
) {
    private val tag = "ConnectionManager"

    companion object {
        /** Base delay in milliseconds for the first retry attempt. */
        private const val BASE_DELAY_MS = 1_000L

        /** Maximum delay cap in milliseconds (30 seconds). */
        private const val MAX_DELAY_MS = 30_000L

        /** Maximum consecutive retries before transitioning to FAILED. */
        private const val MAX_RETRIES = 7

        /** Jitter factor: ±20% randomisation applied to each delay. */
        private const val JITTER_FACTOR = 0.2
    }

    private val attemptCount = AtomicInteger(0)
    private var reconnectJob: Job? = null

    /**
     * Begins monitoring [GlobalStreamState] and triggers [reconnectAction] when
     * a FAILED or DEGRADED state is observed and retries haven't been exhausted.
     *
     * Call once per session from your Orchestrator or ViewModel [init] block.
     */
    fun watchState(
        externalScope: CoroutineScope = scope,
        reconnectAction: suspend () -> Unit
    ) {
        externalScope.launch {
            GlobalStreamState.snapshot.collect { snapshot ->
                when (snapshot.state) {
                    GlobalStreamState.State.FAILED,
                    GlobalStreamState.State.STOPPED -> {
                        // Only auto-reconnect from FAILED — STOPPED is user-initiated.
                        if (snapshot.state == GlobalStreamState.State.FAILED) {
                            scheduleReconnect(externalScope, reconnectAction)
                        }
                    }
                    GlobalStreamState.State.STREAMING -> {
                        // Successful connection — reset attempt counter.
                        if (attemptCount.get() > 0) {
                            Log.i(tag, "✅ Connected — resetting retry counter")
                            attemptCount.set(0)
                        }
                    }
                    else -> { /* IDLE, CONNECTING, STREAM_STARTING, DEGRADED, RECOVERING — no action */ }
                }
            }
        }
    }

    /**
     * Schedules a reconnect attempt with exponential backoff and jitter.
     * Respects [MAX_RETRIES] before transitioning to [GlobalStreamState.State.FAILED].
     */
    private fun scheduleReconnect(
        externalScope: CoroutineScope,
        reconnectAction: suspend () -> Unit
    ) {
        val attempt = attemptCount.getAndIncrement()

        if (attempt >= MAX_RETRIES) {
            Log.e(tag, "❌ Max retries ($MAX_RETRIES) reached — giving up")
            externalScope.launch {
                GlobalStreamState.transition(GlobalStreamState.State.FAILED) {
                    copy(errorMessage = "Max reconnect attempts reached. Please restart.")
                }
            }
            return
        }

        val rawDelay = min(
            BASE_DELAY_MS * (2.0.pow(attempt.toDouble())).toLong(),
            MAX_DELAY_MS
        )
        val jitter = (rawDelay * JITTER_FACTOR * (Math.random() * 2 - 1)).toLong()
        val finalDelay = (rawDelay + jitter).coerceAtLeast(BASE_DELAY_MS)

        Log.i(tag, "🔄 Reconnect attempt ${attempt + 1}/$MAX_RETRIES in ${finalDelay}ms")

        reconnectJob?.cancel()
        reconnectJob = externalScope.launch {
            delay(finalDelay)
            try {
                GlobalStreamState.transition(GlobalStreamState.State.CONNECTING)
                reconnectAction()
            } catch (e: Exception) {
                Log.e(tag, "Reconnect attempt failed: ${e.message}")
                GlobalStreamState.transition(GlobalStreamState.State.FAILED) {
                    copy(errorMessage = "Reconnect failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Cancels any pending reconnect job and resets the attempt counter.
     * Call this when the user manually stops the stream or logs out.
     */
    fun reset() {
        reconnectJob?.cancel()
        reconnectJob = null
        attemptCount.set(0)
        Log.i(tag, "ConnectionManager reset — attempts cleared")
    }

    /**
     * Returns the current number of reconnect attempts made since the last reset.
     * Useful for surfacing retry status in the UI.
     */
    val currentAttempt: Int get() = attemptCount.get()
}
