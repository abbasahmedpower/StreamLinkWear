package com.streamlink.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Design Tokens ─────────────────────────────────────────────────────────────
val CyberBackground = Color(0xFF0A0F1E)
val CyberSurface    = Color(0xFF111827)
val CyberPrimary    = Color(0xFF00FFCC)
val CyberAccent     = Color(0xFF7C3AFF)
val CyberOnPrimary  = Color(0xFF003322)
val CyberMuted      = Color(0xFF8899AA)
val CyberRed        = Color(0xFFFF3B5C)
val CyberYellow     = Color(0xFFFFE600)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
    ) {
        content()
    }
}

@Composable
fun GridTwoColumns(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
    }
}

/**
 * ✅ ZERO-RECOMPOSITION TelemetryItem.
 *
 * Key change: [value] and [progress] are now *lambdas* `() -> String` and `() -> Float`
 * instead of plain values.  When the State upstream changes, Compose only re-invokes
 * the lambda inside THIS composable's subtree — the parent composable is NOT recomposed.
 *
 * The animated progress bar still gets smooth transitions via [animateFloatAsState].
 */
@Composable
fun RowScope.TelemetryItem(title: String, valueProvider: () -> String, progressProvider: () -> Float, icon: String = "") {
    Column(
        modifier = Modifier.weight(1f)
    ) {
        Text(
            text = title,
            color = Color(0xFFBAC9CC),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = valueProvider(),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progressProvider() },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Color(0xFF00FFCC),
            trackColor = Color.White.copy(alpha = 0.1f)
        )
    }
}

/** Horizontal divider with a subtle glow tint. */
@Composable
fun GlowDivider(color: Color = CyberPrimary) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(Color.Transparent, color.copy(alpha = 0.4f), Color.Transparent)
                )
            )
    )
}

/** Small status badge pill. */
@Composable
fun StatusBadge(label: String, active: Boolean) {
    val bgColor = if (active) CyberPrimary.copy(alpha = 0.15f) else CyberRed.copy(alpha = 0.15f)
    val fgColor = if (active) CyberPrimary else CyberRed

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(bgColor)
            .border(1.dp, fgColor.copy(alpha = 0.4f), RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Blinking dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(fgColor)
        )
        Text(
            text = label,
            color = fgColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )
    }
}
