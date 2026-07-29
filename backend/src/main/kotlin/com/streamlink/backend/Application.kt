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

    val nodeId      = SecureConfig.nodeId
    val redisUrl    = SecureConfig.redisUrl
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
    val packetLossPercent: Float = 0f,
    // ── Fix 6: relay vs. direct counters for dashboard visibility ─────────
    val relayCount: Int = 0,
    val directCount: Int = 0
)

// ✅ §4.2: Per-user metrics instead of one global object that overwrites all users
object LiveMetrics {
    @Serializable
    data class UserSnapshot(
        val fps: Int = 0,
        val latencyMs: Long = 0L,
        val bitrateKbps: Int = 0,
        val lossPermille: Int = 0
    )

    private val perUser = java.util.concurrent.ConcurrentHashMap<String, UserSnapshot>()

    fun update(userId: String, fps: Int, latencyMs: Long, bitrateKbps: Int, lossPermille: Int) {
        perUser[userId] = UserSnapshot(fps, latencyMs, bitrateKbps, lossPermille)
    }

    fun snapshotFor(userId: String): UserSnapshot? = perUser[userId]
    fun all(): Collection<UserSnapshot> = perUser.values
    fun remove(userId: String) { perUser.remove(userId) }

    // Dashboard WebSocket sessions
    val dashboardSessions: MutableSet<DefaultWebSocketSession> =
        Collections.synchronizedSet(LinkedHashSet())
}

