package com.streamlink.shared.diagnostics

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured JSON Logger for Enterprise Observability.
 * Outputs all logs as parsable JSON for tools like Loki or ElasticSearch.
 */
object StructuredLogger {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    private var sessionId: String = "unknown"
    private var appVersion: String = "1.0.0"
    private var buildCode: Int = 5
    
    // Global context fields
    private var currentThermalState: Int = -1
    private var currentBatteryLevel: Int = -1
    private var isWatch: Boolean = false

    fun init(sessionId: String, appVersion: String, buildCode: Int, isWatch: Boolean) {
        this.sessionId = sessionId
        this.appVersion = appVersion
        this.buildCode = buildCode
        this.isWatch = isWatch
    }

    fun updateContext(thermalState: Int, batteryLevel: Int) {
        currentThermalState = thermalState
        currentBatteryLevel = batteryLevel
    }

    fun i(component: String, message: String, extras: Map<String, Any> = emptyMap()) {
        log("INFO", component, message, extras)
    }

    fun w(component: String, message: String, extras: Map<String, Any> = emptyMap()) {
        log("WARN", component, message, extras)
    }

    fun e(component: String, message: String, throwable: Throwable? = null, extras: Map<String, Any> = emptyMap()) {
        val errorExtras = extras.toMutableMap()
        throwable?.let {
            errorExtras["exception"] = it.javaClass.simpleName
            errorExtras["error_message"] = it.message ?: "Unknown"
        }
        log("ERROR", component, message, errorExtras)
    }
    
    fun d(component: String, message: String, extras: Map<String, Any> = emptyMap()) {
        log("DEBUG", component, message, extras)
    }

    private fun log(severity: String, component: String, message: String, extras: Map<String, Any>) {
        try {
            val json = JSONObject().apply {
                put("timestamp", dateFormat.format(Date()))
                put("session_id", sessionId)
                put("thread", Thread.currentThread().name)
                put("component", component)
                put("severity", severity)
                put("device_model", Build.MODEL)
                put("is_watch", isWatch)
                put("version", appVersion)
                put("build", buildCode)
                put("thermal_state", currentThermalState)
                put("battery_level", currentBatteryLevel)
                put("message", message)
                
                if (extras.isNotEmpty()) {
                    val extrasObj = JSONObject()
                    extras.forEach { (k, v) -> extrasObj.put(k, v) }
                    put("extras", extrasObj)
                }
            }

            val logString = json.toString()
            
            // Output to standard Logcat (can be scraped by Promtail or Fluentd later)
            when (severity) {
                "INFO" -> Log.i("StreamLinkJSON", logString)
                "WARN" -> Log.w("StreamLinkJSON", logString)
                "ERROR" -> Log.e("StreamLinkJSON", logString)
                else -> Log.d("StreamLinkJSON", logString)
            }
        } catch (e: Exception) {
            // Fallback in case JSON encoding fails
            Log.e("StreamLinkJSON", "Fallback: [$severity] $component: $message")
        }
    }
}
