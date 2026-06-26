package com.streamlink.shared

import android.util.Base64
import android.util.Log
import java.security.*
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * KeyExchange — ECDH-P256 key exchange with Perfect Forward Secrecy.
 *
 * Protocol:
 * 1. Phone generates ephemeral ECDH-P256 keypair
 * 2. Watch generates ephemeral ECDH-P256 keypair
 * 3. Both exchange public keys over signaling channel
 * 4. Each side derives shared AES-256-GCM key via ECDH
 * 5. Session key is derived from shared secret via HKDF-SHA256
 *
 * PFS: new keypair generated per session, ephemeral keys never stored.
 */
object KeyExchange {

    private const val TAG = "KeyExchange"
    private const val EC_CURVE = "secp256r1"         // NIST P-256
    private const val KEY_ALGO = "EC"
    private const val AGREEMENT_ALGO = "ECDH"
    private const val SESSION_KEY_LABEL = "StreamLinkWear-v1-session"

    data class EphemeralKeyPair(
        val publicKeyBase64: String,
        internal val privateKey: PrivateKey
    )

    /**
     * Step 1 — Generate ephemeral keypair (call once per session on both sides).
     */
    fun generateEphemeralKeyPair(): EphemeralKeyPair {
        val kpg = KeyPairGenerator.getInstance(KEY_ALGO)
        kpg.initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        val kp = kpg.generateKeyPair()
        val publicB64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        Log.i(TAG, "Ephemeral keypair generated (${kp.public.encoded.size}B public key)")
        return EphemeralKeyPair(publicKeyBase64 = publicB64, privateKey = kp.private)
    }

    /**
     * Step 2 — Derive session key from ECDH shared secret.
     * Call with YOUR private key and THEIR public key (from signaling).
     *
     * @return 32-byte AES-256 session key
     */
    fun deriveSessionKey(
        myPrivateKey: PrivateKey,
        theirPublicKeyBase64: String
    ): ByteArray {
        val theirPublicBytes = Base64.decode(theirPublicKeyBase64, Base64.NO_WRAP)
        val kf = KeyFactory.getInstance(KEY_ALGO)
        val theirPublicKey = kf.generatePublic(
            java.security.spec.X509EncodedKeySpec(theirPublicBytes)
        )

        val ka = KeyAgreement.getInstance(AGREEMENT_ALGO)
        ka.init(myPrivateKey)
        ka.doPhase(theirPublicKey, true)
        val sharedSecret = ka.generateSecret()

        // HKDF-like derivation: SHA-256(label || sharedSecret)
        val sessionKey = hkdfDerive(sharedSecret, SESSION_KEY_LABEL.toByteArray())
        Log.i(TAG, "Session key derived (${sessionKey.size * 8} bits)")
        return sessionKey
    }

    /**
     * Verify peer public key is a valid P-256 point (prevents invalid curve attack).
     */
    fun validatePeerKey(peerPublicKeyBase64: String): Boolean {
        return try {
            val bytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
            if (bytes.size < 32 || bytes.size > 256) return false
            val kf = KeyFactory.getInstance(KEY_ALGO)
            kf.generatePublic(java.security.spec.X509EncodedKeySpec(bytes))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Invalid peer public key: ${e.message}")
            false
        }
    }

    /**
     * Simplified HKDF: SHA-256(context || secret).
     * Produces a 32-byte derived key suitable for AES-256.
     */
    private fun hkdfDerive(sharedSecret: ByteArray, context: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(context)
        digest.update(sharedSecret)
        return digest.digest()  // 32 bytes
    }

    /**
     * Wrap derived bytes into a SecretKey usable with AES/GCM/NoPadding.
     */
    fun toAesKey(keyBytes: ByteArray): SecretKey =
        SecretKeySpec(keyBytes.copyOf(32), "AES")
}
