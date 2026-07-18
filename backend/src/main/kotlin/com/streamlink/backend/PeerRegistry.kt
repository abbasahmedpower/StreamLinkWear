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
        val deviceType: DeviceType,   // PHONE or WATCH
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

    private val peers  = ConcurrentHashMap<String, PeerSession>()  // peerId → session
    private val pairs  = ConcurrentHashMap<String, PeerPair>()     // userId → pair
    private val mutex  = Mutex()
    private val pairCount = AtomicLong(0)

    suspend fun register(session: PeerSession): Unit = mutex.withLock {
        peers[session.peerId] = session
        tryPairUser(session.userId)
    }

    suspend fun unregister(peerId: String): Unit = mutex.withLock {
        val session = peers.remove(peerId) ?: return@withLock
        // Remove pair if either side disconnects
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
        val userPeers = peers.values.filter { it.userId == userId }
        val phone = userPeers.find { it.deviceType == DeviceType.PHONE } ?: return
        val watch = userPeers.find { it.deviceType == DeviceType.WATCH } ?: return
        if (pairs.containsKey(userId)) return  // Already paired

        val pair = PeerPair(phone = phone, watch = watch)
        pairs[userId] = pair
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
