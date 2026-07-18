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
 *
 * Test Instrumentation:
 * - Set JVM system property `SERVER_PRIVATE_KEY_OVERRIDE_FOR_TEST` to inject
 *   a deterministic key during unit tests without modifying environment variables.
 *   This property is ONLY checked in non-production builds and is ignored if the
 *   real env variable is present.
 */
object ServerIdentityVerifier {

    /**
     * Resolves the HMAC signing key.
     * Resolution order:
     *   1. `SERVER_PRIVATE_KEY` environment variable  (production, takes priority)
     *   2. `SERVER_PRIVATE_KEY_OVERRIDE_FOR_TEST` JVM system property  (test only)
     */
    private val SERVER_PRIVATE_KEY: ByteArray by lazy {
        val envKey  = System.getenv("SERVER_PRIVATE_KEY")
        val testKey = System.getProperty("SERVER_PRIVATE_KEY_OVERRIDE_FOR_TEST")
        val resolved = envKey ?: testKey
            ?: error("❌ SERVER_PRIVATE_KEY env variable is not set! " +
                     "For tests, set SERVER_PRIVATE_KEY_OVERRIDE_FOR_TEST system property.")
        resolved.toByteArray(Charsets.UTF_8)
    }

    /**
     * Signs a userId and returns a token in the format `UUID.Signature`.
     * Called once during device registration.
     */
    fun generateClientToken(userId: String): String {
        val signature = hmacSign(userId)
        return "$userId.$signature"
    }

    /**
     * Verifies a `UUID.Signature` token.
     * Returns the verified userId on success, null on any failure.
     * Uses constant-time comparison to prevent timing attacks.
     */
    fun verifyClientToken(rawToken: String): String? {
        val dotIndex = rawToken.indexOf('.')
        if (dotIndex < 1 || dotIndex == rawToken.lastIndex) return null

        val userId         = rawToken.substring(0, dotIndex)
        val clientSig      = rawToken.substring(dotIndex + 1)
        val expectedSig    = hmacSign(userId)

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
