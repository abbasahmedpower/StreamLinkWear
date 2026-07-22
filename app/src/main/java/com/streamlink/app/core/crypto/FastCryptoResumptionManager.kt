package com.streamlink.app.core.crypto

import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

class FastCryptoResumptionManager(
    private val masterSecret: ByteArray,
    private val sessionId: String,
    private val deviceNonce: String
) {

    // Handover Epoch: Increments on every network change
    private val currentEpoch = AtomicInteger(0)
    
    @Volatile
    private var currentTransportType: String = "WIFI"
    
    // Extracted once at the start of the ECDH session
    private val sessionPrk: ByteArray = HKDF.extract(null, masterSecret)

    // Current Transport Secret (Changes per Epoch & TransportType)
    @Volatile
    private lateinit var currentTransportSecret: ByteArray

    // Rotation Limits
    private val ROTATION_LIMIT_BYTES = 500 * 1024 * 1024L // 500 MB
    private val ROTATION_LIMIT_MS = 10 * 60 * 1000L // 10 Minutes
    
    private var bytesEncryptedInEpoch = 0L
    private var epochStartTimeMs = 0L

    init {
        deriveNewTransportSecret()
    }

    /**
     * Called when a network handover occurs.
     * @param newTransportType e.g. "HOTSPOT", "BT", "WIFI"
     */
    fun onNetworkHandover(newTransportType: String): Int {
        currentTransportType = newTransportType
        return triggerRotation()
    }

    /**
     * Call this when a chunk of data is encrypted.
     * It checks limits and returns true if a rotation just happened.
     */
    fun checkAndRotate(bytesProcessed: Long): Boolean {
        bytesEncryptedInEpoch += bytesProcessed
        val timeElapsed = System.currentTimeMillis() - epochStartTimeMs
        
        if (bytesEncryptedInEpoch >= ROTATION_LIMIT_BYTES || timeElapsed >= ROTATION_LIMIT_MS) {
            triggerRotation()
            return true
        }
        return false
    }

    private fun triggerRotation(): Int {
        val epoch = currentEpoch.incrementAndGet()
        deriveNewTransportSecret()
        return epoch
    }

    private fun deriveNewTransportSecret() {
        // Build expansion info incorporating the exact parameters to prevent collisions
        // e.g. "epoch=5|transport=HOTSPOT|session=83A91|device=watch"
        val epoch = currentEpoch.get()
        val infoString = "epoch=$epoch|transport=$currentTransportType|session=$sessionId|device=$deviceNonce"
        val epochInfo = infoString.toByteArray(Charsets.UTF_8)
        
        // Expand the PRK into a 32-byte Transport Secret
        currentTransportSecret = HKDF.expand(sessionPrk, epochInfo, 32)
        
        // Reset limits
        bytesEncryptedInEpoch = 0L
        epochStartTimeMs = System.currentTimeMillis()
    }

    /**
     * Derives specific keys for a specific channel (Multiplexing).
     * @param channelName e.g., "video", "audio", "control", "telemetry"
     * @return ChannelCryptoContext containing AES Key and Nonce Base
     */
    fun deriveChannelKeys(channelName: String): ChannelCryptoContext {
        val channelInfo = "channel_$channelName".toByteArray(Charsets.UTF_8)
        
        // Derive 32 bytes of key material from the Transport Secret
        // 16 bytes for AES-128 Key + 12 bytes for GCM Nonce Base + 4 bytes reserved
        val keyMaterial = HKDF.expand(currentTransportSecret, channelInfo, 32)

        val aesKey = ByteArray(16)
        val nonceBase = ByteArray(12)
        
        System.arraycopy(keyMaterial, 0, aesKey, 0, 16)
        System.arraycopy(keyMaterial, 16, nonceBase, 0, 12)

        return ChannelCryptoContext(
            aesKey = aesKey,
            nonceBase = nonceBase,
            epoch = currentEpoch.get()
        )
    }
}

// Immutable structure to hold the active keys for a channel
class ChannelCryptoContext(
    val aesKey: ByteArray,
    val nonceBase: ByteArray,
    val epoch: Int
) {
    // Generates a per-packet IV by XORing the Nonce Base with the Packet Sequence
    // Extremely fast, Zero GC operation in the Hot Path
    fun constructPacketIv(packetSequence: Long, outIv: ByteArray) {
        System.arraycopy(nonceBase, 0, outIv, 0, 12)
        for (i in 0..7) {
            outIv[11 - i] = (outIv[11 - i].toLong() xor (packetSequence ushr (i * 8))).toByte()
        }
    }
}
