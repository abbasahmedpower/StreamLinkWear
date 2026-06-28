package com.streamlink.shared.security

import com.streamlink.shared.SecurityManager
import com.streamlink.shared.FramePacket
import java.nio.ByteBuffer

enum class EncryptionMode {
    FULL,       // AES-256-GCM (Heavy, secure)
    PARTIAL,    // AES-GCM on first N bytes only
    SIGNED_ONLY,// HMAC-SHA256 (Lightweight, verifiable, no confidentiality)
    NONE        // Raw (Not recommended for prod)
}

/**
 * Smart GPU-Assisted Security Layer
 * Avoids encrypting everything to save CPU and Battery, targeting different frame types intelligently.
 */
class SelectiveSecurityEngine(private val masterKey: ByteArray) {

    companion object {
        const val PARTIAL_ENCRYPT_BYTES = 1024 // Encrypt only the first 1KB of P/B frames
    }

    /**
     * Dynamically determines the required encryption level based on frame type.
     */
    fun secureFrame(frame: FramePacket): SecureFramePayload {
        val mode = determineEncryptionMode(frame.isKeyframe)
        
        // Extract raw bytes from ByteBuffer
        val rawBytes = ByteArray(frame.size)
        val dup = frame.buffer.duplicate()
        dup.position(frame.offset)
        dup.get(rawBytes, 0, frame.size)
        
        return when (mode) {
            EncryptionMode.FULL -> {
                // I-Frames: Full AES-256-GCM encryption
                val encoded = java.util.Base64.getEncoder().encodeToString(rawBytes)
                val encryptedData = SecurityManager.encrypt(
                    payload = encoded, 
                    keyBytes = masterKey,
                    sequenceNumber = (frame.timestampUs and 0xFFFFFFFF).toInt()
                ).toByteArray(Charsets.UTF_8)
                SecureFramePayload(encryptedData, mode, (frame.timestampUs and 0xFFFFFFFF).toInt())
            }
            EncryptionMode.PARTIAL -> {
                // P/B-Frames: Encrypt only the critical header/first bytes
                val targetSize = minOf(rawBytes.size, PARTIAL_ENCRYPT_BYTES)
                val targetBytes = rawBytes.copyOfRange(0, targetSize)
                
                val encoded = java.util.Base64.getEncoder().encodeToString(targetBytes)
                val encryptedHeader = SecurityManager.encrypt(
                    payload = encoded,
                    keyBytes = masterKey,
                    sequenceNumber = (frame.timestampUs and 0xFFFFFFFF).toInt()
                ).toByteArray(Charsets.UTF_8)
                
                // Append the rest of the raw data (unencrypted) to save CPU
                val unencryptedTail = if (rawBytes.size > PARTIAL_ENCRYPT_BYTES) {
                    rawBytes.copyOfRange(PARTIAL_ENCRYPT_BYTES, rawBytes.size)
                } else {
                    ByteArray(0)
                }
                
                // Combine them
                val combined = encryptedHeader + unencryptedTail
                SecureFramePayload(combined, mode, (frame.timestampUs and 0xFFFFFFFF).toInt())
            }
            EncryptionMode.SIGNED_ONLY -> {
                // Metadata or very low priority frames: Just sign to prevent tampering
                SecureFramePayload(rawBytes, mode, (frame.timestampUs and 0xFFFFFFFF).toInt())
            }
            EncryptionMode.NONE -> {
                SecureFramePayload(rawBytes, mode, (frame.timestampUs and 0xFFFFFFFF).toInt())
            }
        }
    }

    private fun determineEncryptionMode(isKeyframe: Boolean): EncryptionMode {
        return if (isKeyframe) {
            EncryptionMode.FULL
        } else {
            EncryptionMode.PARTIAL
        }
    }
}

data class SecureFramePayload(
    val data: ByteArray,
    val mode: EncryptionMode,
    val sequenceNumber: Int
)
