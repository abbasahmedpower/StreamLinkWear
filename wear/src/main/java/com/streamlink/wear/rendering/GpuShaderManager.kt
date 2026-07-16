package com.streamlink.wear.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * مدير الـ GPU الموحد: يدمج وظائف GpuRenderPipeline القديمة + GpuShaderManager القديم
 * في كلاس واحد قابل للاستخدام كـ instance مع دعم كامل للـ Dynamic FPS.
 *
 * يستبدل كلاً من:
 * - GpuRenderPipeline.kt (محذوف)
 * - GpuShaderManager.kt القديم (companion object فقط)
 */
class GpuShaderManager {

    companion object {
        private const val TAG = "GpuShaderManager"

        // Vertex Shader: يحدد أبعاد ومسار الصورة المستقبلة على الشاشة الدائرية
        private const val VERTEX_SHADER = """
            attribute vec4 vPosition;
            attribute vec2 vTexCoord;
            varying vec2 texCoord;
            void main() {
                gl_Position = vPosition;
                texCoord = vTexCoord;
            }
        """

        // Fragment Shader مع دعم Gamma Correction والتحكم في سطوع الشاشة الدائرية
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 texCoord;
            uniform samplerExternalOES uTexture;
            uniform float uGamma;
            uniform int uThermalStatus;
            void main() {
                vec4 color = texture2D(uTexture, texCoord);
                // Gamma correction لتحسين جودة الألوان على شاشة AMOLED الدائرية
                if (uGamma != 1.0) {
                    color.rgb = pow(color.rgb, vec3(1.0 / uGamma));
                }
                // تخفيف السطوع عند الإجهاد الحراري لحماية المعالج
                if (uThermalStatus == 1) {
                    color.rgb *= 0.85;
                }
                gl_FragColor = color;
            }
        """
    }

    private var programId = 0
    private var uGammaHandle = -1
    private var uThermalStatusHandle = -1
    private var aPositionHandle = -1
    private var aTexCoordHandle = -1
    private var uTextureHandle = -1

    // مخزن الإحداثيات المخصص مسبقاً لتجنب أي GC خلال الرندر
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)   // 4 vertices × (x,y,u,v) × 4 bytes
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(
                -1.0f, -1.0f,  0.0f, 0.0f,   // bottom-left
                 1.0f, -1.0f,  1.0f, 0.0f,   // bottom-right
                -1.0f,  1.0f,  0.0f, 1.0f,   // top-left
                 1.0f,  1.0f,  1.0f, 1.0f    // top-right
            ))
            position(0)
        }

    private val stride = 4 * 4  // 4 floats × 4 bytes

    var currentGamma: Float = 1.0f
    var thermalStatus: Int = 0

    /**
     * يُستدعى مرة واحدة عند إنشاء الـ GL Context (داخل onSurfaceCreated).
     */
    fun initialize(): Boolean {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        if (vertexShader == 0 || fragmentShader == 0) {
            Log.e(TAG, "فشل في تجميع الـ Shaders")
            return false
        }

        programId = GLES20.glCreateProgram()
        if (programId == 0) {
            Log.e(TAG, "فشل في إنشاء برنامج GL")
            return false
        }

        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "فشل ربط برنامج الـ GPU: ${GLES20.glGetProgramInfoLog(programId)}")
            GLES20.glDeleteProgram(programId)
            programId = 0
            return false
        }

        // احفظ مؤشرات الـ Uniforms والـ Attributes لتجنب البحث عنها في كل فريم
        aPositionHandle      = GLES20.glGetAttribLocation(programId, "vPosition")
        aTexCoordHandle      = GLES20.glGetAttribLocation(programId, "vTexCoord")
        uTextureHandle       = GLES20.glGetUniformLocation(programId, "uTexture")
        uGammaHandle         = GLES20.glGetUniformLocation(programId, "uGamma")
        uThermalStatusHandle = GLES20.glGetUniformLocation(programId, "uThermalStatus")

        // تنظيف الـ shaders الوسيطة — البرنامج المرتبط لا يحتاجها
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        Log.i(TAG, "GpuShaderManager تم التهيئة بنجاح")
        return true
    }

    /**
     * رسم فريم واحد. يُستدعى من onDrawFrame فقط عند موافقة DynamicFpsController.
     *
     * @param textureId معرف الـ OES Texture الصادر من الـ Video Decoder
     */
    fun drawFrame(textureId: Int) {
        if (programId == 0) return

        GLES20.glUseProgram(programId)

        // ربط الـ External Texture (SurfaceTexture من الـ MediaCodec)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTextureHandle, 0)

        // قيم ديناميكية بدون أي allocations
        GLES20.glUniform1f(uGammaHandle, currentGamma)
        GLES20.glUniform1i(uThermalStatusHandle, thermalStatus)

        // رسم المستطيل الكامل بـ Triangle Strip
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTexCoordHandle)
    }

    /**
     * تحرير موارد الـ GPU عند إنهاء الجلسة.
     */
    fun release() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0

        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "خطأ تجميع Shader (type=$type): ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
