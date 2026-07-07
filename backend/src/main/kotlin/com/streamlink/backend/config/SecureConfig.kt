package com.streamlink.backend.config

object SecureConfig {
    private fun requireEnv(key: String): String =
        System.getenv(key)?.takeIf { it.isNotBlank() && !it.startsWith("CHANGE_ME") }
            ?: throw IllegalStateException(
                "FATAL: Environment variable '$key' is missing or contains a placeholder value. " +
                "Refusing to start with an insecure default."
            )

    val horusSecretToken: String by lazy { requireEnv("HORUS_SECRET") }
    val redisUrl: String by lazy { requireEnv("REDIS_URL") }
    val tlsPassword: String by lazy { requireEnv("HORUS_TLS_PASSWORD") }
    val nodeId: String = System.getenv("NODE_ID") ?: "NODE_1"
}
