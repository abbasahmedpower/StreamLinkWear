package com.streamlink.shared.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * PairingAttemptThrottle — prevents brute-force attacks on the 6-digit PIN.
 * Uses exponential backoff after failures and completely locks out after N attempts.
 *
 * ✅ 4.5: Bounded memory — the internal state map is capped at [maxTrackedAddresses] entries.
 *
 * Two-tier sweep strategy:
 *  Tier 1 — IMMEDIATE (Hard Limit Trigger): if the map exceeds [maxTrackedAddresses] after
 *            any recordFailure(), a sweep runs immediately regardless of [sweepIntervalMs].
 *            Defends against burst attacks (e.g., 10,000 IPs in 5 seconds via IPv6).
 *  Tier 2 — OPPORTUNISTIC (Time-Based): runs at most once per [sweepIntervalMs] to clean up
 *            stale entries even if the hard limit hasn't been reached.
 *
 * Thread-safety: ConcurrentHashMap.compute() for atomic state updates.
 * AtomicLong + CAS for sweep guard (prevents thundering herd of concurrent sweeps).
 */
class PairingAttemptThrottle(
    private val maxFailuresBeforeLockout: Int = 5,
    private val lockoutMs: Long = 5 * 60_000L,     // 5 minutes lockout
    private val baseBackoffMs: Long = 1_000L,
    // ✅ 4.5: Hard cap — prevents unbounded memory growth (DoS vector)
    private val maxTrackedAddresses: Int = 2_000,
    // ✅ 4.5: Opportunistic sweep interval (Tier 2)
    private val sweepIntervalMs: Long = 60_000L
) {
    private data class AttemptState(
        val failures: Int = 0,
        val lastFailureAt: Long = 0L,
        val lockedUntil: Long = 0L
    )

    private val state = ConcurrentHashMap<String, AttemptState>()
    private val lastSweep = AtomicLong(0L)

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
        // ✅ 4.5 Nano: Tier 1 — immediate sweep if burst attack pushed us over the hard cap.
        // This fires synchronously in the attacker's own thread, making the attack self-limiting.
        if (state.size > maxTrackedAddresses) {
            doSweep(now, force = true)
        } else {
            // Tier 2 — opportunistic time-based cleanup
            tryScheduledSweep(now)
        }
    }

    fun recordSuccess(remoteAddress: String) {
        state.remove(remoteAddress)
    }

    /**
     * Tier 2: Scheduled sweep — runs at most once per [sweepIntervalMs].
     * CAS guard ensures only one thread runs the sweep even under concurrent recordFailure() calls.
     */
    private fun tryScheduledSweep(now: Long) {
        val last = lastSweep.get()
        if (now - last < sweepIntervalMs) return
        if (!lastSweep.compareAndSet(last, now)) return // another thread won the CAS
        doSweep(now, force = false)
    }

    /**
     * Core sweep logic — two eviction phases:
     *  Phase 1: Remove genuinely stale entries (lockout expired + last failure old enough).
     *  Phase 2: Hard cap enforcement — evict oldest-first if still over [maxTrackedAddresses].
     *
     * @param force if true, skip the [sweepIntervalMs] guard (used by Tier 1 hard-limit trigger).
     */
    private fun doSweep(now: Long, force: Boolean) {
        if (force) {
            // Update lastSweep so Tier 2 doesn't double-sweep immediately after
            lastSweep.set(now)
        }

        // Phase 1: Stale entry eviction
        val staleBefore = now - (lockoutMs * 2)
        state.entries.removeIf { entry ->
            entry.value.lastFailureAt < staleBefore && now >= entry.value.lockedUntil
        }

        // Phase 2: Hard cap — evict oldest entries if still over limit
        if (state.size > maxTrackedAddresses) {
            state.entries
                .sortedBy { it.value.lastFailureAt }
                .take(state.size - maxTrackedAddresses)
                .forEach { state.remove(it.key) }
        }
    }
}
