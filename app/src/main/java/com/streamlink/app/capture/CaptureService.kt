package com.streamlink.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.shared.util.safeSystemService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CaptureService : Service() {
    private val tag = "CaptureService"

    @Inject lateinit var orchestrator: StreamingOrchestrator
    @Inject lateinit var hardwareEncoder: HardwareEncoder
    @Inject lateinit var directSocketServer: com.streamlink.shared.DirectSocketServer
    @Inject lateinit var audioCaptureEngine: AudioCaptureEngine

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(tag, "Projection stopped by system — tearing down")
            stopCapture()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

            if (data != null) {
                startForeground(1, createNotification())
                startCapture(resultCode, data)
            }
        } else if (action == ACTION_STOP) {
            stopCapture()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val mpm = safeSystemService<MediaProjectionManager>(Context.MEDIA_PROJECTION_SERVICE)
            if (mpm == null) {
                com.streamlink.shared.diagnostics.StartupDiagnostics.warn("CaptureService", "MediaProjectionManager is null")
                stopSelf()
                return
            }

            mediaProjection = mpm.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                com.streamlink.shared.diagnostics.StartupDiagnostics.warn("CaptureService", "MediaProjection is null")
                stopSelf()
                return
            }

            com.streamlink.shared.diagnostics.StartupDiagnostics.ok("CaptureService MediaProjection started")
        } catch (e: Exception) {
            android.util.Log.e(tag, "Failed to start MediaProjection", e)
            com.streamlink.shared.diagnostics.StartupDiagnostics.warn("CaptureService", "MediaProjection failed: ${e.message}")
            stopSelf()
            return
        }

        mediaProjection?.registerCallback(projectionCallback, android.os.Handler(mainLooper))

        hardwareEncoder.onEncoderError = {
            Log.e(tag, "Encoder error detected. Initiating Self-Healing (MICRO-10)...")
            autoRestartEncoder()
        }

        // Ensure encoder is initialized
        if (!hardwareEncoder.initialize()) {
            Log.e(tag, "Failed to initialize HardwareEncoder")
            stopSelf()
            return
        }

        setupVirtualDisplay()
        mediaProjection?.let { audioCaptureEngine.start(it) }

        Log.i(tag, "Screen capture started successfully")
    }

    private fun setupVirtualDisplay() {
        val surface = hardwareEncoder.encoderSurface
        if (surface == null) {
            Log.e(tag, "Encoder surface is null")
            return
        }

        val metrics = resources.displayMetrics
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "StreamLinkDisplay",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
    }

    private fun autoRestartEncoder() {
        android.os.Handler(mainLooper).postDelayed({
            Log.i(tag, "Self-Healing: Releasing faulty encoder...")
            virtualDisplay?.release()
            virtualDisplay = null
            hardwareEncoder.release()

            Log.i(tag, "Self-Healing: Re-initializing encoder...")
            if (hardwareEncoder.initialize()) {
                setupVirtualDisplay()
                Log.i(tag, "Self-Healing: Encoder restored successfully.")
            } else {
                Log.e(tag, "Self-Healing: Encoder restore failed.")
                stopSelf()
            }
        }, 500)
    }

    private fun stopCapture() {
        audioCaptureEngine.stop()
        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        hardwareEncoder.release()
        Log.i(tag, "Screen capture stopped")
    }

    private fun createNotification(): Notification {
        val channelId = "capture_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Screen Capture", NotificationManager.IMPORTANCE_LOW
            )
            val nm: NotificationManager? = safeSystemService(Context.NOTIFICATION_SERVICE)
            nm?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("StreamLink")
            .setContentText("Casting screen to watch...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.streamlink.START"
        const val ACTION_STOP = "com.streamlink.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
    }
}
