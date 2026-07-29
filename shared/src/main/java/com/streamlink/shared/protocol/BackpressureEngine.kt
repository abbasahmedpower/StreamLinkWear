package com.streamlink.shared.protocol

import android.util.Log
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.ThermalMonitor

/**
 * Phase 2 — BackpressureEngine: Multi-dimensional pressure scoring
 *
 * يحل محل الاعتماد على queue.size وحده في BackpressureController.
 *
 * المشكلة مع queue.size وحده:
 * - تليفون سخن (thermal throttling) → مشكلة قبل ما الـ queue يكبر
 * - باتري منخفضة → encoder بيبطأ قبل ما الشبكة تشتكي
 * - RTT ارتفع → الـ queue لسه فاضي لكن congestion واقع
 *
 * الحل: Weighted multi-dimensional score [0..100]:
 * - 0-30:  Green  → رفع الـ bitrate (400Kbps خطوات)
 * - 30-60: Yellow → ثبّت الـ bitrate
 * - 60-80: Orange → خفّض الـ bitrate (15%)
 * - 80+:   Red    → خفّض بشكل حاد (40%) + طلب IDR
 *
 * ⚠️ الأوزان دي مبدئية — لازم تتظبط من Perfetto trace + battery log على جهاز حقيقي.
 * أي حد يقولك "الأوزان دي نهائية" بدون قياس بيكذب عليك (من المراجعة، القسم 5.5).
 */
