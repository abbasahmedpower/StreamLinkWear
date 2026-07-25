package com.streamlink.backend

import io.lettuce.core.api.StatefulRedisConnection
import io.ktor.websocket.*
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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
    private val json = Json { ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(HandoffOrchestrator::class.java)

    // ✅ Cross-node routing via Redis Pub/Sub
    suspend fun route(userId: String, senderDevice: PeerRegistry.DeviceType, raw: String) {
        val env = try {
            json.decodeFromString<SignalEnvelope>(raw)
        } catch (e: Exception) {
            kotlin.io.println("DEBUG: Failed to parse SignalEnvelope: ${e.message}")
            return
        }

        if (env.type == "METRICS") {
            try {
                // Parse payload string as a JSON object
                val payloadJson = kotlinx.serialization.json.Json.parseToJsonElement(env.payload) as kotlinx.serialization.json.JsonObject
                val fps = payloadJson["fps"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toIntOrNull() ?: 0
                val bitrateKbps = payloadJson["bitrateKbps"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toIntOrNull() ?: 0
                val latencyMs = payloadJson["latencyMs"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toLongOrNull() ?: 0L
                val lossPercent = payloadJson["packetLossPercent"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toFloatOrNull() ?: 0f
                
                LiveMetrics.update(userId, fps, latencyMs, bitrateKbps, (lossPercent * 10).toInt())
            } catch (e: Exception) {
                // Ignore parsing errors for metrics
            }
            return
        }

        val targetDevice = if (senderDevice == PeerRegistry.DeviceType.PHONE)
            PeerRegistry.DeviceType.WATCH else PeerRegistry.DeviceType.PHONE

        // Try local delivery first
        val pair = registry.getPair(userId)
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

        // Remote delivery via Redis Pub/Sub
        redis?.async()?.publish("sl:signal:$userId", raw)
    }

    fun onDeviceConnected(userId: String, device: PeerRegistry.DeviceType, peerId: String) {
        redis?.async()?.set("sl:presence:$userId:${device.name}", peerId)
        redis?.async()?.expire("sl:presence:$userId:${device.name}", 300)
    }

    fun onDeviceDisconnected(userId: String, device: PeerRegistry.DeviceType, peerId: String) {
        redis?.async()?.del("sl:presence:$userId:${device.name}")
    }

    // Legacy API (for /stream/handoff endpoint)
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
        val target = if (senderType == "MOBILE") "WEAR" else "MOBILE"
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
}
