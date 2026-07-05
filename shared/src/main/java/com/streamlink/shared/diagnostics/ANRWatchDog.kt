package com.streamlink.shared.diagnostics

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * A lightweight ANR watchdog that monitors the main thread.
 * It posts a small Runnable to the main looper. If the runnable is not executed
 * within the given timeout (e.g. 5 seconds), it means the main thread is blocked.
 */
class ANRWatchDog(
    private val timeoutMs: Long = 5000L
) : Thread("ANRWatchDog") {

    @Volatile
    private var tick: Long = 0L

    @Volatile
    private var reported: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ticker = Runnable {
        tick = (tick + 1) % Long.MAX_VALUE
        reported = false
    }

    init {
        // Run watchdog as a background daemon
        isDaemon = true
    }

    override fun run() {
        var lastTick = -1L

        while (!isInterrupted) {
            val currentTick = tick
            if (currentTick == lastTick && !reported) {
                // ANR detected
                reported = true
                val mainThread = Looper.getMainLooper().thread
                val stackTrace = mainThread.stackTrace
                val e = ANRException("ANR Detected! Main thread is blocked for >${timeoutMs}ms", stackTrace)
                
                // We use Log.e directly to ensure it gets recorded.
                // If CrashReporter is active, this will crash the app and save the trace.
                Log.e("ANRWatchDog", "Application Not Responding (ANR)", e)
                
                // Throw it on the current thread to trigger the global CrashReporter
                throw e
            }

            lastTick = currentTick
            mainHandler.post(ticker)

            try {
                sleep(timeoutMs)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    class ANRException(message: String, stackTrace: Array<StackTraceElement>) : RuntimeException(message) {
        init {
            this.stackTrace = stackTrace
        }
    }
}
