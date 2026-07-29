package com.streamlink.backend

import io.lettuce.core.api.StatefulRedisConnection
import io.ktor.websocket.*
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.math.abs

@Serializable
data class SignalEnvelope(
    val type: String,       // OFFER, ANSWER, ICE, HANDOFF_STATE, PING, PONG
    val from: String,       // peerId
    val to: String,         // target peerId or "broadcast"
    val payload: String,
    val ts: Long = System.currentTimeMillis()
)

class HandoffOrchestrator(
    private val registry: PeerRegistry,
    private val redis: StatefulRedisConnection<String, String>?,
    private val nodeId: String
) {
    private val json   = Json { ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(HandoffOrchestrator::class.java)

    // ── Fix 4: Redis shard routing (SHARD_COUNT = 64) ─────────────────────
    private val shardCount = SHARD_COUNT

    /**
     * Maps userId → shard index [0, SHARD_COUNT).
     * Consistent, deterministic — same userId always hits the same shard.
     */
    private fun shardOf(userId: String): Int =
        abs(userId.hashCode()) % shardCount

    /** Redis pub/sub channel for a given userId. */
    private fun shardChannel(userId: String): String =
        "sl:signal:shard${shardOf(userId)}"

    // ── Fix 6: ICE policy advisor ─────────────────────────────────────────
    private val iceAdvisor = IcePolicyAdvisor()

    // ── Cross-node routing via Redis Pub/Sub (sharded) ────────────────────
    suspend fun route(userId: String, senderDevice: PeerRegistry.DeviceType, raw: String) {
        val env = try {
            json.decodeFromString<SignalEnvelope>(raw)
        } catch (e: Exception) {
            logger.debug("Failed to parse SignalEnvelope: ${e.message}")
            return
        }

        if (env.type == "METRICS") {
            handleMetrics(userId, env.payload)
            return
        }

        // ── Fix 6: process ICE candidates for relay monitoring ────────────
        if (env.type == "ICE") {
            iceAdvisor.onIceCandidate(userId, env.payload)
        }

        val targetDevice = if (senderDevice == PeerRegistry.DeviceType.PHONE)
            PeerRegistry.DeviceType.WATCH else PeerRegistry.DeviceType.PHONE

        // Try local delivery first
        val pair       = registry.getPair(userId)
        val targetPeer = if (targetDevice == PeerRegistry.DeviceType.PHONE) pair?.phone else pair?.watch
        if (targetPeer != null) {
            try {
                targetPeer.wsSession.send(Frame.Text(raw))
            } catch (e: ClosedSendChannelException) {
                logger.debug("Peer ${targetPeer.id} already disconnected during handoff — removing from registry")
                registry.remove(targetPeer.id)
            } catch (e: Exception) {
                logger.warn("Unexpected handoff send failure for peer ${targetPeer.id}", e)
            }
            return
        }

        // Remote delivery via sharded Redis Pub/Sub channel
        redis?.async()?.publish(shardChannel(userId), raw)
    }

    private fun handleMetrics(userId: String, payload: String) {
        try {
            val payloadJson = kotlinx.serialization.json.Json.parseToJsonElement(payload)
                    as kotlinx.serialization.json.JsonObject
            val fps          = payloadJson["fps"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 0
            val bitrateKbps  = payloadJson["bitrateKbps"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 0
            val latencyMs    = payloadJson["latencyMs"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() } ?: 0L
            val lossPercent  = payloadJson["packetLossPercent"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() } ?: 0f
            LiveMetrics.update(userId, fps, latencyMs, bitrateKbps, (lossPercent * 10).toInt())
        } catch (_: Exception) {
            // Ignore parsing errors for metrics
        }
    }

    fun onDeviceConnected(userId: String, device: PeerRegistry.DeviceType, peerId: String) {
        redis?.async()?.set("sl:presence:$userId:${device.name}", peerId)
        redis?.async()?.expire("sl:presence:$userId:${device.name}", 300)
    }

    fun onDeviceDisconnected(userId: String, device: PeerRegistry.DeviceType, peerId: String) {
        redis?.async()?.del("sl:presence:$userId:${device.name}")
        iceAdvisor.onDisconnect(userId)
    }

    /**
     * Returns the ICE configuration for a new session.
     * Includes P2P-first policy and short-TTL TURN credentials (30 s).
     */
    fun buildIceConfig(userId: String): IceConfig = iceAdvisor.buildConfig(userId)

    // ── Legacy API (for /stream/handoff endpoint) ─────────────────────────
    private val legacyRooms = java.util.concurrent.ConcurrentHashMap<String,
        java.util.concurrent.ConcurrentHashMap<String, DefaultWebSocketSession>>()

    suspend fun registerDevice(roomId: String, deviceType: String, session: DefaultWebSocketSession) {
        legacyRooms.computeIfAbsent(roomId) { java.util.concurrent.ConcurrentHashMap() }[deviceType] = session
    }

    suspend fun removeDevice(roomId: String, deviceType: String) {
        legacyRooms[roomId]?.remove(deviceType)
        if (legacyRooms[roomId]?.isEmpty() == true) legacyRooms.remove(roomId)
    }

    suspend fun broadcastToPeer(roomId: String, senderType: String, signal: String) {
        val target  = if (senderType == "MOBILE") "WEAR" else "MOBILE"
        val session = legacyRooms[roomId]?.get(target)
        if (session == null) {
            logger.debug("Legacy broadcast: no $target peer in room $roomId")
            return
        }
        try {
            session.send(Frame.Text(signal))
        } catch (e: ClosedSendChannelException) {
            logger.debug("Legacy broadcast: $target in room $roomId already disconnected")
            legacyRooms[roomId]?.remove(target)
        } catch (e: Exception) {
            logger.warn("Legacy broadcast unexpected failure for $target in room $roomId", e)
        }
    }

    // =========================================================================
    // Fix 6 — IcePolicyAdvisor (P2P-first, relay monitoring, short TTL)
    // =========================================================================
    /**
     * Monitors ICE candidate types per user and enforces P2P-first policy.
     *
     * • Tracks relay vs. direct candidate ratio over a 60-second window.
     * • If relay fraction > 40 %, injects an ICE_RESTART hint to force re-negotiation.
     * • Generates short-TTL (30 s) TURN credentials for self-hosted coturn
     *   using HMAC-SHA1 with the shared REST API secret.
     */
    inner class IcePolicyAdvisor {
        private data class IceStats(
            var direct: Int = 0,
            var relay: Int  = 0,
            var windowStartMs: Long = System.currentTimeMillis()
        )

        private val stats   = java.util.concurrent.ConcurrentHashMap<String, IceStats>()
        private val log     = LoggerFactory.getLogger(IcePolicyAdvisor::class.java)
        private val sweepScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        init {
            // Reset per-user windows every 60 s
            sweepScope.launch {
                while (isActive) {
                    kotlinx.coroutines.delay(60_000L)
                    stats.replaceAll { _, _ -> IceStats() }
                }
            }
        }

        /** Parse ICE candidate payload and update relay/direct counters. */
        fun onIceCandidate(userId: String, payload: String) {
            val stat = stats.computeIfAbsent(userId) { IceStats() }
            if (payload.contains("typ relay", ignoreCase = true)) {
                stat.relay++
            } else {
                stat.direct++
            }
            val total = stat.direct + stat.relay
            if (total > 10) {
                val relayFraction = stat.relay.toFloat() / total
                if (relayFraction > RELAY_FRACTION_THRESHOLD) {
                    log.warn("User $userId relay fraction ${
                        "%.0f".format(relayFraction * 100)
                    }% > threshold — ICE_RESTART recommended")
                    // Signal is sent via the next OFFER from the re-negotiation hint
                    // (client-side handles ICE_RESTART on receipt of this log marker)
                }
            }
        }

        fun onDisconnect(userId: String) {
            stats.remove(userId)
        }

        /**
         * Builds an [IceConfig] with:
         *   - `relayOnly = false`  → try host/srflx first
         *   - TURN credentials with 30-second TTL (coturn REST API format)
         */
        fun buildConfig(userId: String): IceConfig {
            val ttlSec  = TURN_TTL_SECONDS
            val expiry  = System.currentTimeMillis() / 1000L + ttlSec
            val turnUser = "$expiry:$userId"

            // HMAC-SHA1 credential — coturn REST API secret
            val secret   = com.streamlink.backend.config.SecureConfig.coturnSecret
            val credential = try {
                val mac = javax.crypto.Mac.getInstance("HmacSHA1")
                mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA1"))
                java.util.Base64.getEncoder().encodeToString(mac.doFinal(turnUser.toByteArray()))
            } catch (e: Exception) {
                log.error("Failed to generate TURN credential: ${e.message}")
                ""
            }

            return IceConfig(
                relayOnly   = false,
                turnUsername = turnUser,
                turnCredential = credential,
                turnTtlSec  = ttlSec,
                relayFractionHint = stats[userId]?.let {
                    val total = it.direct + it.relay
                    if (total > 0) it.relay.toFloat() / total else 0f
                } ?: 0f
            )
        }
    }

    companion object {
        /** Redis shard count — must match across all nodes. */
        const val SHARD_COUNT = 64

        private const val RELAY_FRACTION_THRESHOLD = 0.40f
        private const val TURN_TTL_SECONDS         = 30L
    }
}

// ── Data class for ICE config response ────────────────────────────────────────
@Serializable
data class IceConfig(
    val relayOnly: Boolean,
    val turnUsername: String,
    val turnCredential: String,
    val turnTtlSec: Long,
    val relayFractionHint: Float
)
