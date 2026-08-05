package com.streamlink.backend.config

object SecureConfig {
    private fun requireEnv(key: String): String =
        System.getenv(key)?.takeIf { it.isNotBlank() && !it.startsWith("CHANGE_ME") }
            ?: throw IllegalStateException(
                "FATAL: Environment variable '$key' is missing or contains a placeholder value. " +
                "Refusing to start with an insecure default."
            )

    // ✅ 5.4: Renamed from HORUS_SECRET → HORUS_SECRET_TOKEN to match client-side naming
    // (secrets.properties, app/build.gradle, shared/build.gradle all use HORUS_SECRET_TOKEN).
    val horusSecretToken: String by lazy { requireEnv("HORUS_SECRET_TOKEN") }
    val redisUrl: String by lazy { requireEnv("REDIS_URL") }
    val tlsPassword: String by lazy { requireEnv("HORUS_TLS_PASSWORD") }
    val nodeId: String = System.getenv("NODE_ID") ?: "NODE_1"
    /** Shared REST API secret for self-hosted coturn short-TTL credentials. */
    val coturnSecret: String by lazy { requireEnv("COTURN_SECRET") }
}
