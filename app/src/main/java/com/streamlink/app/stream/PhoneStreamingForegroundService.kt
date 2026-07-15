package com.streamlink.app.stream

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
import com.streamlink.app.R
import com.streamlink.app.ui.MainActivity
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.util.safeSystemService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * PhoneStreamingForegroundService — يبقي البث حياً عندما يُخفّف المستخدم التطبيق.
 *
 * المشكلة التي يحلّها:
 *   بدون هذا الـ Service، يقتل Android الـ Coroutines عند ذهاب التطبيق للخلفية،
 *   مما يوقف البث الفوري. المستخدم يجب أن يعود للتطبيق ليُعيد التشغيل يدوياً.
 *
 * الحل:
 *   ForegroundService مع PARTIAL_WAKE_LOCK يمنع النظام من إيقاف الخيوط،
 *   ويُظهر إشعاراً دائماً يُعلم المستخدم أن البث نشط.
 *
 * Policy Compliance (Google Play):
 *   - نوع: connectedDevice — يُعبّر بدقة عن الغرض (بث لجهاز متصل).
 *   - الـ WakeLock يُقيَّد بـ 30 دقيقة كحد أقصى تلقائياً.
 *   - الإشعار واضح ويحتوي زر "إيقاف" مباشر.
 *
 * Nano-Level Design:
 *   - WakeLock.setReferenceCounted(false) → release() آمن حتى لو استُدعي مرتين.
 *   - try-finally في releaseWakeLock لمنع تسريب مهما حدث.
 *   - START_STICKY → يُعاد تشغيله إذا أوقفه النظام بضغط ذاكرة.
 *
 * Usage:
 *   PhoneStreamingForegroundService.start(context)
 *   PhoneStreamingForegroundService.stop(context)
 */
@AndroidEntryPoint
class PhoneStreamingForegroundService : Service() {

    private val tag = "PhoneFgService"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var streamStateJob: Job? = null

    companion object {
        const val ACTION_START = "com.streamlink.app.FG_START"
        const val ACTION_STOP  = "com.streamlink.app.FG_STOP"
        const val CHANNEL_ID   = "StreamLinkPhone_FG"
        const val NOTIF_ID     = 2001

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, PhoneStreamingForegroundService::class.java)
                    .apply { action = ACTION_START }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, PhoneStreamingForegroundService::class.java)
                    .apply { action = ACTION_STOP }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.i(tag, "Starting phone foreground service")
                startForegroundWithNotification()
                acquireWakeLock()
                watchStreamState()
            }
            ACTION_STOP -> {
                Log.i(tag, "Stopping phone foreground service")
                shutdown()
            }
            null -> {
                // System restarted us (START_STICKY) — re-enter foreground immediately
                Log.w(tag, "Restarted by system, re-entering foreground")
                startForegroundWithNotification()
                acquireWakeLock()
            }
        }
        return START_STICKY
    }

    // ── Foreground & Notification ─────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        createNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                buildNotification("جاري البث إلى الساعة…"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("جاري البث إلى الساعة…"))
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        // زر "إيقاف" مباشر من الإشعار — سهّل على المستخدم
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PhoneStreamingForegroundService::class.java)
                .apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamLink — بث نشط")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setLocalOnly(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_pause,
                "إيقاف البث",
                stopIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "StreamLink — بث نشط",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "إشعار دائم أثناء البث إلى الساعة"
            setShowBadge(false)
        }
        safeSystemService<NotificationManager>(NOTIFICATION_SERVICE)
            ?.createNotificationChannel(channel)
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm: PowerManager? = safeSystemService(POWER_SERVICE)
        if (pm == null) {
            Log.w(tag, "POWER_SERVICE unavailable — running without WakeLock")
            return
        }
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StreamLinkPhone::BroadcastWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // 30 min max — prevents infinite drain
        }
        Log.d(tag, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(tag, "WakeLock release error: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    // ── Stream State Monitor ──────────────────────────────────────────────────

    /**
     * يراقب حالة البث لتحديث نص الإشعار تلقائياً.
     * إذا توقف البث من تلقاء نفسه → يُوقف الـ Service لتوفير البطارية.
     */
    private fun watchStreamState() {
        streamStateJob?.cancel()
        streamStateJob = scope.launch {
            GlobalStreamState.snapshot.collect { snapshot ->
                when (snapshot.state) {
                    GlobalStreamState.State.STREAMING -> {
                        updateNotification(
                            "جاري البث · ${snapshot.bitrateKbps} kbps · ${snapshot.latencyMs} ms"
                        )
                    }
                    GlobalStreamState.State.STOPPED,
                    GlobalStreamState.State.FAILED -> {
                        Log.i(tag, "Stream ended (${snapshot.state}) — auto-stopping foreground service")
                        shutdown()
                    }
                    else -> { /* CONNECTING, RECOVERING — keep service alive */ }
                }
            }
        }
    }

    private fun updateNotification(text: String) {
        val nm: NotificationManager? = safeSystemService(NOTIFICATION_SERVICE)
        nm?.notify(NOTIF_ID, buildNotification(text))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private fun shutdown() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        streamStateJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        Log.i(tag, "PhoneStreamingForegroundService destroyed")
    }
}
