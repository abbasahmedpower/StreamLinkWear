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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

fun main() {
    val nodeId  = System.getenv("NODE_ID")  ?: "NODE_1"
    val redisUrl = System.getenv("REDIS_URL") ?: "redis://localhost:6379"

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(nodeId, redisUrl)
    }.start(wait = true)
}

// ─── Metrics model ────────────────────────────────────────────────────────────
@Serializable
data class StreamMetricsSnapshot(
    val nodeId: String,
    val timestampMs: Long,
    val activePeers: Int,
    val activePairs: Int,
    val totalPairedSessions: Long,
    val fps: Int = 0,
    val latencyMs: Long = 0L,
    val bitrateKbps: Int = 0,
    val packetLossPercent: Float = 0f
)

// Global live metric state (updated by signaling events)
object LiveMetrics {
    val fps         = AtomicInteger(0)
    val latencyMs   = AtomicLong(0)
    val bitrateKbps = AtomicInteger(0)
    val lossPercent = AtomicInteger(0)  // stored as permille (×10) for int atomics

    // Dashboard WebSocket sessions
    val dashboardSessions: MutableSet<DefaultWebSocketSession> =
        Collections.synchronizedSet(LinkedHashSet())

    fun update(fps: Int, latencyMs: Long, bitrateKbps: Int, lossPermille: Int) {
        this.fps.set(fps)
        this.latencyMs.set(latencyMs)
        this.bitrateKbps.set(bitrateKbps)
        this.lossPercent.set(lossPermille)
    }
}

fun Application.module(nodeId: String, redisUrl: String) {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout    = Duration.ofSeconds(60)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    install(ContentNegotiation) { json() }

    // Redis for cross-node session sharing
    val redisClient: RedisClient = RedisClient.create(redisUrl)
    val redis: StatefulRedisConnection<String, String> = redisClient.connect()

    val registry = PeerRegistry()
    val orchestrator = HandoffOrchestrator(registry, redis, nodeId)

    // Background: broadcast metrics to dashboard every 500ms
    launch {
        while (isActive) {
            delay(500)
            val stats = registry.stats()
            val snapshot = StreamMetricsSnapshot(
                nodeId            = nodeId,
                timestampMs       = System.currentTimeMillis(),
                activePeers       = (stats["peers"] as? Int) ?: 0,
                activePairs       = (stats["pairs"] as? Int) ?: 0,
                totalPairedSessions = (stats["totalPairs"] as? Long) ?: 0L,
                fps               = LiveMetrics.fps.get(),
                latencyMs         = LiveMetrics.latencyMs.get(),
                bitrateKbps       = LiveMetrics.bitrateKbps.get(),
                packetLossPercent = LiveMetrics.lossPercent.get() / 10f
            )
            val json = Json.encodeToString(snapshot)
            val dead = mutableListOf<DefaultWebSocketSession>()
            LiveMetrics.dashboardSessions.forEach { ws ->
                try { ws.send(Frame.Text(json)) }
                catch (_: Exception) { dead.add(ws) }
            }
            LiveMetrics.dashboardSessions.removeAll(dead.toSet())
        }
    }

    routing {
        get("/health") {
            call.respondText("OK node=$nodeId redis=${redis.isOpen}")
        }

        // ── Real-time metrics for Dashboard ──────────────────────────────────
        webSocket("/metrics") {
            LiveMetrics.dashboardSessions.add(this)
            try {
                // Accept optional metric updates from phone client
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        // e.g. phone can push: {"fps":30,"latencyMs":45,"bitrateKbps":2000}
                        // (parse and update LiveMetrics here if needed)
                    }
                }
            } finally {
                LiveMetrics.dashboardSessions.remove(this)
            }
        }

        // ── WebRTC signaling ─────────────────────────────────────────────────
        webSocket("/signal/{userId}/{deviceType}") {
            val userId     = call.parameters["userId"]     ?: return@webSocket close()
            val deviceType = call.parameters["deviceType"] ?: return@webSocket close()
            
            // X-Horus-Authorization Security Check
            val authToken = call.request.headers["X-Horus-Authorization"]
            if (authToken != "NASA_GRADE_SECRET_TOKEN_HORUS") { 
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized Access Attempt"))
                return@webSocket
            }

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

        // ── Legacy handoff endpoint ──────────────────────────────────────────
        webSocket("/stream/handoff/{roomId}/{deviceType}") {
            val roomId     = call.parameters["roomId"]     ?: "default"
            val deviceType = call.parameters["deviceType"] ?: "UNKNOWN"
            
            // X-Horus-Authorization Security Check
            val authToken = call.request.headers["X-Horus-Authorization"]
            if (authToken != "NASA_GRADE_SECRET_TOKEN_HORUS") { 
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized Access Attempt"))
                return@webSocket
            }

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
