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
import io.ktor.server.request.*
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import io.ktor.network.tls.certificates.generateCertificate
import java.io.File
import java.security.KeyStore
import org.slf4j.LoggerFactory

import com.streamlink.backend.config.SecureConfig

fun main() {
    // 1. Fail-closed check: Crash immediately if secrets are missing or placeholders
    SecureConfig.horusSecretToken
    SecureConfig.redisUrl
    SecureConfig.tlsPassword

    val nodeId  = SecureConfig.nodeId
    val redisUrl = SecureConfig.redisUrl
    val tlsPassword = SecureConfig.tlsPassword
    
    val keystoreFile = File("build/keystore.jks")
    if (!keystoreFile.exists()) {
        keystoreFile.parentFile.mkdirs()
        generateCertificate(
            file = keystoreFile,
            keyAlias = "streamlink",
            keyPassword = tlsPassword,
            jksPassword = tlsPassword
        )
    }

    val keystore = KeyStore.getInstance(KeyStore.getDefaultType())
    keystoreFile.inputStream().use {
        keystore.load(it, tlsPassword.toCharArray())
    }

    val env = applicationEngineEnvironment {
        log = LoggerFactory.getLogger("ktor.application")
        connector {
            port = 8080
            host = "0.0.0.0"
        }
        sslConnector(
            keyStore = keystore,
            keyAlias = "streamlink",
            keyStorePassword = { tlsPassword.toCharArray() },
            privateKeyPassword = { tlsPassword.toCharArray() }
        ) {
            port = 8443
            host = "0.0.0.0"
            keyStorePath = keystoreFile
        }
        module {
            module(nodeId, redisUrl)
        }
    }

    embeddedServer(Netty, env).start(wait = true)
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
        maxFrameSize = 4L * 1024 * 1024 // ✅ FIX #17: Prevent massive frame DoS
        masking = true                  // ✅ FIX #17: Enforce masking to prevent proxy cache poisoning
    }
    install(ContentNegotiation) { json() }

    // Redis for cross-node session sharing
    val redisClient: RedisClient = RedisClient.create(redisUrl)
    var redis: StatefulRedisConnection<String, String>? = null
    try {
        redis = redisClient.connect()
        log.info("Connected to Redis successfully.")
    } catch (e: Exception) {
        log.error("Failed to connect to Redis. Proceeding without cluster sync.", e)
    }

    val registry = PeerRegistry()
    val orchestrator = HandoffOrchestrator(registry, redis, nodeId)

    val expectedToken = SecureConfig.horusSecretToken

    // Background: broadcast metrics to dashboard every 500ms
    val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    monitorScope.launch {
        while (isActive) {
            delay(500)
            
            val evicted = registry.evictStale()
            if (evicted.isNotEmpty()) {
                // Log eviction.
                // Note: The peers map is cleaned up. Ktor handles timeout and actual WS close.
            }
            
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
            call.respondText("OK node=$nodeId redis=${redis?.isOpen == true}")
        }

        // ── Device Registration (one-time, rate-limited by Global Token) ────────
        post("/api/v1/register") {
            val globalAuth = call.request.headers["X-Horus-Global-Token"] ?: ""
            if (!java.security.MessageDigest.isEqual(
                    globalAuth.toByteArray(Charsets.UTF_8),
                    expectedToken.toByteArray(Charsets.UTF_8)
                )) {
                call.respond(io.ktor.http.HttpStatusCode.Unauthorized, "Unauthorized")
                return@post
            }

            val params  = call.receiveParameters()
            val userId  = params["userId"]?.trim() ?: ""

            if (userId.isBlank() || userId.length > 64 ||
                !userId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Invalid userId")
                return@post
            }

            val token = ServerIdentityVerifier.generateClientToken(userId)
            call.respond(mapOf("token" to token))
        }

        // ── Real-time metrics for Dashboard ──────────────────────────────────
        webSocket("/metrics") {
            // ✅ FIX High #4: Protect /metrics from unauthenticated reconnaissance.
            val clientToken = call.request.headers["X-Horus-Identity-Token"] ?: ""
            if (ServerIdentityVerifier.verifyClientToken(clientToken) == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

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
            val routeUserId = call.parameters["userId"]     ?: return@webSocket close()
            val deviceType  = call.parameters["deviceType"] ?: return@webSocket close()

            // ✅ FIX Critical #1 & #2: Stateless signed-identity verification
            // Replaces the static shared HORUS_SECRET_TOKEN for per-device auth.
            val clientToken  = call.request.headers["X-Horus-Identity-Token"] ?: ""
            val verifiedUserId = ServerIdentityVerifier.verifyClientToken(clientToken)
            if (verifiedUserId == null || verifiedUserId != routeUserId) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid Identity Token"))
                return@webSocket
            }

            if (routeUserId.length > 64 || !routeUserId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid userId format"))
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
                userId = verifiedUserId,
                deviceType = deviceEnum,
                wsSession = this
            )

            registry.register(session)
            orchestrator.onDeviceConnected(verifiedUserId, deviceEnum, peerId)

            try {
                incoming.consumeEach { frame ->
                    registry.updatePing(peerId)
                    if (frame is Frame.Text) {
                        orchestrator.route(verifiedUserId, deviceEnum, frame.readText())
                    }
                }
            } finally {
                registry.unregister(peerId)
                orchestrator.onDeviceDisconnected(verifiedUserId, deviceEnum, peerId)
            }
        }

        // ── Legacy handoff endpoint (DEPRECATED — use /signal) ──────────────
        webSocket("/stream/handoff/{roomId}/{deviceType}") {
            val roomId     = call.parameters["roomId"]     ?: "default"
            val deviceType = call.parameters["deviceType"] ?: "UNKNOWN"
            
            // ✅ FIX High #3: Constant-time comparison to prevent timing side-channel.
            val authToken = call.request.headers["X-Horus-Authorization"] ?: ""
            if (!java.security.MessageDigest.isEqual(
                    authToken.toByteArray(Charsets.UTF_8),
                    expectedToken.toByteArray(Charsets.UTF_8)
                )) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            if (roomId.length > 64 || !roomId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid roomId format"))
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
