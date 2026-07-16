package com.streamlink.app.core

import java.util.concurrent.atomic.AtomicReference

enum class TouchControlSource {
    NONE,
    TCP_SOCKET,
    WEBRTC_DATA_CHANNEL
}

/**
 * حكم مرور أحداث اللمس - يمنع تداخل مسارات التحكم المختلفة في نفس الوقت.
 */
class TouchInputArbitrator {
    private val activeSource = AtomicReference<TouchControlSource>(TouchControlSource.NONE)
    private var lastEventTime = 0L
    private val idleTimeoutMs = 1500L // إذا لم يرسل المصدر النشط أي حدث لمدة ثانية ونصف، نفقد السيطرة ونسمح للآخر بالدخول

    fun shouldAcceptEvent(incomingSource: TouchControlSource): Boolean {
        val now = System.currentTimeMillis()
        val current = activeSource.get()

        if (current == TouchControlSource.NONE) {
            if (activeSource.compareAndSet(TouchControlSource.NONE, incomingSource)) {
                lastEventTime = now
                return true
            }
        }

        if (current == incomingSource) {
            lastEventTime = now
            return true
        }

        // تفقد إذا كان المصدر الحالي خاملاً (Timeout) لتحرير التحكيم تلقائياً
        if (now - lastEventTime > idleTimeoutMs) {
            if (activeSource.compareAndSet(current, incomingSource)) {
                lastEventTime = now
                return true
            }
        }

        return false
    }

    fun forceRelease() {
        activeSource.set(TouchControlSource.NONE)
    }
}
