package com.streamlink.shared

object StreamProtocol {
    // TCP Direct Socket
    const val DIRECT_SOCKET_PORT = 8999
    const val CHUNK_MTU = 3900

    // Wire header (25 bytes total):
    // MAGIC(4) | VERSION(1) | nalSeq(4) | chunkIdx(2) | totalChunks(2) | flags(1) | nalType(1) | payloadSize(2) | crc16(2) | timestampUs(8)
    // Removed: deadlineUs(8) — moved to flags bits; reserved shrunk from 6→2
    // Savings: 33 → 25 bytes = −8% bandwidth on every chunk
    const val WIRE_HEADER_SIZE = 25

    // Horus Protocol Identifiers
    const val MAGIC_NUMBER = 0x484F5255 // "HORU"
    const val PROTOCOL_VERSION: Byte = 2   // bumped: new header layout, CRC16 added

    /**
     * Phase 3 decision — protocol.ExtensibleHeader (V3) stays EXPERIMENTAL, not wired
     * into the production DirectSocketClient/Server transport. Reasoning:
     *  1. DirectSocketClient (receiver) only decodes the fixed 25-byte V2 header at
     *     hardcoded offsets (HDR_MAGIC..HDR_TIMESTAMP_US). Flipping NalChunker's sender
     *     side to V3 without a matching receiver rewrite would silently corrupt every
     *     phone → watch stream — a wire-format break, not a safe incremental step.
     *  2. V3's own value proposition — multi-stream StreamId, out-of-order ACK bitmap
     *     piggyback (protocol.FrameWindow / protocol.AckBitmap) — targets a UDP-like,
     *     possibly-reordering transport. The current transport is TCP, which already
     *     guarantees in-order delivery (see FrameAssembler's own doc comment), so V3's
     *     main advantages don't apply yet.
     *  3. FrameWindow and AckBitmap are fully implemented and unit-tested but have zero
     *     production call sites — they were built ahead of the transport that needs them.
     * Flip this to true only after DirectSocketClient/Server gain a matching V3 decode
     * path and FrameWindow/AckBitmap are wired to a transport that benefits from them.
     */
    const val V3_EXTENSIBLE_HEADER_ENABLED = false

    // Header field offsets (for receiver)
    const val HDR_MAGIC         = 0   // Int   (4 bytes)
    const val HDR_VERSION       = 4   // Byte  (1 byte)
    const val HDR_NAL_SEQ       = 5   // Int   (4 bytes)
    const val HDR_CHUNK_IDX     = 9   // Short (2 bytes)
    const val HDR_TOTAL_CHUNKS  = 11  // Short (2 bytes)
    const val HDR_FLAGS         = 13  // Byte  (1 byte) bit0=keyframe, bit1=hasDeadline
    const val HDR_NAL_TYPE      = 14  // Byte  (1 byte)
    const val HDR_PAYLOAD_SIZE  = 15  // Short (2 bytes)
    const val HDR_CRC16         = 17  // Short (2 bytes) — CRC-CCITT of bytes [0..16]
    const val HDR_TIMESTAMP_US  = 19  // Long  (8 bytes)
    // deadlineUs removed — reconstruct from timestampUs + per-profile deadline budget

    // Wire buffer pool (sized for new 25-byte header)
    const val WIRE_BUFFER_SIZE = CHUNK_MTU + WIRE_HEADER_SIZE + 64

    /**
     * CRC-CCITT (CRC16/XMODEM) over the first [length] bytes of [data].
     * Used to detect silent corruption in the wire header before decoding payload.
     *
     * Polynomial: 0x1021, Init: 0x0000, RefIn: false, RefOut: false, XorOut: 0x0000
     */
    fun crc16(data: ByteArray, length: Int = data.size): Short {
        var crc = 0
        for (i in 0 until length) {
            val b = data[i].toInt() and 0xFF
            for (bit in 7 downTo 0) {
                val msb = (crc ushr 15) and 1
                crc = (crc shl 1) or ((b ushr bit) and 1)
                if (msb == 1) crc = crc xor 0x1021
            }
        }
        repeat(16) {
            val msb = (crc ushr 15) and 1
            crc = crc shl 1
            if (msb == 1) crc = crc xor 0x1021
        }
        return (crc and 0xFFFF).toShort()
    }

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
    const val PATH_HEARTBEAT_PING = "/streamlink/heartbeat"

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
            // ✅ 5.3: URL from BuildConfig (populated via secrets.properties) — not a hardcoded constant
            url        = BuildConfig.TURN_URL.ifBlank { "turn:turn.streamlink.local:3478" },
            username   = BuildConfig.TURN_USERNAME,
            credential = BuildConfig.TURN_PASSWORD
        ),
        TurnServer(
            url        = BuildConfig.TURN_URL.ifBlank { "turn:turn.streamlink.local:3478?transport=tcp" }.let {
                if (it.contains("?")) it else "$it?transport=tcp"
            },
            username   = BuildConfig.TURN_USERNAME,
            credential = BuildConfig.TURN_PASSWORD
        )
    )

    // ✅ 5.3: Checks BOTH URL (not the placeholder) AND credentials (non-blank).
    // Previously always returned false because the URL was a hardcoded constant.
    // Checks only the primary server — the TCP variant is always derived from the same URL.
    fun isTurnConfigured(): Boolean {
        val primary = TURN_SERVERS.firstOrNull() ?: return false
        return primary.url != "turn:turn.streamlink.local:3478" &&
               primary.credential.isNotBlank()
    }

    // ── Security constants ────────────────────────────────────────────────
    const val AES_KEY_BITS       = 256
    const val GCM_IV_BYTES       = 12
    const val GCM_TAG_BITS       = 128
    const val TOKEN_VALIDITY_MS  = 5_000L    // 5 seconds replay window

    // ── Touch & Control Reverse Channel ─────────────────────────────────────────────────────
    const val MAGIC_NUMBER_INPUT   = 0x484F5443 // "HOTC" — Horus Touch Control
    const val MAGIC_NUMBER_CONTROL = 0x484F434E // "HOCN" — Horus Control Network
    const val MAGIC_NUMBER_JSON    = 0x484F4A53 // "HOJS" — Horus JSON payload
    const val INPUT_FRAME_SIZE     = 32         // 32-byte cache-aligned frame
    
    // Audio & Video payload types
    const val PAYLOAD_TYPE_VIDEO_H264: Byte = 1
    const val PAYLOAD_TYPE_AUDIO_PCM16: Byte = 2
    const val AUDIO_SAMPLE_RATE = 24000
    const val AUDIO_CHANNELS = 1

    // Control Commands
    const val CMD_SET_BITRATE = 1
    const val CMD_GLOBAL_ACTION = 2
    const val CMD_SET_BUFFER_JITTER_MS = 3   // phone → watch: jitter-buffer target (0-800ms)
    const val CMD_SET_QUALITY_MODE = 4       // watch → phone: 0=BATTERY_SAVER, 1=BALANCED, 2=HIGH_QUALITY
    const val CMD_REQUEST_KEYFRAME = 5       // watch → phone: request IDR frame
    const val CMD_EPOCH_ACK = 6              // phone <-> watch: Fast Crypto Resumption Handshake
    const val CMD_JSON_SETTINGS = 7          // phone → watch: JSON-encoded ControlMessage.SettingsUpdate

    val ALLOWED_DOMAINS: Set<String> = setOf(
        "streamlink.local",
        "streamlink.app",
        "localhost"
    )
}
