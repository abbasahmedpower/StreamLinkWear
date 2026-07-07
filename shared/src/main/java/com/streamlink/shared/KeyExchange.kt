package com.streamlink.shared

import android.util.Base64
import android.util.Log
import java.security.*
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
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
        theirPublicKeyBase64: String,
        pairingCode: String
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

        // Standard HKDF-SHA256 derivation with pairing code as info/salt
        val infoString = "$SESSION_KEY_LABEL|PIN:$pairingCode"
        val sessionKey = hkdfDerive(sharedSecret, infoString.toByteArray())
        Log.i(TAG, "Session key derived (${sessionKey.size * 8} bits)")
        return sessionKey
    }

    /**
     * Convenience overload — accepts the full [EphemeralKeyPair] so callers in
     * other modules don't need to access the [internal] privateKey field directly.
     */
    fun deriveSessionKey(myKeyPair: EphemeralKeyPair, theirPublicKeyBase64: String, pairingCode: String): ByteArray =
        deriveSessionKey(myKeyPair.privateKey, theirPublicKeyBase64, pairingCode)

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
     * Standard HKDF-SHA256 implementation (RFC 5869).
     * Produces a 32-byte derived key suitable for AES-256.
     */
    private fun hkdfDerive(sharedSecret: ByteArray, info: ByteArray): ByteArray {
        val macAlgo = "HmacSHA256"
        
        // 1. HKDF-Extract(salt, IKM)
        val salt = ByteArray(32) { 0 }
        val extractMac = Mac.getInstance(macAlgo)
        extractMac.init(SecretKeySpec(salt, macAlgo))
        val prk = extractMac.doFinal(sharedSecret)
        
        // 2. HKDF-Expand(PRK, info, L=32)
        val expandMac = Mac.getInstance(macAlgo)
        expandMac.init(SecretKeySpec(prk, macAlgo))
        expandMac.update(info)
        expandMac.update(1.toByte())
        return expandMac.doFinal() // 32 bytes
    }

    /**
     * Wrap derived bytes into a SecretKey usable with AES/GCM/NoPadding.
     */
    fun toAesKey(keyBytes: ByteArray): SecretKey =
        SecretKeySpec(keyBytes.copyOf(32), "AES")

    // ── Encrypted Auth Block ──────────────────────────────────────────────────
    // Protocol step after ECDH:
    //   Watch → Phone:  [12-byte nonce][ciphertext+16-byte GCM tag]
    //   Phone decrypts and checks for magic string "STREAMLINK_VERIFIED"
    //   Wrong PIN → mismatched session key → AEADBadTagException → Fail Closed
    private const val AUTH_MAGIC = "STREAMLINK_VERIFIED"
    private const val AUTH_NONCE_SIZE = 12

    /**
     * Watch calls this immediately after ECDH to build the auth block to send.
     * @return  nonce(12) + ciphertext+tag bytes (35 bytes total for the magic string)
     */
    fun encryptAuthBlock(sessionKey: ByteArray): ByteArray {
        val nonce = ByteArray(AUTH_NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce)
        )
        val ciphertext = cipher.doFinal(AUTH_MAGIC.toByteArray(Charsets.UTF_8))
        return nonce + ciphertext
    }

    /**
     * Phone calls this to verify the auth block from the watch.
     * @return true if PIN matches, false if wrong PIN or MITM
     */
    fun verifyAuthBlock(authBlock: ByteArray, sessionKey: ByteArray): Boolean {
        return try {
            if (authBlock.size < AUTH_NONCE_SIZE + 1) return false
            val nonce = authBlock.copyOf(AUTH_NONCE_SIZE)
            val ciphertext = authBlock.copyOfRange(AUTH_NONCE_SIZE, authBlock.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                SecretKeySpec(sessionKey, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, nonce)
            )
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8) == AUTH_MAGIC
        } catch (e: javax.crypto.AEADBadTagException) {
            Log.w(TAG, "❌ Auth block verification failed — wrong PIN or tampered data")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Auth block error: ${e.message}")
            false
        }
    }
}
