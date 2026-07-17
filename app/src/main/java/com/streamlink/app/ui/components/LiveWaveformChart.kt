package com.streamlink.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val GridLine    = Color(0x1AFFFFFF)

/**
 * ✅ ZERO-RECOMPOSITION waveform chart.
 *
 * The secret: we receive [bitrateHistory] as a Compose [State<FloatArray>] and read it
 * INSIDE the Canvas lambda (which runs in the Draw Phase, not the Composition Phase).
 * Compose only re-draws the canvas pixels — it never re-composes the parent composable.
 * This means 60 FPS updates with exactly ZERO extra Recompositions.
 *
 * @param bitrateHistory  State wrapping a ring-buffer of raw bitrate values (Kbps).
 * @param maxBitrateKbps  The ceiling value used to normalise bar heights.
 * @param height          Desired chart height.
 */
@Composable
fun LiveWaveformChart(
    bitrateHistory: State<FloatArray>,
    maxBitrateKbps: Float = 2000f,
    height: Dp = 80.dp
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        // ── READ STATE HERE (Draw Phase) ─────────────────────────────────────
        // This line reads the StateFlow value inside the draw lambda.
        // Compose registers a draw-layer invalidation — NOT a recomposition.
        val history = bitrateHistory.value
        // ─────────────────────────────────────────────────────────────────────

        if (history.isEmpty()) return@Canvas

        drawGrid()
        drawWaveform(history, maxBitrateKbps)
        drawGlowDot(history, maxBitrateKbps)
    }
}

/** Draws faint horizontal grid lines. */
private fun DrawScope.drawGrid() {
    val lines = 4
    repeat(lines) { i ->
        val y = size.height * (i + 1) / (lines + 1)
        drawLine(
            color = GridLine,
            start = Offset(0f, y),
            end   = Offset(size.width, y),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        )
    }
}

/** Draws the filled waveform path with dynamic colour based on current load. */
private fun DrawScope.drawWaveform(history: FloatArray, maxKbps: Float) {
    val n = history.size
    val stepX = size.width / (n - 1).coerceAtLeast(1)

    val currentValue = history.last()
    val ratio = (currentValue / maxKbps).coerceIn(0f, 1f)

    val lineColor = when {
        ratio > 0.85f -> CyberRed
        ratio > 0.60f -> CyberYellow
        else          -> CyberPrimary
    }

    // Build path top-line
    val path = Path()
    history.forEachIndexed { index, value ->
        val x = index * stepX
        val y = size.height - (value / maxKbps).coerceIn(0f, 1f) * size.height
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    // Close path to bottom for gradient fill
    val fillPath = Path().apply {
        addPath(path)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }

    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(lineColor.copy(alpha = 0.35f), Color.Transparent),
            startY = 0f,
            endY   = size.height
        )
    )

    drawPath(
        path  = path,
        color = lineColor,
        style = Stroke(width = 2.5f, cap = StrokeCap.Round)
    )
}

/** Draws an animated glow dot at the latest data point. */
private fun DrawScope.drawGlowDot(history: FloatArray, maxKbps: Float) {
    val currentValue = history.last()
    val ratio = (currentValue / maxKbps).coerceIn(0f, 1f)

    val dotColor = when {
        ratio > 0.85f -> CyberRed
        ratio > 0.60f -> CyberYellow
        else          -> CyberPrimary
    }

    val x = size.width
    val y = size.height - ratio * size.height

    // Outer glow
    drawCircle(color = dotColor.copy(alpha = 0.25f), radius = 10f, center = Offset(x, y))
    // Inner dot
    drawCircle(color = dotColor, radius = 4f, center = Offset(x, y))
}