fun Application.module(nodeId: String, redisUrl: String) {
    install(WebSockets) {
        pingPeriod   = Duration.ofSeconds(15)
        timeout      = Duration.ofSeconds(60)
        maxFrameSize = 4L * 1024 * 1024 // ✅ FIX #17: Prevent massive frame DoS
        masking      = true              // ✅ FIX #17: Enforce masking to prevent proxy cache poisoning
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

    val registry     = PeerRegistry()
    val orchestrator = HandoffOrchestrator(registry, redis, nodeId)

    val expectedToken = SecureConfig.horusSecretToken

    // ── Fix 5: Rate limiters (token bucket) ──────────────────────────────
    // signalLimiter : 30 messages/sec per userId  — protects WebSocket signaling
    // registerLimiter: 5 requests/min per client IP — protects /api/v1/register
    val signalLimiter   = RateLimiter(capacity = 30.0,  refillRatePerSecond = 30.0)
    val registerLimiter = RateLimiter(capacity =  5.0,  refillRatePerSecond =  5.0 / 60.0)

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

            val stats    = registry.stats()
            val snapshot = StreamMetricsSnapshot(
                nodeId              = nodeId,
                timestampMs         = System.currentTimeMillis(),
                activePeers         = (stats["peers"] as? Int) ?: 0,
                activePairs         = (stats["pairs"] as? Int) ?: 0,
                totalPairedSessions = (stats["totalPairs"] as? Long) ?: 0L,
                // ✅ §4.2: Use aggregated metrics from all active users
                fps                 = LiveMetrics.all().map { it.fps }.maxOrNull() ?: 0,
                latencyMs           = LiveMetrics.all().map { it.latencyMs }.maxOrNull() ?: 0L,
                bitrateKbps         = LiveMetrics.all().sumOf { it.bitrateKbps },
                packetLossPercent   = LiveMetrics.all().map { it.lossPermille / 10f }.maxOrNull() ?: 0f
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

        // ── Fix 6: ICE config endpoint — short-TTL (30s) coturn credentials ──
        get("/api/v1/ice-config") {
            val clientToken    = call.request.headers["X-Horus-Identity-Token"] ?: ""
            val verifiedUserId = ServerIdentityVerifier.verifyClientToken(clientToken)
            if (verifiedUserId == null) {
                call.respond(io.ktor.http.HttpStatusCode.Unauthorized, "Unauthorized")
                return@get
            }
            val iceConfig = orchestrator.buildIceConfig(verifiedUserId)
            call.respond(iceConfig)
        }

        // ── Device Registration (one-time, rate-limited by Global Token) ────
        post("/api/v1/register") {
            // ── Fix 5: IP-based rate limit (belt + braces alongside nginx) ──
            val clientIp = call.request.headers["X-Real-IP"]
                ?: call.request.local.remoteAddress
            if (!registerLimiter.tryConsume(clientIp)) {
                call.respond(io.ktor.http.HttpStatusCode.TooManyRequests, "Rate limit exceeded")
                return@post
            }

            val globalAuth = call.request.headers["X-Horus-Global-Token"] ?: ""
            if (!java.security.MessageDigest.isEqual(
                    globalAuth.toByteArray(Charsets.UTF_8),
                    expectedToken.toByteArray(Charsets.UTF_8)
                )) {
                call.respond(io.ktor.http.HttpStatusCode.Unauthorized, "Unauthorized")
                return@post
            }

            val params = call.receiveParameters()
            val userId = params["userId"]?.trim() ?: ""

            if (userId.isBlank() || userId.length > 64 ||
                !userId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, "Invalid userId")
                return@post
            }

            val token = ServerIdentityVerifier.generateClientToken(userId)
            call.respond(mapOf("token" to token))
        }

        // ✅ §3.6: Per-device token revocation endpoint
        post("/api/v1/revoke/{userId}") {
            val globalAuth = call.request.headers["X-Horus-Global-Token"] ?: ""
            if (!java.security.MessageDigest.isEqual(
                    globalAuth.toByteArray(Charsets.UTF_8),
                    expectedToken.toByteArray(Charsets.UTF_8)
                )) {
                call.respond(io.ktor.http.HttpStatusCode.Unauthorized, "Unauthorized")
                return@post
            }
            val userId = call.parameters["userId"]
                ?: return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest)
            // TTL matches token lifetime (30 days in seconds)
            redis?.async()?.setex("sl:revoked:$userId", 30L * 24 * 60 * 60, "1")
            LiveMetrics.remove(userId)
            call.respond(io.ktor.http.HttpStatusCode.OK, mapOf("revoked" to userId))
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
            val clientToken    = call.request.headers["X-Horus-Identity-Token"] ?: ""
            val verifiedUserId = ServerIdentityVerifier.verifyClientToken(clientToken)
            if (verifiedUserId == null || verifiedUserId != routeUserId) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid Identity Token"))
                return@webSocket
            }

            // ✅ §3.6: Check revocation list in Redis before accepting connection
            if (redis?.sync()?.get("sl:revoked:$verifiedUserId") == "1") {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Token revoked"))
                return@webSocket
            }

            if (routeUserId.length > 64 || !routeUserId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid userId format"))
                return@webSocket
            }

            val peerId = UUID.randomUUID().toString()

            val deviceEnum = try {
                PeerRegistry.DeviceType.valueOf(deviceType.uppercase())
            } catch (_: Exception) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid deviceType"))
                return@webSocket
            }

            val session = PeerRegistry.PeerSession(
                peerId     = peerId,
                userId     = verifiedUserId,
                deviceType = deviceEnum,
                wsSession  = this
            )

            registry.register(session)
            orchestrator.onDeviceConnected(verifiedUserId, deviceEnum, peerId)

            try {
                incoming.consumeEach { frame ->
                    registry.updatePing(peerId)
                    if (frame is Frame.Text) {
                        // ── Fix 5: per-userId rate limit on WebSocket messages ──
                        if (!signalLimiter.tryConsume(verifiedUserId)) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Rate limit exceeded"))
                            return@consumeEach
                        }
                        orchestrator.route(verifiedUserId, deviceEnum, frame.readText())
                    }
                }
            } finally {
                registry.unregister(peerId)
                orchestrator.onDeviceDisconnected(verifiedUserId, deviceEnum, peerId)
            }
        }

        // ✅ §2.4: Fixed — reuse the single shared orchestrator instance so both devices
        // register into the SAME legacyRooms map and can actually reach each other.
        webSocket("/stream/handoff/{roomId}/{deviceType}") {
            val roomId     = call.parameters["roomId"]     ?: "default"
            val deviceType = call.parameters["deviceType"] ?: "UNKNOWN"

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

            // ✅ §2.4: Was: val legacyOrch = HandoffOrchestrator(...) — each connection
            // got its own isolated instance, so no two devices could ever meet.
            orchestrator.registerDevice(roomId, deviceType, this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) orchestrator.broadcastToPeer(roomId, deviceType, frame.readText())
                }
            } finally {
                orchestrator.removeDevice(roomId, deviceType)
            }
        }
    }
}
