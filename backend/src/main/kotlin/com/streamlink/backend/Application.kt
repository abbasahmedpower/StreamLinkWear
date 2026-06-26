package com.streamlink.backend

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.channels.consumeEach
import java.time.Duration
import java.util.UUID

fun main() {
    val nodeId  = System.getenv("NODE_ID")  ?: "NODE_1"
    val redisUrl = System.getenv("REDIS_URL") ?: "redis://localhost:6379"

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(nodeId, redisUrl)
    }.start(wait = true)
}

fun Application.module(nodeId: String, redisUrl: String) {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout    = Duration.ofSeconds(60)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    install(ContentNegotiation) { json() }

    // ✅ FIX M6: Redis for cross-node session sharing
    val redisClient: RedisClient = RedisClient.create(redisUrl)
    val redis: StatefulRedisConnection<String, String> = redisClient.connect()

    val registry = PeerRegistry()
    val orchestrator = HandoffOrchestrator(registry, redis, nodeId)

    routing {
        get("/health") {
            call.respondText("OK node=$nodeId redis=${redis.isOpen}")
        }

        // WebRTC signaling endpoint
        webSocket("/signal/{userId}/{deviceType}") {
            val userId     = call.parameters["userId"]     ?: return@webSocket close()
            val deviceType = call.parameters["deviceType"] ?: return@webSocket close()
            val peerId     = UUID.randomUUID().toString()

            val deviceEnum = try {
                PeerRegistry.DeviceType.valueOf(deviceType.uppercase())
            } catch (_: Exception) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid deviceType"))
                return@webSocket
            }

            val session = PeerRegistry.PeerSession(
                peerId = peerId,
                userId = userId,
                deviceType = deviceEnum,
                wsSession = this
            )

            registry.register(session)
            orchestrator.onDeviceConnected(userId, deviceEnum, peerId)

            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        orchestrator.route(userId, deviceEnum, frame.readText())
                    }
                }
            } finally {
                registry.unregister(peerId)
                orchestrator.onDeviceDisconnected(userId, deviceEnum, peerId)
            }
        }

        // Legacy handoff endpoint (backwards-compat)
        webSocket("/stream/handoff/{roomId}/{deviceType}") {
            val roomId     = call.parameters["roomId"]     ?: "default"
            val deviceType = call.parameters["deviceType"] ?: "UNKNOWN"
            val legacyOrch = HandoffOrchestrator(registry, redis, nodeId)
            legacyOrch.registerDevice(roomId, deviceType, this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) legacyOrch.broadcastToPeer(roomId, deviceType, frame.readText())
                }
            } finally {
                legacyOrch.removeDevice(roomId, deviceType)
            }
        }
    }
}
