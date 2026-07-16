package com.streamlink.wear.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.PowerManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class GpuRenderPipeline {

    private var programId = 0
    private var textureId = 0
    
    // Shader Uniform Handles
    private var uGammaHandle = 0
    private var uThermalStatusHandle = 0

    // Direct Memory Flat Render Coordinates to avoid GC
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(32)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(floatArrayOf(
                -1.0f, -1.0f, 0.0f, 0.0f,
                 1.0f, -1.0f, 1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f, 1.0f,
                 1.0f,  1.0f, 1.0f, 1.0f
            ))
            position(0)
        }

    fun initializeGl(vertexShaderSource: String, fragmentShaderSource: String) {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)
        
        programId = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, fragmentShader)
            GLES20.glLinkProgram(this)
        }

        uGammaHandle = GLES20.glGetUniformLocation(programId, "uGamma")
        uThermalStatusHandle = GLES20.glGetUniformLocation(programId, "uThermalStatus")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    }

    /**
     * Render Pulse: Called 30-60 times a second.
     */
    fun renderFrame(currentGamma: Float, thermalStatus: Int) {
        GLES20.glUseProgram(programId)

        // Inject dynamic values live with NO ALLOCATIONS
        GLES20.glUniform1f(uGammaHandle, currentGamma)
        GLES20.glUniform1i(uThermalStatusHandle, if (thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT) 1 else 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }
    }
}
