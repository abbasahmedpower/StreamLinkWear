package com.streamlink.shared.deprecated

import java.nio.ByteBuffer
import java.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.streamlink.shared.StreamProtocol

object SecurityManager {

    private val secureRandom = SecureRandom()

    // ✅ FIX: Bounded nonce cache with automatic eviction
    private val nonceCache = ConcurrentHashMap<String, Long>(512)
    private val nonceCacheInserts = AtomicLong(0L)
    private const val NONCE_EVICT_INTERVAL = 100L   // evict every 100 inserts
    private const val NONCE_MAX_SIZE = 1024           // hard cap — never exceed

    private val seqWindow = ConcurrentHashMap<Int, Long>(256)
    private val keyRef = AtomicReference<ByteArray?>(null)

    private const val AAD_VERSION: Byte = 1
    private const val AAD_CONTROL_MESSAGE: Byte = 1
    private const val STRING_AAD_BYTES = 6  // version(1) | type(1) | sequence(4)
    private const val BINARY_AAD_BYTES = 10 // version(1) | type(1) | sequence(8)

    fun generateAndStoreKey(): ByteArray {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(StreamProtocol.AES_KEY_BITS, secureRandom)
        val key = kg.generateKey().encoded
        keyRef.set(key)
        return key
    }

    fun setKey(keyBytes: ByteArray) {
        keyRef.set(keyBytes.copyOf())
    }

    fun encrypt(
        data: ByteArray,
        key: ByteArray,
        timestamp: Long = System.currentTimeMillis(),
        frameType: Byte = StreamProtocol.PAYLOAD_TYPE_VIDEO_H264,
        sequenceNumber: Long = timestamp
    ): ByteArray {
        val iv = ByteArray(StreamProtocol.GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val aad = buildBinaryAad(frameType, sequenceNumber)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(StreamProtocol.GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(data)
        
        // Return raw IV + authenticated metadata + ciphertext (no Base64 for binary efficiency)
        return iv + aad + ciphertext
    }

    fun encrypt(payload: String, keyBytes: ByteArray, sequenceNumber: Int = 0): String {
        val timestamp = System.currentTimeMillis()
        val iv = ByteArray(StreamProtocol.GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val aad = buildMessageAad(AAD_CONTROL_MESSAGE, sequenceNumber)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(StreamProtocol.GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(
            "$payload|TS|$timestamp|SEQ|$sequenceNumber".toByteArray(Charsets.UTF_8)
        )
        return Base64.getEncoder().encodeToString(iv + aad + ciphertext)
    }

    fun decrypt(encoded: String, keyBytes: ByteArray): String? {
        if (encoded.isEmpty()) return null
        return try {
            val combined = java.util.Base64.getDecoder().decode(encoded)
            if (combined.size <= StreamProtocol.GCM_IV_BYTES + STRING_AAD_BYTES) return null

            val iv = combined.copyOfRange(0, StreamProtocol.GCM_IV_BYTES)
            val aad = combined.copyOfRange(
                StreamProtocol.GCM_IV_BYTES,
                StreamProtocol.GCM_IV_BYTES + STRING_AAD_BYTES
            )
            if (aad[0] != AAD_VERSION) return null

            val authenticatedSequence = ByteBuffer.wrap(aad, 2, Int.SIZE_BYTES).int
            val ciphertext = combined.copyOfRange(
                StreamProtocol.GCM_IV_BYTES + STRING_AAD_BYTES,
                combined.size
            )
            val ivKey = sha256Key(iv, authenticatedSequence)
            val now = System.currentTimeMillis()

            evictNonceCache(now)

            if (nonceCache.size >= NONCE_MAX_SIZE) {
                android.util.Log.e("SecurityManager", "Nonce cache at hard cap — dropping packet")
                return null
            }

            if (nonceCache.putIfAbsent(ivKey, now) != null) {
                android.util.Log.w("SecurityManager", "Replay: duplicate nonce")
                return null
            }
            
            nonceCacheInserts.incrementAndGet()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(StreamProtocol.GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(aad)
            val plaintext = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            val parts = plaintext.split("|TS|")
            if (parts.size < 2) return null

            val url = parts[0]
            val rest = parts[1].split("|SEQ|")
            val ts = rest[0].toLongOrNull() ?: return null
            val seq = rest.getOrNull(1)?.toIntOrNull() ?: 0
            if (seq != authenticatedSequence) {
                android.util.Log.w("SecurityManager", "Authenticated sequence mismatch")
                return null
            }

            if (now - ts > StreamProtocol.TOKEN_VALIDITY_MS) {
                android.util.Log.w("SecurityManager", "Expired token (age=${now - ts}ms)")
                return null
            }

            seqWindow.entries.removeIf { now - it.value > StreamProtocol.TOKEN_VALIDITY_MS }
            if (seqWindow.putIfAbsent(seq, now) != null) {
                android.util.Log.w("SecurityManager", "Seq replay: $seq")
                return null
            }
            
            url
        } catch (e: Exception) {
            android.util.Log.w("SecurityManager", "Decrypt failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * ✅ FIX: Eviction strategy:
     * 1. Every NONCE_EVICT_INTERVAL inserts → sweep expired entries
     * 2. If still too large → evict oldest 25%
     */
    private fun evictNonceCache(now: Long) {
        val insertCount = nonceCacheInserts.get()
        if (insertCount % NONCE_EVICT_INTERVAL != 0L && nonceCache.size < NONCE_MAX_SIZE / 2) return

        // Remove expired entries
        nonceCache.entries.removeIf { now - it.value > StreamProtocol.TOKEN_VALIDITY_MS }

        // If still too large, force-evict oldest 25%
        if (nonceCache.size >= NONCE_MAX_SIZE * 3 / 4) {
            val evictCount = NONCE_MAX_SIZE / 4
            var count = 0
            val iter = nonceCache.entries.iterator()
            while (iter.hasNext() && count < evictCount) {
                iter.next(); iter.remove(); count++
            }
            android.util.Log.d("SecurityManager", "Force-evicted $count nonces")
        }
    }

    fun isDomainAllowed(url: String): Boolean {
        return try {
            val host = android.net.Uri.parse(url).host ?: return false
            StreamProtocol.ALLOWED_DOMAINS.any { domain -> host == domain || host.endsWith(".$domain") }
        } catch (_: Exception) { false }
    }

    private fun sha256Key(iv: ByteArray, sequenceNumber: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(iv)
        digest.update(sequenceNumber.toString().toByteArray())
        return java.util.Base64.getEncoder().withoutPadding().encodeToString(digest.digest())
    }

    private fun buildMessageAad(frameType: Byte, sequenceNumber: Int): ByteArray =
        ByteBuffer.allocate(STRING_AAD_BYTES)
            .put(AAD_VERSION)
            .put(frameType)
            .putInt(sequenceNumber)
            .array()

    private fun buildBinaryAad(frameType: Byte, sequenceNumber: Long): ByteArray =
        ByteBuffer.allocate(BINARY_AAD_BYTES)
            .put(AAD_VERSION)
            .put(frameType)
            .putLong(sequenceNumber)
            .array()

    // Monitoring
    fun nonceCacheSize(): Int = nonceCache.size
    fun totalNonceInserts(): Long = nonceCacheInserts.get()

    // For Unit Tests Only
    fun resetForTest() {
        nonceCache.clear()
        nonceCacheInserts.set(0L)
        seqWindow.clear()
    }
}
