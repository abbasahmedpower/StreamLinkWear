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
    private val timeoutMs: Long = 5000L,
    private val onAnrDetected: (ANRException) -> Unit = { e ->
        Log.e("ANRWatchDog", "ANR detected on main thread — reporting only, preserving app process", e)
    }
) : Thread("ANRWatchDog") {

    @Volatile
    private var tick: Long = 0L

    @Volatile
    private var reported: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ticker = Runnable {
        tick = (tick + 1) and Long.MAX_VALUE
        reported = false
    }

    init {
        // Run watchdog as a background daemon
        isDaemon = true
    }

    override fun run() {
        var lastTick = -1L
        var anrDetectedCount = 0

        while (!isInterrupted) {
            val currentTick = tick
            if (currentTick == lastTick) {
                anrDetectedCount++
                if (anrDetectedCount == 1) {
                    // First detection: wait 2s to confirm it's not a temporary GC pause or Doze mode
                    Log.w("ANRWatchDog", "Main thread blocked for >${timeoutMs}ms. Waiting 2s for confirmation...")
                    try {
                        sleep(2000L)
                    } catch (e: InterruptedException) {
                        return
                    }
                    continue
                } else if (!reported) {
                    // Second pass: confirmed ANR
                    reported = true
                    val mainThread = Looper.getMainLooper().thread
                    val stackTrace = mainThread.stackTrace
                    val e = ANRException("ANR Confirmed! Main thread is blocked for >${timeoutMs + 2000}ms", stackTrace)
                    
                    Log.e("ANRWatchDog", "Application Not Responding (ANR) detected", e)
                    onAnrDetected(e)
                }
            } else {
                anrDetectedCount = 0
                reported = false
                lastTick = currentTick
                mainHandler.post(ticker)
            }

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
