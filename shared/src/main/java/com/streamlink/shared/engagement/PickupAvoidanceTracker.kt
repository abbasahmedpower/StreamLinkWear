package com.streamlink.shared.engagement

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PickupAvoidanceTracker — turns "did something from the watch instead of grabbing the
 * phone" into a simple, honest daily counter.
 *
 * Design goals (matches the project's existing nano/micro discipline):
 *  - O(1) hot path: in-memory cache backing a StateFlow, disk write is fire-and-forget.
 *  - No PII, no server round-trip — pure local SharedPreferences, same spirit as
 *    TrustedDeviceStore but without the encryption overhead (this is not sensitive data).
 *  - Resets automatically at local-day rollover — yesterday's number never leaks into today.
 *  - Deliberately NOT gamified with streak-shaming or push notifications: the goal is an
 *    honest reflection of behavior, not a dark-pattern engagement loop.
 *
 * Call [recordPickupAvoided] from exactly one place per "the user acted from the watch and
 * therefore did not pick up the phone" — e.g. RemoteControlAccessibilityService.onDown(),
 * which fires once per remote-control interaction (not once per touch-move segment).
 */
class PickupAvoidanceTracker private constructor(context: Context) {

    private val tag = "PickupAvoidance"
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun todayKey(): String = dayFormat.format(Date())

    private val _todayCount = MutableStateFlow(loadTodayCountFromDisk())
    val todayCount: StateFlow<Int> = _todayCount

    private fun loadTodayCountFromDisk(): Int {
        val storedDay = prefs.getString(KEY_DAY, null)
        return if (storedDay == todayKey()) prefs.getInt(KEY_COUNT, 0) else 0
    }

    /** Call once per watch-side interaction that replaced a phone pickup. */
    fun recordPickupAvoided() {
        val today = todayKey()
        val storedDay = prefs.getString(KEY_DAY, null)
        val newCount = if (storedDay == today) _todayCount.value + 1 else 1

        _todayCount.value = newCount
        prefs.edit()
            .putString(KEY_DAY, today)
            .putInt(KEY_COUNT, newCount)
            .apply()

        Log.d(tag, "Pickup avoided — today's count: $newCount")
    }

    companion object {
        private const val PREFS_FILE = "sl_pickup_avoidance_v1"
        private const val KEY_DAY = "day"
        private const val KEY_COUNT = "count"

        @Volatile private var instance: PickupAvoidanceTracker? = null

        fun get(context: Context): PickupAvoidanceTracker =
            instance ?: synchronized(this) {
                instance ?: PickupAvoidanceTracker(context).also { instance = it }
            }
    }
}

/** Usage: context.pickupAvoidanceTracker.recordPickupAvoided() */
val Context.pickupAvoidanceTracker: PickupAvoidanceTracker
    get() = PickupAvoidanceTracker.get(this)
