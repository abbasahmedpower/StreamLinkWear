package com.streamlink.wear.rendering

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.streamlink.shared.telemetry.StreamMetricsSource

class HardenedStreamTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private var managedSurface: Surface? = null
    var onSurfaceReady: (Surface) -> Unit = {}
    var onSurfaceDestroyed: () -> Unit = {}

    // Track frame hardware timestamp
    private var lastFrameTimestamp = 0L

    // ── Dynamic FPS Controller: يوفر بطارية الساعة عند ثبات الصورة ──────────────
    private val dynamicFpsController = DynamicFpsController()

    /**
     * Set by FeaturePolicyEngine after evaluating watch hardware conditions.
     * This is the single source of truth for whether Dynamic FPS throttling is active.
     */
    @Volatile
    var isDynamicFpsEnabled: Boolean = false
        set(value) {
            field = value
            android.util.Log.i("HardenedStreamTextureView", "Dynamic FPS → $value")
        }

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
                    StreamMetricsSource.active?.recordDrop()
                    return
                }
            }

            // recordFrame(bytes=0): payload size isn't known at the render layer —
            // MirrorDataPlane/DirectSocketClient already record wire bytes for
            // bandwidth. This call's job is fps + render timing only.
            StreamMetricsSource.active?.recordFrame(0)
            StreamMetricsSource.active?.recordFrameTiming(decodeMs = 0f, renderMs = renderTimeMs)
            // In a real implementation, we would also record bytes here based on the network payload size
        }
        lastFrameTimestamp = now
    }
}
