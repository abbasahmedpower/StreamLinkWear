package com.streamlink.wear.rendering

import android.opengl.GLES20
import android.util.Log

class GpuShaderManager {
    companion object {
        private const val TAG = "GpuShaderManager"

        // Vertex Shader: يحدد أبعاد ومسار الصورة المستقبلة على الشاشة الدائرية
        private const val VERTEX_SHADER_CODE = """
            attribute vec4 vPosition;
            attribute vec2 vTexCoord;
            varying vec2 texCoord;
            void main() {
                gl_Position = vPosition;
                texCoord = vTexCoord;
            }
        """

        // Fragment Shader: يقوم بتحويل الألوان وحسابات الـ Hardware Textures فوراً
        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            varying vec2 texCoord;
            uniform sampler2D textureY;
            void main() {
                gl_FragColor = texture2D(textureY, texCoord);
            }
        """

        /**
         * بناء وتجميع الـ Graphics Pipeline على كارت الشاشة المدمج
         */
        fun createGlProgram(): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

            if (vertexShader == 0 || fragmentShader == 0) {
                Log.e(TAG, "فشل في تجميع خطوط الـ Shaders البرمجية.")
                return 0
            }

            val program = GLES20.glCreateProgram()
            if (program != 0) {
                GLES20.glAttachShader(program, vertexShader)
                GLES20.glAttachShader(program, fragmentShader)
                GLES20.glLinkProgram(program)
                
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] == 0) {
                    Log.e(TAG, "فشل ربط برنامج الـ GPU المشترك: " + GLES20.glGetProgramInfoLog(program))
                    GLES20.glDeleteProgram(program)
                    return 0
                }
            }
            return program
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader != 0) {
                GLES20.glShaderSource(shader, shaderCode)
                GLES20.glCompileShader(shader)
                val compiled = IntArray(1)
                GLES20.glCompileShader(shader)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
                if (compiled[0] == 0) {
                    Log.e(TAG, "خطأ تجميع في الـ Shader من النوع ($type): " + GLES20.glGetShaderInfoLog(shader))
                    GLES20.glDeleteShader(shader)
                    return 0
                }
            }
            return shader
        }
    }
}
