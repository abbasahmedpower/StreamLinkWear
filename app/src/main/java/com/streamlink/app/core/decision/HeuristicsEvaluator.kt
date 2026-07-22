package com.streamlink.app.core.decision

object HeuristicsEvaluator {

    // Architectural Weights (Sum = 1.0)
    private const val WEIGHT_RTT = 0.35f
    private const val WEIGHT_THERMAL = 0.25f
    private const val WEIGHT_PACKET_LOSS = 0.20f
    private const val WEIGHT_DECODER_DROPS = 0.15f
    private const val WEIGHT_BATTERY = 0.05f

    fun calculateHealthScore(snapshot: TelemetrySnapshot): Float {
        val scoreRtt = normalizeRtt(snapshot.rttMs)
        val scoreThermal = normalizeThermal(snapshot.thermalCelsius)
        val scoreLoss = normalizePacketLoss(snapshot.packetLossPercent)
        val scoreDrops = normalizeDrops(snapshot.decoderDroppedFrames)
        val scoreBattery = normalizeBattery(snapshot.batteryPercent)

        return (scoreRtt * WEIGHT_RTT) +
               (scoreThermal * WEIGHT_THERMAL) +
               (scoreLoss * WEIGHT_PACKET_LOSS) +
               (scoreDrops * WEIGHT_DECODER_DROPS) +
               (scoreBattery * WEIGHT_BATTERY)
    }

    // --- Nano-level: Linear Interpolation for Normalization (0 to 100) ---

    // RTT: < 20ms = 100, > 100ms = 0
    @Suppress("NOTHING_TO_INLINE")
    private inline fun normalizeRtt(rtt: Int): Float =
        (100f - ((rtt - 20) * (100f / 80f))).coerceIn(0f, 100f)

    // Thermal: < 35C = 100, > 50C = 0
    @Suppress("NOTHING_TO_INLINE")
    private inline fun normalizeThermal(temp: Float): Float =
        (100f - ((temp - 35f) * (100f / 15f))).coerceIn(0f, 100f)

    // Packet Loss: 0% = 100, > 5% = 0
    @Suppress("NOTHING_TO_INLINE")
    private inline fun normalizePacketLoss(loss: Float): Float =
        (100f - (loss * 20f)).coerceIn(0f, 100f)

    // Drops: 0 drops = 100, > 10 drops per interval = 0
    @Suppress("NOTHING_TO_INLINE")
    private inline fun normalizeDrops(drops: Int): Float =
        (100f - (drops * 10f)).coerceIn(0f, 100f)

    // Battery: > 20% = 100, < 5% = 0 (Only penalize when dying)
    @Suppress("NOTHING_TO_INLINE")
    private inline fun normalizeBattery(battery: Int): Float =
        if (battery > 20) 100f else (battery * (100f / 20f)).coerceIn(0f, 100f)
}
