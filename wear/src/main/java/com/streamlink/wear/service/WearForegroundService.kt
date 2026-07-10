package com.streamlink.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamlink.wear.R
import com.streamlink.wear.player.DirectStreamPlayer
import com.streamlink.wear.ui.WearMainActivity
import dagger.hilt.android.AndroidEntryPoint
import com.streamlink.shared.util.safeSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

/**
 * WearForegroundService — keeps streaming alive when the watch screen dims.
 *
 * Without this, Android kills the streaming coroutines when the app goes to background.
 * This ForegroundService holds a WakeLock and runs the player independently of the UI.
 *
 * Usage:
 *   Start: startForegroundService(Intent(context, WearForegroundService::class.java).apply { action = ACTION_START })
 *   Stop:  startService(Intent(context, WearForegroundService::class.java).apply { action = ACTION_STOP })
 */
@AndroidEntryPoint
class WearForegroundService : Service() {

    @Inject lateinit var streamPlayer: DirectStreamPlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START = "com.streamlink.wear.START"
        const val ACTION_STOP  = "com.streamlink.wear.STOP"
        const val CHANNEL_ID   = "StreamLinkWear_Channel"
        const val NOTIF_ID     = 1001

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, WearForegroundService::class.java).apply { action = ACTION_START }
            )
        }
        fun stop(context: Context) {
            context.startService(
                Intent(context, WearForegroundService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        streamPlayer.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startOrRecoverForeground(systemRestart = false)
            }
            ACTION_STOP -> {
                Log.i("WearFgService", "Stopping streaming service")
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            null -> {
                startOrRecoverForeground(systemRestart = true)
            }
            else -> {
                Log.w("WearFgService", "Unknown action=${intent.action}; keeping foreground stream guard alive")
                startOrRecoverForeground(systemRestart = false)
            }
        }
        return START_STICKY
    }

    private fun startOrRecoverForeground(systemRestart: Boolean) {
        createNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        acquireWakeLock()
        if (systemRestart) {
            Log.w("WearFgService", "Recovered after system restart; waiting for UI Surface to resume player")
        } else {
            Log.i("WearFgService", "Streaming service started")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm: PowerManager? = safeSystemService(POWER_SERVICE)
        if (pm == null) {
            Log.w("WearFgService", "POWER_SERVICE unavailable — continuing without WakeLock")
            return
        }
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StreamLinkWear::StreamWakeLock"
        ).apply { acquire(30 * 60 * 1000L)  } // 30 min max
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamLink Active")
            .setContentText("Streaming from phone")
            .setSmallIcon(android.R.drawable.ic_media_play) // Mandatory for foreground service
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "StreamLink Stream",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active streaming notification" }
        val nm: NotificationManager? = safeSystemService(Context.NOTIFICATION_SERVICE)
        nm?.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        streamPlayer.release()
        scope.cancel()
        releaseWakeLock()
    }
}
