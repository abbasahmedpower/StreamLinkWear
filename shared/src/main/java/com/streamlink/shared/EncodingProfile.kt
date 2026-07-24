package com.streamlink.shared

/**
 * EncodingProfile — التمثيل الموحد الوحيد لحالة الإنكودر الكاملة.
 *
 * يحل محل الاستخدام المتفرق لـ ResolutionProfile + متغيرات bitrate/fps منفصلة.
 * كل تغيير على HardwareEncoder يجب أن يمر عبر هذا الكائن فقط.
 */
data class EncodingProfile(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int
) {
    companion object {
        /** FULL: أعلى جودة — شبكة ممتازة وحرارة منخفضة */
        fun full() = EncodingProfile(
            label      = "FULL",
            width      = StreamProtocol.WEAR_W_FULL,
            height     = StreamProtocol.WEAR_H_FULL,
            fps        = StreamProtocol.WEAR_FPS_FULL,
            bitrateKbps = StreamProtocol.WEAR_BPS_FULL
        )

        /** ECO: جودة موفّرة — شبكة متوسطة أو حرارة مرتفعة */
        fun eco() = EncodingProfile(
            label      = "ECO",
            width      = StreamProtocol.WEAR_W_ECO,
            height     = StreamProtocol.WEAR_H_ECO,
            fps        = StreamProtocol.WEAR_FPS_ECO,
            bitrateKbps = StreamProtocol.WEAR_BPS_ECO
        )

        /** يبني profile من StreamingProfile القديم للتوافق مع DecisionEngine الحالي */
        fun fromStreamingMultiplier(
            profile: String,
            bitrateMultiplier: Float,
            targetFps: Int,
            baseProfile: EncodingProfile = full()
        ) = baseProfile.copy(
            label       = profile,
            fps         = targetFps,
            bitrateKbps = (baseProfile.bitrateKbps * bitrateMultiplier).toInt().coerceAtLeast(200)
        )

        /** تحويل ResolutionProfile القديم لـ EncodingProfile للتوافق */
        fun from(rp: ResolutionProfile) = EncodingProfile(
            label       = rp.label,
            width       = rp.width,
            height      = rp.height,
            fps         = rp.fps,
            bitrateKbps = rp.bitrateKbps
        )
    }

    /** تحويل عكسي لـ ResolutionProfile عند الحاجة لـ APIs قديمة */
    fun toResolutionProfile() = ResolutionProfile(label, width, height, fps, bitrateKbps)
}
