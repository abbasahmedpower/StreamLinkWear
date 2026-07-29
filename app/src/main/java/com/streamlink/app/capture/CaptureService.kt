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
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.app.core.safeExec
import com.streamlink.app.core.safeRun
import com.streamlink.shared.ThermalMonitor
import com.streamlink.shared.util.ResourceRegistry
import com.streamlink.shared.util.safeSystemService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@AndroidEntryPoint
class CaptureService : Service() {
    private val tag = "CaptureService"

    @Inject lateinit var orchestrator: StreamingOrchestrator
    @Inject lateinit var hardwareEncoder: HardwareEncoder
    @Inject lateinit var directSocketServer: com.streamlink.shared.DirectSocketServer
    @Inject lateinit var audioCaptureEngine: AudioCaptureEngine
    @Inject lateinit var thermalMonitor: ThermalMonitor

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val resourceRegistry = ResourceRegistry()

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    }
                    startForeground(1, createNotification(), type)
                } else {
                    startForeground(1, createNotification())
                }
                startCapture(resultCode, data)
            }
        } else if (action == ACTION_STOP) {
            stopCapture()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        // ── MediaProjection setup ────────────────────────────────────────────────
        val mpm = safeRun(tag, "getMediaProjectionManager") {
            safeSystemService<MediaProjectionManager>(Context.MEDIA_PROJECTION_SERVICE)
        } ?: run {
            com.streamlink.shared.diagnostics.StartupDiagnostics.warn(tag, "MediaProjectionManager is null")
            stopSelf(); return
        }

        val projection = safeRun(tag, "getMediaProjection", report = true) {
            mpm.getMediaProjection(resultCode, data)
        } ?: run {
            com.streamlink.shared.diagnostics.StartupDiagnostics.warn(tag, "MediaProjection is null")
            stopSelf(); return
        }
        mediaProjection = projection
        com.streamlink.shared.diagnostics.StartupDiagnostics.ok("CaptureService MediaProjection started")

        projection.registerCallback(projectionCallback, android.os.Handler(mainLooper))

        // ── Encoder error self-healing ───────────────────────────────────────────
        hardwareEncoder.onEncoderError = {
            Log.e(tag, "Encoder error detected. Initiating Self-Healing (MICRO-10)...")
            autoRestartEncoder()
        }

        hardwareEncoder.onSurfaceChanged = { newSurface ->
            safeExec(tag, "hotSwapVirtualDisplaySurface", report = true) {
                virtualDisplay?.setSurface(newSurface)
                virtualDisplay?.resize(
                    hardwareEncoder.currentWidth,
                    hardwareEncoder.currentHeight,
                    resources.displayMetrics.densityDpi
                )
                Log.i(tag, "VirtualDisplay surface hot-swapped to new Encoder surface")
            }.also { success ->
                if (success == null) autoRestartEncoder()
            }
        }

        // ── Encoder initialisation ───────────────────────────────────────────────
        if (!hardwareEncoder.initialize()) {
            Log.e(tag, "Failed to initialize HardwareEncoder")
            stopSelf(); return
        }

        // ── Thermal throttling: early response before OS steps in ────────────────
        // SEVERE  (level 7): halve bitrate to shed heat immediately
        // CRITICAL (level 9): pause stream entirely until cool-down
        safeExec(tag, "thermalMonitor.start") { thermalMonitor.start() }

        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
        ).also { scope ->
            thermalMonitor.thermalLevel.onEach { level ->
                when {
                    level >= 9 -> {
                        Log.w(tag, "Thermal CRITICAL ($level/10) — pausing stream")
                        safeExec(tag, "pauseVideoForThermal", report = true) {
                            orchestrator.pauseVideo()
                        }
                    }
                    level >= 7 -> {
                        Log.w(tag, "Thermal SEVERE ($level/10) — halving bitrate")
                        safeExec(tag, "setBitrateForThermal", report = true) {
                            hardwareEncoder.setBitrate(
                                (hardwareEncoder.currentBitrateKbps() * 0.5).toInt().coerceAtLeast(200)
                            )
                        }
                    }
                    else -> { /* nominal — no action */ }
                }
            }.launchIn(scope)
        }

        setupVirtualDisplay()
        projection.let { audioCaptureEngine.start(it) }

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
        )?.also { resourceRegistry.register(it) }
    }

    private var isRestartingEncoder = false
    private fun autoRestartEncoder() {
        if (isRestartingEncoder) return
        isRestartingEncoder = true
        android.os.Handler(mainLooper).postDelayed({
            try {
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
            } finally {
                isRestartingEncoder = false
            }
        }, 500)
    }

    private fun stopCapture() {
        audioCaptureEngine.stop()
        safeExec(tag, "thermalMonitor.stop") { thermalMonitor.stop() }
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

    override fun onDestroy() {
        // ✅ N2 FIX: Emergency safety net — OS killed the service (Low Memory / System trim).
        // Guarantees MediaProjection, VirtualDisplay, and HardwareEncoder are always released.
        if (mediaProjection != null || virtualDisplay != null) {
            Log.w(tag, "onDestroy called while capture was active — performing emergency cleanup")
            stopCapture()
        }
        resourceRegistry.close()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // ✅ N2 FIX: User swiped the app from Recents — force clean teardown
        // so the foreground service and MediaProjection don't linger in the background.
        Log.i(tag, "Task removed by user — forcing stopCapture and self-destruction")
        safeExec(tag, "stopCaptureOnTaskRemoved") { stopCapture() }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.streamlink.START"
        const val ACTION_STOP  = "com.streamlink.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA        = "data"
    }
}
