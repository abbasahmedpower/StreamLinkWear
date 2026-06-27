package com.streamlink.wear.ui

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceView
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import com.streamlink.wear.player.DirectStreamPlayer
import com.streamlink.wear.service.WearForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WearMainActivity : ComponentActivity() {

    @Inject lateinit var streamPlayer: DirectStreamPlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isAmbient = false

    /**
     * Ambient mode observer — activates when watch screen dims.
     * We DON'T stop streaming in ambient; we reduce it to ECO quality.
     */
    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                isAmbient = true
                Log.i("WearMain", "Entering ambient mode — keeping stream alive at ECO quality")
                // Keep ForegroundService alive, reduce visual quality
                // QualityController already handles this via thermalLevel/isUserMoving signals
            }

            override fun onExitAmbient() {
                isAmbient = false
                Log.i("WearMain", "Exiting ambient mode — restoring full quality")
                // Renderer will auto-recover; request IDR for clean frame
                // The stream is already running (never stopped)
            }

            override fun onUpdateAmbient() {
                // Called every 60s in ambient — update any ambient UI if needed
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen ON during active streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Register ambient lifecycle
        lifecycle.addObserver(ambientObserver)

        // Start background service immediately
        WearForegroundService.start(this)

        setContent {
            var surfaceReady by remember { mutableStateOf(false) }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : android.view.SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                streamPlayer.setSurface(holder.surface)
                                streamPlayer.start(scope)
                                surfaceReady = true
                                Log.i("WearMain", "Surface ready — streaming started")
                            }
                            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {
                                Log.d("WearMain", "Surface changed ${w}x${h}")
                            }
                            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                streamPlayer.setSurface(null)
                                surfaceReady = false
                            }
                        })
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Wrist raise restores from ambient — no action needed,
        // AmbientLifecycleObserver.onExitAmbient() handles it
    }

    override fun onStop() {
        super.onStop()
        // Activity goes background — ForegroundService keeps streaming
        // DO NOT release streamPlayer here
        Log.i("WearMain", "Activity stopped — streaming continues in ForegroundService")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only release when explicitly stopped by user
        if (isFinishing) {
            WearForegroundService.stop(this)
            streamPlayer.release()
        }
    }
}
