package com.streamlink.shared

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * RealtimeThread: A dedicated background thread with the highest possible priority
 * (URGENT_DISPLAY or AUDIO) to avoid Linux kernel context switching delays.
 */
class RealtimeThread(name: String) : HandlerThread(name, Process.THREAD_PRIORITY_URGENT_DISPLAY) {
    
    private var handler: Handler? = null

    override fun onLooperPrepared() {
        super.onLooperPrepared()
        handler = Handler(looper)
    }

    fun executeRealtime(task: Runnable) {
        val h = handler
        if (h != null) {
            h.post(task)
        } else {
            // Postpone if looper isn't ready
            Thread {
                while (handler == null) {
                    Thread.sleep(1)
                }
                handler?.post(task)
            }.start()
        }
    }
}
