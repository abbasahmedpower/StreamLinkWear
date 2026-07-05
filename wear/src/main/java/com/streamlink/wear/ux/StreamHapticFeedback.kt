package com.streamlink.wear.ux

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.util.safeSystemService

class StreamHapticFeedback(context: Context) {
    private val vibrator: Vibrator? = context.safeSystemService(Context.VIBRATOR_SERVICE)

    fun triggerFeedback(state: GlobalStreamState.State) {
        if (vibrator == null || !vibrator.hasVibrator()) return

        when (state) {
            GlobalStreamState.State.STREAMING -> {
                // Short light pulse to announce successful connection
                val effect = VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            }
            GlobalStreamState.State.DEGRADED -> {
                // Two successive pulses to gently alert the user of stream quality degradation
                val timings = longArrayOf(0, 30, 100, 30)
                val amplitudes = intArrayOf(0, 150, 0, 150)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator.vibrate(effect)
            }
            GlobalStreamState.State.FAILED -> {
                // Long and explicit vibration to announce channel disconnection and recovery start
                val effect = VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            }
            else -> { /* No need to vibrate in other stable states */ }
        }
    }
}
