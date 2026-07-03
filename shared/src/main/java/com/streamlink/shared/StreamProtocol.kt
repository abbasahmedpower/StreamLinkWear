package com.streamlink.shared

object StreamProtocol {
    // TCP Direct Socket
    const val DIRECT_SOCKET_PORT = 8999
    const val CHUNK_MTU = 3900

    // Wire header: HORU(4) | VERSION(1) | nalSeq(4) | chunkIdx(2) | totalChunks(2) | flags(1) | nalType(1) | payloadSize(2) | timestampUs(8)
    const val WIRE_HEADER_SIZE = 25
    
    // Horus Protocol Identifiers
    const val MAGIC_NUMBER = 0x484F5255 // "HORU"
    const val PROTOCOL_VERSION: Byte = 1

    // Header field offsets (للـ receiver)
    const val HDR_MAGIC         = 0   // Int   (4 bytes)
    const val HDR_VERSION       = 4   // Byte  (1 byte)
    const val HDR_NAL_SEQ       = 5   // Int   (4 bytes)
    const val HDR_CHUNK_IDX     = 9   // Short (2 bytes)
    const val HDR_TOTAL_CHUNKS  = 11  // Short (2 bytes)
    const val HDR_FLAGS         = 13  // Byte  (1 byte)  bit0=keyframe
    const val HDR_NAL_TYPE      = 14  // Byte  (1 byte)
    const val HDR_PAYLOAD_SIZE  = 15  // Short (2 bytes)
    const val HDR_TIMESTAMP_US  = 17  // Long  (8 bytes)

    // Wire buffer pool
    const val WIRE_BUFFER_SIZE = CHUNK_MTU + WIRE_HEADER_SIZE + 8

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
            username   = BuildConfig.TURN_USERNAME,
            credential = BuildConfig.TURN_PASSWORD
        ),
        TurnServer(
            url        = "turn:turn.streamlink.local:3478?transport=tcp",
            username   = BuildConfig.TURN_USERNAME,
            credential = BuildConfig.TURN_PASSWORD
        )
    )

    fun isTurnConfigured(): Boolean =
        TURN_SERVERS.isNotEmpty() &&
        TURN_SERVERS.first().url != "turn:turn.streamlink.local:3478"

    // ── Security constants ────────────────────────────────────────────────
    const val AES_KEY_BITS       = 256
    const val GCM_IV_BYTES       = 12
    const val GCM_TAG_BITS       = 128
    const val TOKEN_VALIDITY_MS  = 5_000L    // 5 seconds replay window

    // ── Touch & Control Reverse Channel ─────────────────────────────────────────────────────
    const val MAGIC_NUMBER_INPUT   = 0x484F5443 // "HOTC" — Horus Touch Control
    const val MAGIC_NUMBER_CONTROL = 0x484F434E // "HOCN" — Horus Control Network
    const val INPUT_FRAME_SIZE     = 32         // 32-byte cache-aligned frame
    
    // Audio & Video payload types
    const val PAYLOAD_TYPE_VIDEO_H264: Byte = 1
    const val PAYLOAD_TYPE_AUDIO_PCM16: Byte = 2
    const val AUDIO_SAMPLE_RATE = 24000
    const val AUDIO_CHANNELS = 1

    // Control Commands
    const val CMD_SET_BITRATE = 1
    const val CMD_GLOBAL_ACTION = 2

    val ALLOWED_DOMAINS: Set<String> = setOf(
        "streamlink.local",
        "streamlink.app",
        "localhost"
    )
}
