package com.streamlink.shared.security

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * PairingAttemptThrottle — prevents brute-force attacks on the 6-digit PIN.
 * Uses exponential backoff after failures and completely locks out after N attempts.
 */
class PairingAttemptThrottle(
    private val maxFailuresBeforeLockout: Int = 5,
    private val lockoutMs: Long = 5 * 60_000L,   // 5 minutes lockout
    private val baseBackoffMs: Long = 1_000L
) {
    private data class AttemptState(
        val failures: Int = 0,
        val lastFailureAt: Long = 0L,
        val lockedUntil: Long = 0L
    )

    private val state = ConcurrentHashMap<String, AttemptState>()

    /** Call this before starting handshake. If it returns a non-null Long, reject immediately. */
    fun msUntilAllowed(remoteAddress: String): Long? {
        val now = System.currentTimeMillis()
        val s = state[remoteAddress] ?: return null
        if (now < s.lockedUntil) return s.lockedUntil - now
        val backoff = min(baseBackoffMs * (1L shl min(s.failures, 10)), 30_000L)
        val readyAt = s.lastFailureAt + backoff
        return if (now < readyAt) readyAt - now else null
    }

    fun recordFailure(remoteAddress: String) {
        val now = System.currentTimeMillis()
        state.compute(remoteAddress) { _, prev ->
            val failures = (prev?.failures ?: 0) + 1
            val lockedUntil = if (failures >= maxFailuresBeforeLockout) now + lockoutMs else 0L
            AttemptState(failures, now, lockedUntil)
        }
    }

    fun recordSuccess(remoteAddress: String) {
        state.remove(remoteAddress)
    }
}
