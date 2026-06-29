package com.streamlink.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import com.streamlink.shared.TouchEvent
import com.streamlink.shared.TouchPhase
import java.lang.reflect.Method

/**
 * RemoteControlAccessibilityService receives normalized touch events from the Watch
 * and injects them into the Android system.
 * 
 * Includes a Hybrid injection strategy:
 * 1. Tries to use InputManager.injectInputEvent (via reflection) for ultra-low latency.
 * 2. Falls back to AccessibilityService GestureDescription if InputManager fails (requires INJECT_EVENTS permission, usually system apps only).
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    private val tag = "RemoteControlService"
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    private var inputManager: InputManager? = null
    private var injectInputEventMethod: Method? = null
    private var canUseInputManager = false

    companion object {
        var instance: RemoteControlAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(tag, "RemoteControlAccessibilityService connected")
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels

        trySetupInputManager()
    }

    private fun trySetupInputManager() {
        try {
            inputManager = getSystemService(INPUT_SERVICE) as InputManager
            injectInputEventMethod = InputManager::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            canUseInputManager = true
            Log.i(tag, "InputManager reflection successful. Using Ultra-low latency injection.")
        } catch (e: Exception) {
            canUseInputManager = false
            Log.w(tag, "InputManager reflection failed. Falling back to GestureDescription: ${e.message}")
        }
    }

    fun handle(event: TouchEvent) {
        val px = event.nx * screenWidth
        val py = event.ny * screenHeight

        if (canUseInputManager) {
            if (injectViaInputManager(event.phase, px, py)) {
                return
            }
        }
        
        injectViaAccessibility(event.phase, px, py)
    }

    private var downTime: Long = 0

    private fun injectViaInputManager(phase: TouchPhase, x: Float, y: Float): Boolean {
        try {
            val action = when (phase) {
                TouchPhase.DOWN -> MotionEvent.ACTION_DOWN
                TouchPhase.MOVE -> MotionEvent.ACTION_MOVE
                TouchPhase.UP -> MotionEvent.ACTION_UP
                TouchPhase.CANCEL -> MotionEvent.ACTION_CANCEL
            }

            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now

            val me = MotionEvent.obtain(
                downTime,
                now,
                action,
                x,
                y,
                0
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }

            // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            injectInputEventMethod?.invoke(inputManager, me, 0)
            me.recycle()
            return true
        } catch (e: Exception) {
            Log.e(tag, "InputManager injection failed: ${e.message}")
            canUseInputManager = false
            return false
        }
    }

    private fun injectViaAccessibility(phase: TouchPhase, x: Float, y: Float) {
        // Fallback to Accessibility Service
        val path = Path().apply { moveTo(x, y) }
        
        // Fixed 16ms continuation
        val duration = 16L 
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance == this) {
            instance = null
        }
        return super.onUnbind(intent)
    }
}
