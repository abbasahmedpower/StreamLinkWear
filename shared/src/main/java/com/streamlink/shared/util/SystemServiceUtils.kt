package com.streamlink.shared.util

import android.content.Context
import android.util.Log

/** يرجع null بدل ما يرمي Exception لو الخدمة مش متاحة على الجهاز/الروم. */
inline fun <reified T> Context.safeSystemService(name: String): T? {
    return try {
        getSystemService(name) as? T
    } catch (e: Exception) {
        Log.e("SafeSystemService", "Service '$name' unavailable: ${e.message}")
        null
    }
}
