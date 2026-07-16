package com.streamlink.app.telemetry

import android.util.Log
import com.streamlink.app.capture.HardwareEncoder
import com.streamlink.shared.telemetry.StreamingControlAction
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Hardware Actuator bridging the Fuzzy Decision Engine with the actual TCP / MediaCodec streamer.
 */
class HardwareActuator(
    private val hardwareEncoder: HardwareEncoder?,
    // Use SingleThreadExecutor to ensure atomic applications
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {

    fun applyControlAction(action: StreamingControlAction) {
        hardwareEncoder ?: return

        executor.execute {
            try {
                Log.d(TAG, "Actuating: Bitrate=${action.targetBitrateKbps}Kbps, Profile=${action.profile.width}x${action.profile.height}@${action.targetFps}, Reason=${action.reason}")

                // 1. Adjust hardware encoder bitrate dynamically (bps scaling)
                hardwareEncoder.setBitrate(action.targetBitrateKbps)

                // 2. Adjust resolution / FPS if severity dictates a step down
                hardwareEncoder.reconfigure(action.profile)

                // 3. Immediately trigger a keyframe (I-Frame) if the TCP queue dropped frames
                if (action.requestKeyframe) {
                    Log.w(TAG, "Network packet loss detected (TCP Queue Overflow). Requesting instant Keyframe!")
                    hardwareEncoder.forceKeyframe()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply hardware control action", e)
            }
        }
    }

    fun release() {
        executor.shutdown()
    }

    companion object {
        private const val TAG = "HardwareActuator"
    }
}
