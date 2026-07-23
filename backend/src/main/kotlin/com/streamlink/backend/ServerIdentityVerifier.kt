package com.streamlink.backend

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless Identity Verifier — performs HMAC-SHA256 signing and
 * constant-time verification of device UUIDs without any database lookup.
 *
 * Security properties:
 * - The SERVER_PRIVATE_KEY never leaves the server environment.
 * - The E2EE ECDH shared-secret is never involved; this key is exclusively
 *   for backend-to-client authentication.
 * - Constant-time comparison prevents timing side-channel attacks.
 * - ✅ §3.6: Tokens embed an issuedAt timestamp and expire after TOKEN_TTL_MS.
 *   Revocation is handled separately via Redis (see Application.kt /api/v1/revoke).
 */
object ServerIdentityVerifier {

    /** 30-day token lifetime — cheap to tune without changing the protocol */
    private const val TOKEN_TTL_MS = 30L * 24 * 60 * 60 * 1000

    private val SERVER_PRIVATE_KEY: ByteArray by lazy {
        val envKey  = System.getenv("SERVER_PRIVATE_KEY")
        val testKey = System.getProperty("SERVER_PRIVATE_KEY_OVERRIDE_FOR_TEST")
        val resolved = envKey ?: testKey
            ?: error("❌ SERVER_PRIVATE_KEY env variable is not set! " +
                     "For tests, set SERVER_PRIVATE_KEY_OVERRIDE_FOR_TEST system property.")
        resolved.toByteArray(Charsets.UTF_8)
    }

    /**
     * Signs a userId + issuedAt timestamp and returns a token:
     *   `userId|issuedAtMs.Signature`
     * Called once during device registration.
     */
    fun generateClientToken(userId: String): String {
        val issuedAt = System.currentTimeMillis()
        val payload  = "$userId|$issuedAt"
        val signature = hmacSign(payload)
        return "$payload.$signature"
    }

    /**
     * Verifies a `userId|issuedAtMs.Signature` token.
     * Returns the verified userId on success, null on any failure.
     * Rejects tokens that are expired, malformed, or have a bad signature.
     * Uses constant-time comparison to prevent timing attacks.
     */
    fun verifyClientToken(rawToken: String): String? {
        val lastDot = rawToken.lastIndexOf('.')
        if (lastDot < 1 || lastDot == rawToken.lastIndex) return null

        val payload   = rawToken.substring(0, lastDot)
        val clientSig = rawToken.substring(lastDot + 1)

        // ✅ §3.6: Parse and validate expiry before touching the signature
        val parts = payload.split("|")
        if (parts.size != 2) return null
        val (userId, issuedAtStr) = parts
        val issuedAt = issuedAtStr.toLongOrNull() ?: return null
        if (System.currentTimeMillis() - issuedAt > TOKEN_TTL_MS) return null  // expired

        val expectedSig = hmacSign(payload)
        val isValid = MessageDigest.isEqual(
            clientSig.toByteArray(Charsets.UTF_8),
            expectedSig.toByteArray(Charsets.UTF_8)
        )
        return if (isValid) userId else null
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun hmacSign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SERVER_PRIVATE_KEY, "HmacSHA256"))
        val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
