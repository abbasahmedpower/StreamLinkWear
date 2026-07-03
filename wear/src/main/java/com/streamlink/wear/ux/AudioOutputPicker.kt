package com.streamlink.wear.ux

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** فتح قائمة أندرويد الرسمية لاختيار جهاز إخراج الصوت — بدون أي كود Bluetooth مخصص. */
object AudioOutputPicker {
    fun open(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w("AudioOutputPicker", "Volume panel unavailable, fallback: ${e.message}")
            try {
                context.startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { /* مفيش حل تاني — نتجاهل بهدوء */ }
        }
    }
}