class BackpressureEngine(
    /** الـ RTT المستهدف (ms) — الوضع الطبيعي لشبكة WiFi Direct/LAN */
    private val targetRttMs: Int = 60
) {

    /**
     * مدخلات التليمتري — كلها مجمعة في data class لسهولة الاختبار (Testability):
     * يمكن كتابة unit tests بدون تشغيل شبكة حقيقية.
     */
    data class TelemetrySample(
        /** RTT الحقيقي (ms) — من FrameWindow.rttMs() */
        val rttMs: Int,
        /** نسبة الفقدان [0.0 - 1.0] — من GopFrameDropper.dropRate أو ACK missing */
        val lossPct: Float,
        /** Thermal state [0..3]: 0=NONE, 1=LIGHT, 2=MODERATE, 3=HEAVY+ */
        val thermalState: Int,
        /** عمر أقدم chunk في الـ queue (ms) — proxy لعمق الازدحام */
        val queueAgeMs: Int,
        /** شحن البطارية [0..100] */
        val batteryPct: Int,
        /** CPU load [0..100] */
        val cpuLoadPct: Int,
        /** عدد الـ unacked frames في الـ FrameWindow */
        val unackedFrames: Int = 0,
    )

    /** نتيجة التقييم — تحدد الإجراء المطلوب */
    data class PressureResult(
        /** الضغط الكلي [0..100] */
        val score: Int,
        /** الإجراء المقترح */
        val action: Action,
        /** تفصيل المساهمات لكل مقياس (للـ debugging) */
        val breakdown: Breakdown,
    ) {
        enum class Action {
            INCREASE_BITRATE,  // score < 30
            HOLD,              // 30 ≤ score < 60
            REDUCE_BITRATE,    // 60 ≤ score < 80
            REDUCE_HARD,       // score ≥ 80 → reduce + request IDR
        }

        data class Breakdown(
            val rttScore: Float,
            val lossScore: Float,
            val thermalScore: Float,
            val queueScore: Float,
            val batteryScore: Float,
            val cpuScore: Float,
        ) {
            override fun toString(): String =
                "rtt=%.0f loss=%.0f thermal=%.0f queue=%.0f battery=%.0f cpu=%.0f".format(
                    rttScore, lossScore, thermalScore, queueScore, batteryScore, cpuScore
                )
        }
    }

    // ── Weights (مبدئية — تتظبط من قياسات حقيقية) ────────────────────────────
    private object Weights {
        const val RTT      = 0.20f   // RTT أساسي لكن مش الوحيد
        const val LOSS     = 0.30f   // فقدان الحزم أهم إشارة congestion
        const val THERMAL  = 0.20f   // حرارة الهاتف تأثيرها مباشر على الـ encoder
        const val QUEUE    = 0.15f   // عمق الـ queue: تأخير مرئي لو كبر
        const val BATTERY  = 0.10f   // بطارية منخفضة → throttle كل حاجة
        const val CPU      = 0.05f   // CPU load: أقل وزن لأنه مش دايماً correlated
    }

    // ── State (للـ smoothing — تمنع قرارات متذبذبة) ──────────────────────────
    @Volatile private var lastScore = 0
    @Volatile private var consecutiveHighPressure = 0
    private val tag = "BackpressureEngine"

    // ── Main scoring ──────────────────────────────────────────────────────────

    fun computePressure(t: TelemetrySample): PressureResult {
        // كل مقياس يُطبَّع على [0..100]
        val rttScore = ((t.rttMs.toFloat() / targetRttMs).coerceIn(0f, 3f) / 3f) * 100f
        val lossScore = (t.lossPct.coerceIn(0f, 0.20f) / 0.20f) * 100f
        val thermalScore = when (t.thermalState) {
            0    -> 0f    // NONE — no thermal pressure
            1    -> 30f   // LIGHT
            2    -> 65f   // MODERATE — significant concern on Wear OS
            else -> 100f  // HEAVY/SEVERE/CRITICAL
        }
        val queueScore = (t.queueAgeMs.toFloat() / 200f).coerceIn(0f, 1f) * 100f
        val batteryScore = when {
            t.batteryPct < 10 -> 100f  // Critical — throttle everything
            t.batteryPct < 15 -> 70f
            t.batteryPct < 25 -> 30f
            else -> 0f
        }
        val cpuScore = t.cpuLoadPct.toFloat().coerceIn(0f, 100f)

        val raw = rttScore * Weights.RTT +
                  lossScore * Weights.LOSS +
                  thermalScore * Weights.THERMAL +
                  queueScore * Weights.QUEUE +
                  batteryScore * Weights.BATTERY +
                  cpuScore * Weights.CPU

        // ── Hysteresis: تجنب تذبذب سريع بين reduce وincrease ──
        val smoothed = (lastScore * 0.3f + raw * 0.7f).toInt().coerceIn(0, 100)
        lastScore = smoothed

        // تتبع الضغط المتواصل
        if (smoothed >= 60) consecutiveHighPressure++ else consecutiveHighPressure = 0

        val action = when {
            smoothed >= 80 -> PressureResult.Action.REDUCE_HARD
            smoothed >= 60 -> PressureResult.Action.REDUCE_BITRATE
            smoothed < 30 && consecutiveHighPressure == 0 -> PressureResult.Action.INCREASE_BITRATE
            else -> PressureResult.Action.HOLD
        }

        Log.d(tag, "Pressure=$smoothed ($action) [${PressureResult.Breakdown(
            rttScore, lossScore, thermalScore, queueScore, batteryScore, cpuScore
        )}]")

        return PressureResult(
            score = smoothed,
            action = action,
            breakdown = PressureResult.Breakdown(
                rttScore, lossScore, thermalScore, queueScore, batteryScore, cpuScore
            )
        )
    }

    // ── Helper: map Android thermal status to [0..3] ──────────────────────────
    companion object {
        /**
         * يحوّل [PowerManager.currentThermalStatus] لـ thermalState [0..3]
         * للاستخدام في [TelemetrySample.thermalState].
         */
        fun thermalStatusToState(powerManagerStatus: Int): Int = when (powerManagerStatus) {
            0, 1 -> 0   // NONE, LIGHT → no pressure
            2    -> 1   // MODERATE → light
            3    -> 2   // SEVERE → moderate concern
            else -> 3   // CRITICAL, EMERGENCY, SHUTDOWN → max pressure
        }

        /**
         * Recommended bitrate multipliers per action.
         * These are starting values — tune from real measurements.
         */
        fun bitrateMultiplier(action: PressureResult.Action): Float = when (action) {
            PressureResult.Action.INCREASE_BITRATE -> 1.12f   // +12%
            PressureResult.Action.HOLD             -> 1.00f
            PressureResult.Action.REDUCE_BITRATE   -> 0.85f   // -15%
            PressureResult.Action.REDUCE_HARD      -> 0.60f   // -40%
        }
    }
}
