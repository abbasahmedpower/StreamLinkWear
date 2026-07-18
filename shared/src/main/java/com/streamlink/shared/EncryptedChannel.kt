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
import kotlin.math.min

class ReplayDetectedException(msg: String) : Exception(msg)

/**
 * EncryptedChannel — AES-256-GCM encryption for the stream.
 * Includes AAD and Monotonic Sequence checks for Replay Protection.
 * Supports partial encryption to save CPU and battery.
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
    private val sendSeq = AtomicLong(0)
    private var lastAcceptedSeq = -1L
    private val random = SecureRandom()
    private val staticNoncePrefix = ByteArray(4).also { random.nextBytes(it) }

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
    // [8 bytes Seq] [12 bytes IV/Nonce] [1 byte isKeyframe] [encrypted data/mixed] [16 bytes GCM Auth Tag]

    /** Encrypt a raw chunk entirely. Returns [Seq || Nonce || 1-byte flag || CipherText+Tag] */
    fun encrypt(plaintext: ByteArray, offset: Int = 0, length: Int = plaintext.size): ByteArray {
        val seq = sendSeq.getAndIncrement()
        val nonce = buildNonce(seq)

        val cipher = getCipher(Cipher.ENCRYPT_MODE, nonce, buildAad(seq, sendLabel))
        val ciphertext = cipher.doFinal(plaintext, offset, length)

        return ByteBuffer.allocate(8 + 12 + 1 + ciphertext.size)
            .putLong(seq)
            .put(nonce)
            .put(1.toByte()) // Always fully encrypt for this convenience method
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

    /** Decrypt a chunk received from the wire (for fully encrypted chunks allocated anew). */
    @Throws(AEADBadTagException::class, ReplayDetectedException::class)
    fun decrypt(cipherData: ByteArray): ByteArray? {
        return try {
            val buf = ByteBuffer.wrap(cipherData)
            val seq = buf.long

            if (seq <= lastAcceptedSeq) {
                throw ReplayDetectedException("Replay detected: seq=$seq <= lastAccepted=$lastAcceptedSeq")
            }

            val nonce = ByteArray(12).also { buf.get(it) }
            val isKeyframe = buf.get() == 1.toByte() // read flag

            val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }

            val cipher = getCipher(Cipher.DECRYPT_MODE, nonce, buildAad(seq, expectedRecvLabel))
            val plaintext = cipher.doFinal(ciphertext)
            // Note: This method is only used for fully encrypted small chunks, so tailLen is assumed 0 here.
            lastAcceptedSeq = seq
            plaintext
        } catch (e: Exception) {
            Log.e(tag, "Decryption failed: ${e.message}")
            null
        }
    }

    /** Zero-allocation selective decrypt. Input: ciphertext slice. Output: output buffer. Returns decrypted size. */
    @Throws(AEADBadTagException::class, ReplayDetectedException::class)
    fun decrypt(ciphertext: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int): Int {
        val buf = ByteBuffer.wrap(ciphertext, offset, length)
        val seq = buf.long

        if (seq <= lastAcceptedSeq) {
            throw ReplayDetectedException("Replay detected: seq=$seq <= lastAccepted=$lastAcceptedSeq")
        }

        val nonce = ByteArray(12)
        buf.get(nonce)
        val isKeyframe = buf.get() == 1.toByte()

        val cipher = getCipher(Cipher.DECRYPT_MODE, nonce, buildAad(seq, expectedRecvLabel))

        val headerLen = 21
        val encryptedPartLen = length - headerLen
        
        val decLen = cipher.doFinal(ciphertext, offset + headerLen, encryptedPartLen, output, outputOffset)
        
        lastAcceptedSeq = seq
        return decLen
    }

    private fun buildAad(seq: Long, label: String): ByteArray {
        return "$sessionId|$label|$seq".toByteArray(Charsets.UTF_8)
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
