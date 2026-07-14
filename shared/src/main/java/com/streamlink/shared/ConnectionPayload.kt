package com.streamlink.shared

import org.json.JSONObject

data class ConnectionPayload(
    val ip: String,
    val port: Int,
    val pairingCode: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("ip", ip)
            put("port", port)
            put("pairingCode", pairingCode)
            put("timestamp", timestamp)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): ConnectionPayload? {
            return try {
                val obj = JSONObject(jsonStr)
                ConnectionPayload(
                    ip = obj.getString("ip"),
                    port = obj.getInt("port"),
                    pairingCode = obj.getString("pairingCode"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
