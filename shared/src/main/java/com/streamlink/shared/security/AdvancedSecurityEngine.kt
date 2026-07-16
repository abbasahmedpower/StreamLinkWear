package com.streamlink.shared.security

import android.util.Log
import com.streamlink.shared.StreamProtocol
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class EncryptionMode {
    FULL,       // AES-256-GCM (Heavy, secure)
    PARTIAL,    // AES-GCM on first N bytes only
    SIGNED_ONLY,// HMAC-SHA256 (Lightweight, verifiable, no confidentiality)
    NONE        // Raw
}

/**
 * AdvancedSecurityEngine — The Unified Security Module.
 * Merges the best parts of SelectiveSecurityEngine (Partial encryption to save CPU)
 * and SecurityManager (Nonce cache and Replay Attack protection),
 * but executes IN-PLACE without expensive Base64 string allocations.
 */
class AdvancedSecurityEngine(private val sessionKey: ByteArray) {
    private val tag = "AdvancedSecurityEngine"
    private val secureRandom = SecureRandom()
    
    // Anti-replay protection (from SecurityManager)
    private val nonceCache = ConcurrentHashMap<String, Long>(512)
    private val nonceCacheInserts = AtomicLong(0L)
    
    companion object {
        const val PARTIAL_ENCRYPT_BYTES = 1024
        private const val NONCE_EVICT_INTERVAL = 100L
        private const val NONCE_MAX_SIZE = 1024
        private const val AAD_VERSION: Byte = 1
        private const val BINARY_AAD_BYTES = 10 // version(1) | type(1) | sequence(8)
    }

    /**
     * Encrypts the wire payload IN-PLACE or into a new buffer, depending on mode.
     * For FULL mode, we encrypt the whole payload.
     * For PARTIAL mode, we encrypt only the first N bytes.
     */
    fun secureFrame(
        payload: ByteArray, 
        offset: Int, 
        length: Int, 
        outBuffer: ByteArray, 
        outOffset: Int, 
        isKeyframe: Boolean,
        frameType: Byte,
        sequenceNumber: Long
    ): Int {
        val mode = if (isKeyframe) EncryptionMode.FULL else EncryptionMode.PARTIAL
        val iv = ByteArray(StreamProtocol.GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val aad = buildBinaryAad(frameType, sequenceNumber)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(StreamProtocol.GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(aad)
        
        // Output format: [IV] + [AAD] + [Ciphertext] + [Unencrypted Tail (for Partial)]
        System.arraycopy(iv, 0, outBuffer, outOffset, iv.size)
        System.arraycopy(aad, 0, outBuffer, outOffset + iv.size, aad.size)
        
        val headerSize = iv.size + aad.size
        
        return when (mode) {
            EncryptionMode.FULL -> {
                val cipherLen = cipher.doFinal(payload, offset, length, outBuffer, outOffset + headerSize)
                headerSize + cipherLen
            }
            EncryptionMode.PARTIAL -> {
                val targetSize = minOf(length, PARTIAL_ENCRYPT_BYTES)
                val cipherLen = cipher.doFinal(payload, offset, targetSize, outBuffer, outOffset + headerSize)
                
                val unencryptedTailSize = length - targetSize
                if (unencryptedTailSize > 0) {
                    System.arraycopy(payload, offset + targetSize, outBuffer, outOffset + headerSize + cipherLen, unencryptedTailSize)
                }
                headerSize + cipherLen + unencryptedTailSize
            }
            else -> {
                System.arraycopy(payload, offset, outBuffer, outOffset + headerSize, length)
                headerSize + length
            }
        }
    }
    
    fun decryptFrame(
        payload: ByteArray,
        offset: Int,
        length: Int,
        outBuffer: ByteArray,
        outOffset: Int,
        isKeyframe: Boolean
    ): Int {
        if (length <= StreamProtocol.GCM_IV_BYTES + BINARY_AAD_BYTES) return -1
        
        val mode = if (isKeyframe) EncryptionMode.FULL else EncryptionMode.PARTIAL
        
        val iv = payload.copyOfRange(offset, offset + StreamProtocol.GCM_IV_BYTES)
        val aad = payload.copyOfRange(offset + StreamProtocol.GCM_IV_BYTES, offset + StreamProtocol.GCM_IV_BYTES + BINARY_AAD_BYTES)
        
        if (aad[0] != AAD_VERSION) return -1
        val sequenceNumber = ByteBuffer.wrap(aad, 2, Long.SIZE_BYTES).long
        
        val now = System.currentTimeMillis()
        evictNonceCache(now)
        
        val ivKey = sha256Key(iv, sequenceNumber)
        if (nonceCache.putIfAbsent(ivKey, now) != null) {
            Log.w(tag, "Replay attack detected: duplicate nonce")
            return -1
        }
        nonceCacheInserts.incrementAndGet()
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(StreamProtocol.GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(aad)
        
        val headerSize = StreamProtocol.GCM_IV_BYTES + BINARY_AAD_BYTES
        val encryptedLength = length - headerSize
        
        return try {
            when (mode) {
                EncryptionMode.FULL -> {
                    cipher.doFinal(payload, offset + headerSize, encryptedLength, outBuffer, outOffset)
                }
                EncryptionMode.PARTIAL -> {
                    val tagLen = StreamProtocol.GCM_TAG_BITS / 8
                    val targetSize = minOf(encryptedLength - tagLen, PARTIAL_ENCRYPT_BYTES)
                    
                    val cipherLen = cipher.doFinal(payload, offset + headerSize, targetSize + tagLen, outBuffer, outOffset)
                    
                    val unencryptedTailSize = encryptedLength - (targetSize + tagLen)
                    if (unencryptedTailSize > 0) {
                        System.arraycopy(payload, offset + headerSize + targetSize + tagLen, outBuffer, outOffset + cipherLen, unencryptedTailSize)
                    }
                    cipherLen + unencryptedTailSize
                }
                else -> {
                    System.arraycopy(payload, offset + headerSize, outBuffer, outOffset, encryptedLength)
                    encryptedLength
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Decrypt failed: ${e.message}")
            -1
        }
    }

    private fun evictNonceCache(now: Long) {
        val insertCount = nonceCacheInserts.get()
        if (insertCount % NONCE_EVICT_INTERVAL != 0L && nonceCache.size < NONCE_MAX_SIZE / 2) return

        nonceCache.entries.removeIf { now - it.value > StreamProtocol.TOKEN_VALIDITY_MS }

        if (nonceCache.size >= NONCE_MAX_SIZE * 3 / 4) {
            val evictCount = NONCE_MAX_SIZE / 4
            var count = 0
            val iter = nonceCache.entries.iterator()
            while (iter.hasNext() && count < evictCount) {
                iter.next(); iter.remove(); count++
            }
        }
    }

    private fun sha256Key(iv: ByteArray, sequenceNumber: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(iv)
        digest.update(sequenceNumber.toString().toByteArray())
        return java.util.Base64.getEncoder().withoutPadding().encodeToString(digest.digest())
    }

    private fun buildBinaryAad(frameType: Byte, sequenceNumber: Long): ByteArray =
        ByteBuffer.allocate(BINARY_AAD_BYTES)
            .put(AAD_VERSION)
            .put(frameType)
            .putLong(sequenceNumber)
            .array()
}
