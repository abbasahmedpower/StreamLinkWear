package com.streamlink.shared

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

/**
 * RecoveryManager — Self-healing streaming recovery engine.
 *
 * Features:
 * - Exponential backoff with ±20% jitter (prevents thundering herd)
 * - Max 10 attempts per session
 * - Thermal-aware delay scaling
 * - Distinct recovery paths: ICE restart / codec reset / full reconnect
 * - Observable recovery state via StateFlow
 */
class RecoveryManager(
    private val scope: CoroutineScope,
    private val onIceRestart: suspend () -> Boolean    = { false },
    private val onCodecReset: suspend () -> Boolean    = { false },
    private val onFullReconnect: suspend () -> Boolean = { false },
    private val onGiveUp: () -> Unit                   = {}
) {
    enum class Strategy { ICE_RESTART, CODEC_RESET, FULL_RECONNECT, GIVE_UP }
    enum class RecoveryState { IDLE, RECOVERING, SUCCEEDED, FAILED }

    private val tag = "RecoveryManager"

    private val attemptCount = AtomicInteger(0)
    private val totalRecoveries = AtomicInteger(0)
    private val lastRecoveryMs = AtomicLong(0L)

    @Volatile var state: RecoveryState = RecoveryState.IDLE
        private set

    @Volatile var thermalMultiplier: Float = 1.0f  // 1.0 = normal, 2.0 = thermal slow

    companion object {
        const val BASE_DELAY_MS   = 500L
        const val MAX_DELAY_MS    = 30_000L
        const val MAX_ATTEMPTS    = 10
        const val MIN_INTERVAL_MS = 2_000L  // Don't attempt again within 2s
    }

    private var recoveryJob: Job? = null

    /**
     * Trigger recovery after a detected failure.
     * @param cause A string label for logging ("ice_fail", "codec_error", etc.)
     */
    fun trigger(cause: String) {
        if (state == RecoveryState.RECOVERING) {
            Log.d(tag, "Recovery already in progress — ignoring trigger: $cause")
            return
        }
        // Debounce
        val now = System.currentTimeMillis()
        if (now - lastRecoveryMs.get() < MIN_INTERVAL_MS) {
            Log.d(tag, "Recovery debounced (last was ${now - lastRecoveryMs.get()}ms ago)")
            return
        }
        if (attemptCount.get() >= MAX_ATTEMPTS) {
            Log.e(tag, "Max recovery attempts reached — giving up")
            state = RecoveryState.FAILED
            onGiveUp()
            return
        }
        recoveryJob?.cancel()
        recoveryJob = scope.launch { recover(cause) }
    }

    private suspend fun recover(cause: String) {
        state = RecoveryState.RECOVERING
        lastRecoveryMs.set(System.currentTimeMillis())
        val attempt = attemptCount.incrementAndGet()

        Log.i(tag, "Recovery attempt $attempt/$MAX_ATTEMPTS — cause: $cause")

        val strategy = pickStrategy(attempt, cause)
        val delayMs  = nextDelay(attempt)

        Log.i(tag, "Strategy: $strategy | delay: ${delayMs}ms")

        delay(delayMs)

        if (!currentCoroutineContext().isActive) return

        val success = when (strategy) {
            Strategy.ICE_RESTART    -> runCatching { onIceRestart() }.getOrDefault(false)
            Strategy.CODEC_RESET    -> runCatching { onCodecReset() }.getOrDefault(false)
            Strategy.FULL_RECONNECT -> runCatching { onFullReconnect() }.getOrDefault(false)
            Strategy.GIVE_UP        -> {
                Log.e(tag, "Giving up after $attempt attempts")
                state = RecoveryState.FAILED
                onGiveUp()
                return
            }
        }

        if (success) {
            Log.i(tag, "Recovery #$attempt SUCCEEDED via $strategy")
            state = RecoveryState.SUCCEEDED
            totalRecoveries.incrementAndGet()
            attemptCount.set(0)  // Reset attempt counter on success
        } else {
            Log.w(tag, "Recovery #$attempt FAILED via $strategy")
            state = RecoveryState.IDLE
            // Will be retried on next trigger
        }
    }

    /**
     * Pick recovery strategy based on attempt number and failure cause.
     * Escalates from light (ICE restart) → medium (codec reset) → heavy (full reconnect).
     */
    private fun pickStrategy(attempt: Int, cause: String): Strategy {
        if (attempt >= MAX_ATTEMPTS) return Strategy.GIVE_UP
        return when {
            cause.contains("ice", ignoreCase = true) && attempt <= 3 -> Strategy.ICE_RESTART
            cause.contains("codec", ignoreCase = true) && attempt <= 3 -> Strategy.CODEC_RESET
            attempt <= 3  -> Strategy.ICE_RESTART
            attempt <= 6  -> Strategy.CODEC_RESET
            attempt <= 9  -> Strategy.FULL_RECONNECT
            else          -> Strategy.GIVE_UP
        }
    }

    /**
     * Exponential backoff with ±20% jitter.
     * Scales by thermalMultiplier when device is hot.
     *
     * Delays: 500, 1000, 2000, 4000, 8000, 16000, 30000ms (capped)
     */
    private fun nextDelay(attempt: Int): Long {
        val base = BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(6))
        val capped = min(base, MAX_DELAY_MS)
        val jitter = (capped * 0.2 * (Random.nextDouble() * 2.0 - 1.0)).toLong()
        val thermal = (capped * thermalMultiplier).toLong()
        return (thermal + jitter).coerceIn(BASE_DELAY_MS, MAX_DELAY_MS * 2)
    }

    fun reset() {
        recoveryJob?.cancel()
        attemptCount.set(0)
        state = RecoveryState.IDLE
    }

    fun cancel() {
        recoveryJob?.cancel()
    }

    // Stats
    val currentAttempt: Int get() = attemptCount.get()
    val successCount: Int get() = totalRecoveries.get()
}
