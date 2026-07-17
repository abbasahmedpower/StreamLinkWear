package com.streamlink.wear.rendering

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.streamlink.shared.telemetry.TelemetryCollector

class HardenedStreamTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private var managedSurface: Surface? = null
    var onSurfaceReady: (Surface) -> Unit = {}
    var onSurfaceDestroyed: () -> Unit = {}

    // Track frame hardware timestamp
    private var lastFrameTimestamp = 0L

    // ── Dynamic FPS Controller: يوفر بطارية الساعة عند ثبات الصورة ──────────────
    private val dynamicFpsController = DynamicFpsController()

    /**
     * يُستدعى من الخارج (ViewModel أو StreamingOrchestrator على الساعة)
     * لتفعيل/تعطيل ميزة توفير الطاقة عند تغيير الإعدادات.
     */
    @Volatile
    var isDynamicFpsEnabled: Boolean = true

    // مخزن مؤقت ثابت الحجم لعينات الـ Hash — بدون أي GC
    private val hashSampleBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(32 * 4)
        .order(ByteOrder.nativeOrder())

    init {
        surfaceTextureListener = this
        isOpaque = true // Micro-optimization: Tells Compositor to disable overdraw under video
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        // Nano-careful Surface reuse
        managedSurface?.release()
        managedSurface = Surface(texture)
        val surface = managedSurface ?: run {
            Log.e("HardenedStreamTextureView", "Failed to create Surface from SurfaceTexture")
            return
        }
        onSurfaceReady(surface)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        // Handle Dynamic Resolution Scaling here from Predictive Engine
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        onSurfaceDestroyed()
        managedSurface?.release()
        managedSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastFrameTimestamp != 0L) {
            val renderTimeMs = (now - lastFrameTimestamp) / 1_000_000f

            // فحص Dynamic FPS: هل يجب رسم هذا الفريم أم تخطيه؟
            if (isDynamicFpsEnabled) {
                // نستخدم مخزن الـ Hash الثابت بدلاً من getBitmap() لتجنب أي GC Pressure
                hashSampleBuffer.clear()
                val shouldRender = dynamicFpsController.shouldRender(hashSampleBuffer)
                if (!shouldRender) {
                    // تخطي تسجيل المقاييس وتحديث الـ overlay لتوفير المعالجة
                    lastFrameTimestamp = now
                    TelemetryCollector.recordFrameDrop()
                    return
                }
            }

            TelemetryCollector.recordFrame()
            TelemetryCollector.recordLatency(renderTimeMs.toLong())
            // In a real implementation, we would also record bytes here based on the network payload size
        }
        lastFrameTimestamp = now
    }
}
