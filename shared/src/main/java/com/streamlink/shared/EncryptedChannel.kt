package com.streamlink.shared

import android.util.Log
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.AEADBadTagException

class ReplayDetectedException(msg: String) : Exception(msg)

/**
 * EncryptedChannel — AES-256-GCM encryption for the H.264 stream.
 * Includes AAD and Monotonic Sequence checks for Replay Protection.
 */
class EncryptedChannel(
    sessionKey: ByteArray,
    private val sessionId: String,
    private val direction: String
) {
    private val tag = "EncryptedChannel"

    init {
        require(sessionKey.size == 32) { "Session key must be 256-bit (32 bytes)" }
    }

    private val key: SecretKey = SecretKeySpec(sessionKey, "AES")
    private val sendSeq = AtomicLong(0)
    private var lastAcceptedSeq = -1L
    private val random = SecureRandom()
    private val staticNoncePrefix = ByteArray(4).also { random.nextBytes(it) }

    // Wire format per chunk:
    // [8 bytes Seq] [12 bytes IV/Nonce] [encrypted data] [16 bytes GCM Auth Tag]

    /** Encrypt a raw H.264 chunk. Returns [Seq || Nonce || CipherText+Tag] */
    fun encrypt(plaintext: ByteArray, offset: Int = 0, length: Int = plaintext.size): ByteArray {
        val seq = sendSeq.getAndIncrement()
        val nonce = buildNonce(seq)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        
        val aad = buildAad(seq)
        cipher.updateAAD(aad)

        val ciphertext = cipher.doFinal(plaintext, offset, length)

        return ByteBuffer.allocate(8 + 12 + ciphertext.size)
            .putLong(seq)
            .put(nonce)
            .put(ciphertext)
            .array()
    }

    /** Zero-allocation encrypt to target buffer. Returns total size written. */
    fun encrypt(plaintext: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int): Int {
        val seq = sendSeq.getAndIncrement()
        val nonce = buildNonce(seq)
        
        // Write Seq (8 bytes)
        val outBuf = ByteBuffer.wrap(output, outputOffset, output.size - outputOffset)
        outBuf.putLong(seq)
        
        // Write Nonce (12 bytes)
        outBuf.put(nonce)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(buildAad(seq))
        
        val encLen = cipher.doFinal(plaintext, offset, length, output, outputOffset + 20)
        return 20 + encLen
    }

    /** Decrypt a chunk received from the wire. Input: [Seq(8) || IV(12) || CipherText+Tag] */
    @Throws(AEADBadTagException::class, ReplayDetectedException::class)
    fun decrypt(cipherData: ByteArray): ByteArray? {
        return try {
            val buf = ByteBuffer.wrap(cipherData)
            val seq = buf.long

            if (seq <= lastAcceptedSeq) {
                throw ReplayDetectedException("Replay detected: seq=$seq <= lastAccepted=$lastAcceptedSeq")
            }

            val nonce = ByteArray(12).also { buf.get(it) }
            val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(buildAad(seq))

            val plaintext = cipher.doFinal(ciphertext)
            lastAcceptedSeq = seq
            plaintext
        } catch (e: Exception) {
            Log.e(tag, "Decryption failed: ${e.message}")
            null
        }
    }

    /** Zero-allocation decrypt. Input: ciphertext slice. Output: output buffer. Returns decrypted size. */
    @Throws(AEADBadTagException::class, ReplayDetectedException::class)
    fun decrypt(ciphertext: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int): Int {
        val buf = ByteBuffer.wrap(ciphertext, offset, length)
        val seq = buf.long

        if (seq <= lastAcceptedSeq) {
            throw ReplayDetectedException("Replay detected: seq=$seq <= lastAccepted=$lastAcceptedSeq")
        }

        val nonce = ByteArray(12)
        buf.get(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(buildAad(seq))
        
        val decLen = cipher.doFinal(ciphertext, offset + 20, length - 20, output, outputOffset)
        lastAcceptedSeq = seq
        return decLen
    }

    private fun buildAad(seq: Long): ByteArray {
        return "$sessionId|$direction|$seq".toByteArray(Charsets.UTF_8)
    }

    private fun buildNonce(seq: Long): ByteArray {
        return ByteBuffer.allocate(12).put(staticNoncePrefix).putLong(seq).array()
    }

    companion object {
        fun generateKey(): ByteArray {
            val key = ByteArray(32)
            SecureRandom().nextBytes(key)
            return key
        }
    }
}
