package com.streamlink.shared.ai

import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

class LocalPredictiveEngine(assetManager: AssetManager, modelPath: String) {
    
    private var interpreter: Interpreter? = null
    
    // Allocate ByteBuffers natively to avoid Allocations during streaming
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    
    // Model takes: last 5 frame sizes, current jitter, IMU variance (Total 7 Float inputs)
    private val inputSize = 7 * 4 // 7 floats * 4 bytes
    // Outputs: predicted frame size (Float) and congestion risk (Float)
    private val outputSize = 2 * 4 // 2 floats * 4 bytes

    init {
        inputBuffer = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder())

        interpreter = try {
            val modelBuffer = loadModelFile(assetManager, modelPath)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseNNAPI(true)
            }
            Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            android.util.Log.w("SharedPredictiveEngine", "Model load failed ($modelPath): ${e.message}")
            null
        }
    }

    /**
     * Allocation-free real-time prediction (NASA-Grade Inference)
     */
    @Synchronized
    fun predictNextFrameMetrics(
        last5FrameSizes: FloatArray,
        currentJitter: Float,
        imuVariance: Float
    ): PredictionResult {
        require(last5FrameSizes.size == 5) {
            "predictNextFrameMetrics expects exactly 5 frame sizes, got ${last5FrameSizes.size}"
        }

        val interp = interpreter ?: return PredictionResult(0f, 0f)

        inputBuffer.clear()
        
        // Pump inputs directly into Native Buffer
        for (size in last5FrameSizes) {
            inputBuffer.putFloat(size)
        }
        inputBuffer.putFloat(currentJitter)
        inputBuffer.putFloat(imuVariance)

        outputBuffer.clear()
        
        // Run mathematical processing on the CPU
        interp.run(inputBuffer, outputBuffer)
        
        outputBuffer.rewind()
        val predictedSize = outputBuffer.float
        val congestionRisk = outputBuffer.float

        return PredictionResult(predictedSize, congestionRisk)
    }

    private fun loadModelFile(assetManager: AssetManager, modelPath: String): ByteBuffer {
        val fileDescriptor: AssetFileDescriptor = assetManager.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel: FileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    @Synchronized
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}

data class PredictionResult(val predictedFrameSize: Float, val congestionRisk: Float)
