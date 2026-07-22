package com.streamlink.app.core.telemetry

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Recorder that keeps the last 30 seconds of telemetry records in memory.
 * Dumps the records to a file upon critical events:
 * - Crash (Uncaught Exceptions)
 * - Disconnect
 * - Handover
 * - Encoder/Decoder Restart
 * - Emergency Downgrade
 */
class TelemetryRecorder(private val context: Context) {
    private val tag = "TelemetryRecorder"
    
    // We assume 1 telemetry sample every 500ms. 30 seconds = 60 samples.
    private val maxCapacity = 60
    private val ringBuffer = ConcurrentLinkedQueue<String>()

    init {
        // Setup Uncaught Exception Handler to catch crashes
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordEvent("Crash: ${throwable.message}")
            dumpToFile("crash")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun recordSample(sampleJson: String) {
        ringBuffer.add(sampleJson)
        while (ringBuffer.size > maxCapacity) {
            ringBuffer.poll()
        }
    }

    fun recordEvent(eventDescription: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        ringBuffer.add("[$timestamp] EVENT: $eventDescription")
        while (ringBuffer.size > maxCapacity) {
            ringBuffer.poll()
        }
    }

    fun dumpToFile(triggerReason: String) {
        try {
            val dir = File(context.filesDir, "telemetry_dumps")
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "telemetry_${triggerReason}_$timestamp.log")
            
            FileWriter(file).use { writer ->
                writer.write("--- TELEMETRY DUMP TRIGGERED BY: $triggerReason ---\n")
                ringBuffer.forEach { record ->
                    writer.write(record)
                    writer.write("\n")
                }
            }
            Log.i(tag, "✅ Saved last 30s of telemetry to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to dump telemetry to file: ${e.message}")
        }
    }
}
