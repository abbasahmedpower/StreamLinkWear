package com.streamlink.app.diagnostics

import android.content.Context
import android.util.Log
import com.streamlink.shared.diagnostics.StartupDiagnostics
import java.io.PrintWriter
import java.io.StringWriter

/**
 * CrashReporter — catches uncaught exceptions, saves a crash report to
 * SharedPreferences, and shows it on the next launch.
 *
 * Install in Application.onCreate() BEFORE Hilt injection:
 *   CrashReporter.install(this)
 *
 * Read pending report:
 *   CrashReporter.getPendingReport(context)?.let { showCrashDialog(it) }
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val PREFS_NAME = "streamlink_crash_report"
    private const val KEY_CRASH = "last_crash"
    private const val KEY_COUNT = "crash_count"
    private const val MAX_REPORT_CHARS = 8_000

    /**
     * Install the global uncaught exception handler.
     * Call this as early as possible in Application.onCreate().
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildReport(thread, throwable)
                save(appContext, report)
                Log.e(TAG, "═══ CRASH REPORT SAVED ═══\n$report")
            } catch (e: Exception) {
                Log.e(TAG, "CrashReporter itself failed", e)
            } finally {
                // Delegate to the original handler (e.g. Firebase Crashlytics or system default)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        Log.i(TAG, "CrashReporter installed")
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val startupTrace = try { StartupDiagnostics.dump() } catch (_: Exception) { "N/A" }

        // MICRO-08: Snapshot Analytics
        val rt = Runtime.getRuntime()
        val memMax = rt.maxMemory() / 1024 / 1024
        val memAlloc = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val memoryFootprint = "${memAlloc}MB / ${memMax}MB"
        val battery = com.streamlink.shared.telemetry.DeviceMonitor.batteryPercent
        val thermals = com.streamlink.shared.telemetry.DeviceMonitor.thermalStatus

        return buildString {
            appendLine("═══ StreamLink Crash Report ═══")
            appendLine("Time    : ${java.util.Date()}")
            appendLine("Thread  : ${thread.name} (${thread.id})")
            appendLine("LastStep: ${StartupDiagnostics.lastStep}")
            appendLine("Memory  : $memoryFootprint")
            appendLine("Battery : $battery%")
            appendLine("Thermals: Status $thermals")
            appendLine()
            appendLine("── Exception ──")
            appendLine(stackTrace.take(4_000))
            appendLine()
            appendLine("── Startup Trace ──")
            appendLine(startupTrace.take(2_000))
            appendLine("═══════════════════════════════")
        }.take(MAX_REPORT_CHARS)
    }

    private fun save(context: Context, report: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_COUNT, 0) + 1
        prefs.edit()
            .putString(KEY_CRASH, report)
            .putInt(KEY_COUNT, count)
            .apply()
    }

    /**
     * Returns a pending crash report from the previous session (if any),
     * and clears it from storage. Returns null if there is no pending report.
     */
    fun getPendingReport(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val report = prefs.getString(KEY_CRASH, null) ?: return null
        val count = prefs.getInt(KEY_COUNT, 0)
        prefs.edit().remove(KEY_CRASH).apply()
        return "Crash #$count from previous session:\n\n$report"
    }

    /**
     * Returns how many crashes have been recorded since installation.
     */
    fun getCrashCount(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_COUNT, 0)
    }
}
