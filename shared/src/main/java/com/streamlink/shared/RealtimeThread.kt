package com.streamlink.shared

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * RealtimeThread: A dedicated background thread with the highest possible priority
 * (URGENT_DISPLAY or AUDIO) to avoid Linux kernel context switching delays.
 */
class RealtimeThread(name: String) : HandlerThread(name, Process.THREAD_PRIORITY_URGENT_DISPLAY) {

    @Volatile private var handler: Handler? = null
    private val pendingTasks = ArrayDeque<Runnable>()
    private val lock = Object()

    override fun onLooperPrepared() {
        super.onLooperPrepared()
        val h = Handler(looper)
        synchronized(lock) {
            handler = h
            while (pendingTasks.isNotEmpty()) {
                h.post(pendingTasks.removeFirst())
            }
        }
    }

    fun executeRealtime(task: Runnable) {
        synchronized(lock) {
            val h = handler
            if (h != null) h.post(task) else pendingTasks.addLast(task)
        }
    }

    fun shutdownSafely() {
        synchronized(lock) { pendingTasks.clear() }
        quitSafely()
    }
}
