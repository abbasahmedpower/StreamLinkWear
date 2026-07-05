package com.streamlink.shared.diagnostics

import android.content.Context
import android.media.MediaCodecList
import android.util.Log
import com.streamlink.shared.SecurityUtils

/**
 * MICRO-09: Configuration Validator
 * Pre-flight checks before stream initiation.
 */
object ConfigValidator {

    private const val TAG = "ConfigValidator"

    fun validatePreFlight(context: Context): Boolean {
        Log.i(TAG, "Running pre-flight checks...")
        
        if (SecurityUtils.isRooted() || SecurityUtils.isEmulator()) {
            Log.w(TAG, "Security risk detected: Rooted/Emulator")
            // Can be bypassed in debug, handled in MainActivity
        }

        if (!isAvcCodecAvailable()) {
            Log.e(TAG, "H.264 (AVC) Codec not supported on this device!")
            return false
        }

        // Additional pre-flight checks (WiFi status, memory available) can be added here
        Log.i(TAG, "Pre-flight checks passed.")
        return true
    }

    private fun isAvcCodecAvailable(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            if (info.isEncoder) {
                for (type in info.supportedTypes) {
                    if (type.equals("video/avc", ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}
