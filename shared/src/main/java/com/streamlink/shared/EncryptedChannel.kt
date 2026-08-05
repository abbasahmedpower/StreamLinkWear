package com.streamlink.shared

import android.util.Log
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.AEADBadTagException

class ReplayDetectedException(msg: String) : Exception(msg)

/**
 * EncryptedChannel — AES-256-GCM encryption for the stream.
 * Includes AAD and Sliding-Window Replay Protection.
 *
 * Security properties:
 *  - Replay window: 1024-packet circular bitset (zero-GC, O(1) check/mark, same algorithm as DTLS/IPsec).
 *  - Nonce: 4 random bytes (static per channel) || 8-byte full seq — no truncation, no 2³²-cycle risk.
 *  - markAccepted() is called AFTER auth-tag verification to prevent "seq burning" attacks.
 *  - Both decrypt() overloads share the same isReplay/markAccepted pair for consistent behavior.
 *  - All rejected packets are logged for observability (timing-safe: rejection happens regardless).
 *
 * Replay window implementation (DTLS-style circular bitset):
 *  - WINDOW_SIZE = 1024 (power of 2 for fast modulo via bitwise AND).
 *  - seenBits: LongArray(16) = 16×64 = 1024 bits. Zero heap allocations on hot path.
 *  - Position: seq & (WINDOW_SIZE-1) — wraps circularly.
 *  - On window advance: only the bits that just "fell out" are cleared, not the whole array.
 */
