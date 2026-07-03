package com.streamlink.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import com.streamlink.shared.TouchEvent
import com.streamlink.shared.TouchPhase
import java.lang.reflect.Method

class RemoteControlAccessibilityService : AccessibilityService() {

    private val tag = "RemoteControlService"
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    private var inputManager: InputManager? = null
    private var injectInputEventMethod: Method? = null
    // هيفضل false في 99% من التنصيبات العادية (بيحتاج INJECT_EVENTS وهو signature-permission)
    private var canUseInputManager = false

    companion object {
        var instance: RemoteControlAccessibilityService? = null
            private set
        private const val SEGMENT_MS = 40L // نافذة تجميع الحركة قبل كل dispatch
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        trySetupInputManager()
    }

    private fun trySetupInputManager() {
        // فحص حقيقي للـpermission قبل أي محاولة — مش هنعتمد على catch بس
        val granted = checkCallingOrSelfPermission("android.permission.INJECT_EVENTS") ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(tag, "INJECT_EVENTS not granted (متوقع على أجهزة السوق العادية) → استخدام GestureDescription فقط")
            canUseInputManager = false
            return
        }
        try {
            inputManager = getSystemService(INPUT_SERVICE) as InputManager
            injectInputEventMethod = InputManager::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
            canUseInputManager = true
            Log.i(tag, "INJECT_EVENTS ممنوح → استخدام المسار السريع")
        } catch (e: Exception) {
            canUseInputManager = false
            Log.w(tag, "InputManager reflection failed: ${e.message}")
        }
    }

    // ── مسار الـGesture المستمر (الافتراضي على أي جهاز حقيقي) ──────────────
    private data class StrokeSession(
        var lastStroke: GestureDescription.StrokeDescription?,
        var lastX: Float,
        var lastY: Float,
        var pendingPath: Path,
        var hasPending: Boolean,
        var isDispatchInFlight: Boolean,
        var isDown: Boolean
    )

    private val sessions = HashMap<Int, StrokeSession>()
    private val lock = Any()

    fun handle(event: TouchEvent) {
        val px = (event.nx * screenWidth).coerceIn(0f, (screenWidth - 1).toFloat())
        val py = (event.ny * screenHeight).coerceIn(0f, (screenHeight - 1).toFloat())

        if (canUseInputManager && injectViaInputManager(event.phase, px, py)) return

        synchronized(lock) {
            when (event.phase) {
                TouchPhase.DOWN -> onDown(event.pointerId, px, py)
                TouchPhase.MOVE -> onMove(event.pointerId, px, py)
                TouchPhase.UP, TouchPhase.CANCEL -> onUp(event.pointerId, px, py)
            }
        }
    }

    private fun onDown(id: Int, x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        sessions[id] = StrokeSession(null, x, y, path, hasPending = true, isDispatchInFlight = false, isDown = true)
        dispatchNextSegment(id)
    }

    private fun onMove(id: Int, x: Float, y: Float) {
        val s = sessions[id] ?: return onDown(id, x, y) // لو ضاعت الجلسة (تأخر شبكة)، ابدأ من جديد
        s.pendingPath.lineTo(x, y)
        s.lastX = x; s.lastY = y
        s.hasPending = true
        if (!s.isDispatchInFlight) dispatchNextSegment(id)
    }

    private fun onUp(id: Int, x: Float, y: Float) {
        val s = sessions[id] ?: return
        s.pendingPath.lineTo(x, y)
        s.lastX = x; s.lastY = y
        s.hasPending = true
        s.isDown = false // آخر segment، من غير willContinue
        if (!s.isDispatchInFlight) dispatchNextSegment(id)
    }

    private fun dispatchNextSegment(id: Int) {
        val s = sessions[id] ?: return
        if (!s.hasPending) return

        val willContinue = s.isDown
        val pathToSend = s.pendingPath
        // continueStroke is an instance method (API 26+) — call on the previous stroke, not as static
        val strokeDesc: GestureDescription.StrokeDescription =
            s.lastStroke?.continueStroke(pathToSend, 0, SEGMENT_MS, willContinue)
                ?: GestureDescription.StrokeDescription(pathToSend, 0, SEGMENT_MS, willContinue)

        val gesture = GestureDescription.Builder().addStroke(strokeDesc).build()
        s.isDispatchInFlight = true
        s.hasPending = false
        s.pendingPath = Path().apply { moveTo(s.lastX, s.lastY) } // segment القادم يبدأ من نفس النقطة

        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                synchronized(lock) {
                    s.isDispatchInFlight = false
                    s.lastStroke = strokeDesc
                    if (!willContinue) {
                        sessions.remove(id)
                    } else if (s.hasPending) {
                        dispatchNextSegment(id)
                    }
                    Unit
                }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                synchronized(lock) {
                    s.isDispatchInFlight = false
                    sessions.remove(id)
                }
            }
        }, null)

        if (!ok) { s.isDispatchInFlight = false }
    }

    // ── مسار InputManager السريع (بيشتغل بس لو الـpermission ممنوح فعليًا) ──
    private var downTime: Long = 0

    private fun injectViaInputManager(phase: TouchPhase, x: Float, y: Float): Boolean {
        return try {
            val action = when (phase) {
                TouchPhase.DOWN -> MotionEvent.ACTION_DOWN
                TouchPhase.MOVE -> MotionEvent.ACTION_MOVE
                TouchPhase.UP -> MotionEvent.ACTION_UP
                TouchPhase.CANCEL -> MotionEvent.ACTION_CANCEL
            }
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val me = MotionEvent.obtain(downTime, now, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            val result = injectInputEventMethod?.invoke(inputManager, me, 0)
            me.recycle()
            result != null
        } catch (e: Exception) {
            Log.e(tag, "InputManager injection failed, disabling: ${e.message}")
            canUseInputManager = false
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance == this) instance = null
        synchronized(lock) { sessions.clear() }
        return super.onUnbind(intent)
    }
}
