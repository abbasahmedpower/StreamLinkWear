package com.streamlink.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceView
import com.streamlink.wear.player.DirectStreamPlayer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@AndroidEntryPoint
class WearMainActivity : ComponentActivity() {

    @Inject lateinit var streamPlayer: DirectStreamPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : android.view.SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                streamPlayer.setSurface(holder.surface)
                                streamPlayer.start(scope)
                            }

                            override fun surfaceChanged(
                                holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int
                            ) {}

                            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                streamPlayer.setSurface(null)
                            }
                        })
                    }
                }
            )

            DisposableEffect(Unit) {
                onDispose {
                    streamPlayer.release()
                }
            }
        }
    }
}