class EncryptedChannel(
    sessionKey: ByteArray,
    private val sessionId: String,
    private val sendLabel: String,
    private val expectedRecvLabel: String
) {
    private val tag = "EncryptedChannel"

    init {
        require(sessionKey.size == 32) { "Session key must be 256-bit (32 bytes)" }
    }

    private val key: SecretKey = SecretKeySpec(sessionKey, "AES")
    private val sendSeq = java.util.concurrent.atomic.AtomicLong(0)
    private val random = SecureRandom()
    // ✅ 6.1: 4 random bytes || 8-byte full seq = 12-byte nonce (no sessionCounter needed)
    private val staticNoncePrefix = ByteArray(4).also { random.nextBytes(it) }

    // ✅ 4.1: DTLS-style circular bitset — zero heap allocations, O(1) operations
    // 1024 bits = 16 × 64-bit longs. Position = seq & (WINDOW_SIZE - 1).
    private val replayLock = Any()
    private var highestSeq = -1L
    private val seenBits = LongArray(WINDOW_LONGS) // 16 longs = 1024 bits, all zero initially

    /**
     * Read-only replay check — call BEFORE decryption.
     * Rejects packets that are too old (below window floor) or exact duplicates within the window.
     */
    private fun isReplay(seq: Long): Boolean = synchronized(replayLock) {
        if (seq <= highestSeq - WINDOW_SIZE) return@synchronized true
        val pos = (seq and WINDOW_MASK).toInt()
        (seenBits[pos ushr 6] and (1L shl (pos and 63))) != 0L
    }

    /**
     * Mark a seq as accepted — call ONLY after the auth tag is verified successfully.
     *
     * Window advance algorithm (same as DTLS RFC 6347 §4.1.2.6):
     *  1. If seq > highestSeq: advance the window, clearing only the bits that fell out.
     *  2. Set the bit for this seq.
     *
     * Calling this before verification would allow an attacker to "burn" a legitimate
     * seq with a forged packet, causing the genuine packet to be rejected as a replay.
     */
    private fun markAccepted(seq: Long) = synchronized(replayLock) {
        if (seq > highestSeq) {
            val advance = seq - highestSeq
            if (advance >= WINDOW_SIZE) {
                // Window fully rotated — clear all bits
                seenBits.fill(0L)
            } else {
                // Clear only the bits that just fell out of the window.
                // Those are the bit positions for seqs [highestSeq - WINDOW_SIZE + 1 .. seq - WINDOW_SIZE].
                for (i in 0 until advance) {
                    val evictedSeq = highestSeq - WINDOW_SIZE + 1 + i
                    val pos = (evictedSeq and WINDOW_MASK).toInt()
                    seenBits[pos ushr 6] = seenBits[pos ushr 6] and (1L shl (pos and 63)).inv()
                }
            }
            highestSeq = seq
        }
        // Set the bit for this accepted seq
        val pos = (seq and WINDOW_MASK).toInt()
        seenBits[pos ushr 6] = seenBits[pos ushr 6] or (1L shl (pos and 63))
    }

    private val cipherThreadLocal = object : ThreadLocal<Cipher>() {
        override fun initialValue(): Cipher {
            return Cipher.getInstance("AES/GCM/NoPadding")
        }
    }

    private fun getCipher(mode: Int, nonce: ByteArray, aad: ByteArray): Cipher {
        val cipher = cipherThreadLocal.get()!!
        cipher.init(mode, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher
    }

    // Wire format per chunk:
    // [8 bytes Seq] [12 bytes IV/Nonce] [1 byte isKeyframe] [encrypted data] [16 bytes GCM Auth Tag]

    /** Encrypt a raw chunk entirely. Returns [Seq || Nonce || 1-byte flag || CipherText+Tag] */
    fun encrypt(plaintext: ByteArray, offset: Int = 0, length: Int = plaintext.size): ByteArray {
        val seq = sendSeq.getAndIncrement()
        val nonce = buildNonce(seq)
        val cipher = getCipher(Cipher.ENCRYPT_MODE, nonce, buildAad(seq, sendLabel))
        val ciphertext = cipher.doFinal(plaintext, offset, length)
        return ByteBuffer.allocate(8 + 12 + 1 + ciphertext.size)
            .putLong(seq)
            .put(nonce)
            .put(1.toByte())
            .put(ciphertext)
            .array()
    }

    /** Zero-allocation encrypt to target buffer entirely. Returns total size written. */
    fun encrypt(plaintext: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int): Int {
        return encryptSelective(plaintext, offset, length, output, outputOffset, true)
    }

    /** Selective encryption to target buffer. Returns total size written. */
    fun encryptSelective(plaintext: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int, isKeyframe: Boolean): Int {
        val seq = sendSeq.getAndIncrement()
        val nonce = buildNonce(seq)
        val outBuf = ByteBuffer.wrap(output, outputOffset, output.size - outputOffset)
        outBuf.putLong(seq)
        outBuf.put(nonce)
        outBuf.put(if (isKeyframe) 1.toByte() else 0.toByte())
        val cipher = getCipher(Cipher.ENCRYPT_MODE, nonce, buildAad(seq, sendLabel))
        val encLen = cipher.doFinal(plaintext, offset, length, output, outputOffset + 21)
        return 21 + encLen
    }

    /**
     * ✅ 4.4: Decrypt a chunk received from the wire (allocating overload).
     *
     * Flow: isReplay (read-only) → doFinal (auth-tag check) → markAccepted
     * Returns null on replay, auth failure, or any parse error — never throws.
     */
    fun decrypt(cipherData: ByteArray): ByteArray? {
        return try {
            val buf = ByteBuffer.wrap(cipherData)
            val seq = buf.long

            // ✅ 4.1: Check replay BEFORE decryption (read-only — does NOT burn the seq)
            if (isReplay(seq)) {
                Log.w(tag, "🚫 Rejected replay/out-of-window seq=$seq (highest=$highestSeq)")
                return null
            }

            val nonce = ByteArray(12).also { buf.get(it) }
            @Suppress("UNUSED_VARIABLE")
            val isKeyframe = buf.get() == 1.toByte()
            val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }

            val cipher = getCipher(Cipher.DECRYPT_MODE, nonce, buildAad(seq, expectedRecvLabel))
            val plaintext = cipher.doFinal(ciphertext) // throws AEADBadTagException on tamper

            // ✅ 4.1: Mark AFTER successful auth-tag verification only
            markAccepted(seq)
            plaintext
        } catch (e: AEADBadTagException) {
            Log.w(tag, "🚫 Auth tag mismatch — possible tampering or data corruption")
            null
        } catch (e: Exception) {
            Log.w(tag, "🚫 Decrypt failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * ✅ 4.4: Zero-allocation selective decrypt (hot path).
     *
     * Same security guarantees as the allocating overload:
     * isReplay → doFinal (auth check) → markAccepted.
     */
    @Throws(AEADBadTagException::class, ReplayDetectedException::class)
    fun decrypt(ciphertext: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int): Int {
        val buf = ByteBuffer.wrap(ciphertext, offset, length)
        val seq = buf.long

        // ✅ 4.1: Check replay BEFORE decryption
        if (isReplay(seq)) {
            Log.w(tag, "🚫 Rejected replay/out-of-window seq=$seq (highest=$highestSeq)")
            throw ReplayDetectedException("Replay detected: seq=$seq")
        }

        val nonce = ByteArray(12)
        buf.get(nonce)
        @Suppress("UNUSED_VARIABLE")
        val isKeyframe = buf.get() == 1.toByte()

        val cipher = getCipher(Cipher.DECRYPT_MODE, nonce, buildAad(seq, expectedRecvLabel))
        val headerLen = 21
        val encryptedPartLen = length - headerLen

        return try {
            val decLen = cipher.doFinal(ciphertext, offset + headerLen, encryptedPartLen, output, outputOffset)
            // ✅ 4.1: Mark AFTER successful auth-tag verification only
            markAccepted(seq)
            decLen
        } catch (e: AEADBadTagException) {
            Log.w(tag, "🚫 Auth tag mismatch on zero-alloc path — possible tampering or corruption")
            throw e
        }
    }

    private fun buildAad(seq: Long, label: String): ByteArray {
        return "$sessionId|$label|$seq".toByteArray(Charsets.UTF_8)
    }

    /**
     * ✅ 6.1: 4-byte random static prefix || 8-byte full seq = 12-byte nonce.
     * Using the full Long prevents nonce reuse up to 2^64 messages.
     */
    private fun buildNonce(seq: Long): ByteArray {
        return ByteBuffer.allocate(12)
            .put(staticNoncePrefix)
            .putLong(seq)
            .array()
    }

    companion object {
        /** Sliding replay window size — must be a power of 2 for bitwise-AND modulo. */
        const val WINDOW_SIZE = 1024L
        private const val WINDOW_MASK = WINDOW_SIZE - 1          // 0x3FF — for fast seq % WINDOW_SIZE
        private const val WINDOW_LONGS = (WINDOW_SIZE / 64).toInt() // 16 longs = 1024 bits

        fun generateKey(): ByteArray {
            val key = ByteArray(32)
            SecureRandom().nextBytes(key)
            return key
        }
    }
}
