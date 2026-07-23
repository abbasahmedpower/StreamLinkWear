package com.streamlink.backend

import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * PeerRegistry — tracks active phone↔watch peer connections.
 * Thread-safe. Supports pair lookup, health check, and cleanup.
 */
class PeerRegistry {

    data class PeerSession(
        val peerId: String,
        val userId: String,
        val deviceType: DeviceType,
        val wsSession: DefaultWebSocketSession,
        val connectedAtMs: Long = System.currentTimeMillis(),
        var lastPingMs: Long = System.currentTimeMillis()
    )

    enum class DeviceType { PHONE, WATCH }

    data class PeerPair(
        val phone: PeerSession,
        val watch: PeerSession,
        val sessionId: String = generateSessionId(),
        val pairedAtMs: Long = System.currentTimeMillis()
    )

    private val peers     = ConcurrentHashMap<String, PeerSession>()                       // peerId → session
    private val pairs     = ConcurrentHashMap<String, PeerPair>()                          // userId → pair
    // ✅ §3.5: Deterministic key prevents stale-session pairing on reconnect
    private val byKey     = ConcurrentHashMap<Pair<String, DeviceType>, PeerSession>()     // (userId,type) → session
    private val mutex     = Mutex()
    private val pairCount = AtomicLong(0)

    suspend fun register(session: PeerSession): Unit = mutex.withLock {
        val key = session.userId to session.deviceType
        // ✅ §3.5: Replace-on-reconnect — close stale socket instead of leaving two entries
        byKey[key]?.let { old ->
            if (old.peerId != session.peerId) {
                peers.remove(old.peerId)
                pairs.remove(session.userId)   // force re-pair with the fresh session
                try {
                    old.wsSession.close(CloseReason(CloseReason.Codes.NORMAL, "Replaced by new connection"))
                } catch (_: Exception) { /* already dead — fine */ }
            }
        }
        peers[session.peerId] = session
        byKey[key] = session
        tryPairUser(session.userId)
    }

    suspend fun unregister(peerId: String): Unit = mutex.withLock {
        val session = peers.remove(peerId) ?: return@withLock
        byKey.remove(session.userId to session.deviceType)   // ✅ §3.5: keep byKey consistent
        pairs.remove(session.userId)
    }

    fun updatePing(peerId: String) {
        peers[peerId]?.let { it.lastPingMs = System.currentTimeMillis() }
    }

    fun getPair(userId: String): PeerPair? = pairs[userId]

    fun getPeer(peerId: String): PeerSession? = peers[peerId]

    fun getPartner(peerId: String): PeerSession? {
        val session = peers[peerId] ?: return null
        val pair = pairs[session.userId] ?: return null
        return when (session.deviceType) {
            DeviceType.PHONE -> pair.watch
            DeviceType.WATCH -> pair.phone
        }
    }

    private fun tryPairUser(userId: String) {
        // ✅ §3.5: O(1) deterministic lookup — no ambiguity when two sessions exist for same (user,type)
        val phone = byKey[userId to DeviceType.PHONE] ?: return
        val watch = byKey[userId to DeviceType.WATCH] ?: return
        if (pairs.containsKey(userId)) return   // already paired
        pairs[userId] = PeerPair(phone = phone, watch = watch)
        pairCount.incrementAndGet()
    }

    /** Evict sessions that haven't sent a ping in > 60s */
    suspend fun evictStale(maxAgeMs: Long = 60_000): List<String> = mutex.withLock {
        val now = System.currentTimeMillis()
        val stale = peers.entries
            .filter { now - it.value.lastPingMs > maxAgeMs }
            .map { it.key }
        stale.forEach { peerId ->
            val session = peers.remove(peerId)
            if (session != null) {
                byKey.remove(session.userId to session.deviceType)   // ✅ §3.5
                pairs.remove(session.userId)
            }
        }
        return@withLock stale
    }

    fun stats(): Map<String, Any> = mapOf(
        "peers"     to peers.size,
        "pairs"     to pairs.size,
        "totalPairs" to pairCount.get()
    )

    companion object {
        private val idGen = AtomicLong(0)
        fun generateSessionId(): String =
            "SL-${System.currentTimeMillis()}-${idGen.incrementAndGet()}"
    }
}
