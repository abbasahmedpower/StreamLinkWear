package com.streamlink.app.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Lightweight, dependency-free implementation of HKDF (RFC 5869) using HmacSHA256.
 */
object HKDF {
    private const val HMAC_ALGO = "HmacSHA256"
    private const val HASH_LENGTH = 32 // 256 bits for SHA-256

    // Step 1: Extract (Concentrates entropy from the Master Secret)
    fun extract(salt: ByteArray?, inputKeyingMaterial: ByteArray): ByteArray {
        val actualSalt = salt ?: ByteArray(HASH_LENGTH) { 0 }
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(actualSalt, HMAC_ALGO))
        return mac.doFinal(inputKeyingMaterial)
    }

    // Step 2: Expand (Generates keys of desired length based on context info)
    fun expand(pseudoRandomKey: ByteArray, info: ByteArray, outLength: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(pseudoRandomKey, HMAC_ALGO))
        
        val result = ByteArray(outLength)
        var generatedBytes = 0
        var block = ByteArray(0)
        var iteration = 1

        while (generatedBytes < outLength) {
            mac.update(block)
            mac.update(info)
            mac.update(iteration.toByte())
            block = mac.doFinal()
            
            val bytesToCopy = minOf(block.size, outLength - generatedBytes)
            System.arraycopy(block, 0, result, generatedBytes, bytesToCopy)
            generatedBytes += bytesToCopy
            iteration++
        }
        return result
    }
}
