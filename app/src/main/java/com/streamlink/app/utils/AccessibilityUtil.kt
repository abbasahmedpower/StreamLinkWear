package com.streamlink.app.utils

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.streamlink.app.control.RemoteControlAccessibilityService

object AccessibilityUtil {

    /**
     * فحص صارم ومزدوج لمنع تزييف حالة الخدمة من قبل كاش نظام التشغيل.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val isEnabledByManager = am.isEnabled

        val expectedComponentName = "${context.packageName}/${RemoteControlAccessibilityService::class.java.name}"
        var isEnabledBySettings = false
        
        try {
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0
            )
            
            if (accessibilityEnabled == 1) {
                val settingValue = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (settingValue != null) {
                    val splitter = TextUtils.SimpleStringSplitter(':')
                    splitter.setString(settingValue)
                    while (splitter.hasNext()) {
                        val accessibilityService = splitter.next()
                        if (accessibilityService.equals(expectedComponentName, ignoreCase = true)) {
                            isEnabledBySettings = true
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AccessibilityUtil", "❌ Error parsing secure settings", e)
        }

        return isEnabledByManager && isEnabledBySettings
    }
}
