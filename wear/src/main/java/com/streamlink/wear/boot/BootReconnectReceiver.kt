package com.streamlink.wear.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.streamlink.shared.TrustedDeviceStore
import com.streamlink.wear.service.WearForegroundService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * FEATURE (closes the RECEIVE_BOOT_COMPLETED dead-permission nano-bug):
 *
 * The wear manifest already declared RECEIVE_BOOT_COMPLETED but no receiver ever
 * consumed it. This receiver makes the permission earn its place: if the watch has
 * a trusted paired phone on record (TrustedDeviceStore — Trust on First Use), the
 * foreground service is quietly started right after boot so discovery/reconnect can
 * begin immediately, without the user having to manually reopen the app.
 *
 * This is intentionally conservative:
 *  - Does nothing if no device has ever been trusted (fresh installs, revoked pairing).
 *  - Only *starts listening* — it does not force a stream to begin. WearForegroundService
 *    + DirectSocketClient own the actual connect/backoff logic already in place.
 */
@AndroidEntryPoint
class BootReconnectReceiver : BroadcastReceiver() {

    @Inject
    lateinit var trustedDeviceStore: TrustedDeviceStore

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!trustedDeviceStore.hasTrustedDevice) {
            Log.i(TAG, "Boot completed — no trusted device on record, skipping auto-resume.")
            return
        }

        Log.i(TAG, "Boot completed — trusted device '${trustedDeviceStore.trustedDeviceName}' " +
            "found, resuming WearForegroundService.")
        WearForegroundService.start(context)
    }

    companion object {
        private const val TAG = "BootReconnectReceiver"
    }
}
