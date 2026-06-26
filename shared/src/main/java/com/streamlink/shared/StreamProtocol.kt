package com.streamlink.shared

object StreamProtocol {
    // TCP Direct Socket
    const val DIRECT_SOCKET_PORT = 8999
    const val CHUNK_MTU = 3900
    const val WIRE_HEADER_SIZE = 10

    // Video profiles
    const val WEAR_W_FULL = 466
    const val WEAR_H_FULL = 466
    const val WEAR_FPS_FULL = 30
    const val WEAR_BPS_FULL = 1800

    const val WEAR_W_ECO = 320
    const val WEAR_H_ECO = 320
    const val WEAR_FPS_ECO = 15
    const val WEAR_BPS_ECO = 600

    // Signaling paths
    const val PATH_SECURE  = "/streamlink/secure"
    const val PATH_CONTROL = "/streamlink/control"
    const val PATH_ACK     = "/streamlink/ack"

    // Control messages
    const val MSG_STOP_STREAM      = "STOP"
    const val MSG_KEYFRAME_REQUEST = "IDR"
    const val MSG_WATCH_READY_ACK  = "ACK"
    const val MSG_PING = "PING"
    const val MSG_PONG = "PONG"

    // Stream modes
    const val MODE_MIRROR = "mirror"
    const val MODE_DIRECT = "direct"

    // Reconnect
    const val MAX_RECONNECT_ATTEMPTS = 8
    const val RECONNECT_BASE_MS = 500L
    const val RECONNECT_MAX_MS  = 30_000L

    // ABR / backpressure
    const val RTT_SAMPLE_WINDOW          = 10
    const val BACKPRESSURE_HIGH_WATERMARK = 0.75f
    const val BACKPRESSURE_LOW_WATERMARK  = 0.40f

    // Metrics
    const val METRICS_FLUSH_INTERVAL_MS = 2_000L

    // Circuit breaker
    const val CB_FAILURE_THRESHOLD = 5
    const val CB_OPEN_DURATION_MS  = 30_000L

    // Pools
    const val WIRE_POOL_CAPACITY  = 32
    const val FRAME_POOL_CAPACITY = 64
    const val NAL_POOL_CAPACITY   = 128

    data class TurnServer(val url: String, val username: String, val credential: String)

    val TURN_SERVERS: List<TurnServer> = listOf(
        TurnServer(
            url        = "turn:turn.streamlink.local:3478",
            username   = "streamlink",
            credential = "supersecret"
        ),
        TurnServer(
            url        = "turn:turn.streamlink.local:3478?transport=tcp",
            username   = "streamlink",
            credential = "supersecret"
        )
    )

    fun isTurnConfigured(): Boolean =
        TURN_SERVERS.isNotEmpty() &&
        TURN_SERVERS.first().url != "turn:your.turn.server:3478"

    // ── Security constants ────────────────────────────────────────────────
    const val AES_KEY_BITS       = 256
    const val GCM_IV_BYTES       = 12
    const val GCM_TAG_BITS       = 128
    const val TOKEN_VALIDITY_MS  = 30_000L   // 30 seconds replay window

    val ALLOWED_DOMAINS: Set<String> = setOf(
        "streamlink.local",
        "streamlink.app",
        "localhost"
    )
}
