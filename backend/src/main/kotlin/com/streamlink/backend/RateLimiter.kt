package com.streamlink.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Token-Bucket Rate Limiter — per-key, thread-safe, lock-free.
 *
 * Each key (userId or client IP) gets its own bucket:
 *   - Starts full at [capacity] tokens.
 *   - Refills at [refillRatePerSecond] tokens/second (lazy, on-demand).
 *   - [tryConsume] returns false immediately when the bucket is empty.
 *
 * Stale buckets (no activity for [evictAfterMs]) are swept every minute
 * to prevent unbounded memory growth under heavy rotation of client IPs.
 *
 * Usage:
 *   val limiter = RateLimiter(capacity = 60, refillRatePerSecond = 1.0)
 *   if (!limiter.tryConsume(userId)) { /* reject */ }
 */
class RateLimiter(
    private val capacity: Double,
    private val refillRatePerSecond: Double,
    private val evictAfterMs: Long = 5 * 60_000L   // evict buckets idle > 5 minutes
) {
    private val log = LoggerFactory.getLogger(RateLimiter::class.java)

    /** Each bucket stores: [tokens as long-bits, lastRefillNanos, lastAccessMs]. */
    private inner class Bucket {
        // Tokens stored as raw IEEE-754 bits for atomic CAS on a LongField
        private val tokenBits = AtomicLong(java.lang.Double.doubleToRawLongBits(capacity))
        private val lastRefillNanos = AtomicLong(System.nanoTime())
        val lastAccessMs = AtomicLong(System.currentTimeMillis())

        fun tryConsume(tokens: Double = 1.0): Boolean {
            lastAccessMs.set(System.currentTimeMillis())
            refill()
            while (true) {
                val currentBits   = tokenBits.get()
                val current       = java.lang.Double.longBitsToDouble(currentBits)
                if (current < tokens) return false
                val newBits = java.lang.Double.doubleToRawLongBits(current - tokens)
                if (tokenBits.compareAndSet(currentBits, newBits)) return true
            }
        }

        private fun refill() {
            val now = System.nanoTime()
            val prevNanos = lastRefillNanos.get()
            val elapsedSec = (now - prevNanos) / 1_000_000_000.0
            if (elapsedSec < 0.001) return                         // < 1 ms — skip
            if (!lastRefillNanos.compareAndSet(prevNanos, now)) return // another thread won

            val add = elapsedSec * refillRatePerSecond
            while (true) {
                val currentBits = tokenBits.get()
                val current     = java.lang.Double.longBitsToDouble(currentBits)
                val newTokens   = minOf(current + add, capacity)
                val newBits     = java.lang.Double.doubleToRawLongBits(newTokens)
                if (tokenBits.compareAndSet(currentBits, newBits)) break
            }
        }
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val sweepScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Background sweep: remove buckets that have been idle for [evictAfterMs]
        sweepScope.launch {
            while (isActive) {
                delay(60_000L)
                val cutoff = System.currentTimeMillis() - evictAfterMs
                val before = buckets.size
                buckets.entries.removeIf { it.value.lastAccessMs.get() < cutoff }
                val evicted = before - buckets.size
                if (evicted > 0) log.debug("RateLimiter evicted $evicted stale buckets")
            }
        }
    }

    /**
     * Returns true if the request is allowed (token consumed), false if rate-limited.
     *
     * @param key    Unique identifier for the client (userId or IP address).
     * @param tokens Number of tokens to consume (default 1).
     */
    fun tryConsume(key: String, tokens: Double = 1.0): Boolean =
        buckets.computeIfAbsent(key) { Bucket() }.tryConsume(tokens)

    /** Current approximate token count for [key] (for metrics/debugging). */
    fun peek(key: String): Double {
        val bucket = buckets[key] ?: return capacity
        val bits = bucket.lastAccessMs   // just to access lastAccess without reflection
        return java.lang.Double.longBitsToDouble(
            // Access tokens field via the same CAS path — read current bits
            (bucket as? Any)?.let {
                val f = it.javaClass.getDeclaredField("tokenBits")
                f.isAccessible = true
                (f.get(it) as AtomicLong).get()
            } ?: java.lang.Double.doubleToRawLongBits(capacity)
        )
    }

    fun shutdown() = sweepScope.coroutineContext[SupervisorJob()]?.cancel()
}
