package com.streamlink.shared

import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * EncryptedChannel — AES-256-GCM encryption for the H.264 stream.
 *
 * Every wire chunk is encrypted before being sent via DirectSocketServer,
 * and decrypted after being received by DirectSocketClient.
 *
 * Wire format per chunk:
 *   [12 bytes IV] [encrypted data] [16 bytes GCM Auth Tag (appended by JCE)]
 */
class EncryptedChannel(private val sessionKey: ByteArray) {
    private val tag = "EncryptedChannel"

    init {
        require(sessionKey.size == 32) { "Session key must be 256-bit (32 bytes)" }
    }

    private val secretKey: SecretKey = SecretKeySpec(sessionKey, "AES")
    private val random = SecureRandom()

    /** Encrypt a raw H.264 chunk. Returns [IV || CipherText+Tag] */
    fun encrypt(plaintext: ByteArray, offset: Int = 0, length: Int = plaintext.size): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext, offset, length)

        return iv + ciphertext  // 12 + len + 16
    }

    /** Zero-allocation encrypt to target buffer. Returns total size written. */
    fun encrypt(plaintext: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int): Int {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        System.arraycopy(iv, 0, output, outputOffset, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encLen = cipher.doFinal(plaintext, offset, length, output, outputOffset + 12)
        return 12 + encLen
    }

    /** Decrypt a chunk received from the wire. Input: [IV(12) || CipherText+Tag] */
    fun decrypt(cipherData: ByteArray): ByteArray? {
        return try {
            val iv = cipherData.copyOfRange(0, 12)
            val ciphertext = cipherData.copyOfRange(12, cipherData.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(tag, "Decryption failed: ${e.message}")
            null
        }
    }

    /** Zero-allocation decrypt. Input: ciphertext slice. Output: output buffer. Returns decrypted size. */
    fun decrypt(ciphertext: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int): Int {
        val iv = ByteArray(12)
        System.arraycopy(ciphertext, offset, iv, 0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext, offset + 12, length - 12, output, outputOffset)
    }

    /** Generate a new random 256-bit session key */
    companion object {
        fun generateKey(): ByteArray {
            val key = ByteArray(32)
            SecureRandom().nextBytes(key)
            return key
        }
    }
}
