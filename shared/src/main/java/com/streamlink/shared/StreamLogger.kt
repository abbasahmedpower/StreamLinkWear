package com.streamlink.shared

import android.util.Log

/**
 * StreamLogger — Zero-overhead production wrapper for Android Log.
 *
 * Strips out Verbose and Debug logs in Release builds to optimize I/O overhead.
 */
object StreamLogger {
    // In a multi-module setup, we can default to BuildConfig.DEBUG or use a manual flag.
    // We will read from a configurable flag initialized by the app.
    var isDebug: Boolean = true

    fun v(tag: String, msg: String) {
        if (isDebug) {
            Log.v(tag, msg)
        }
    }

    fun d(tag: String, msg: String) {
        if (isDebug) {
            Log.d(tag, msg)
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.w(tag, msg, tr)
        } else {
            Log.w(tag, msg)
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.e(tag, msg, tr)
        } else {
            Log.e(tag, msg)
        }
    }
}
