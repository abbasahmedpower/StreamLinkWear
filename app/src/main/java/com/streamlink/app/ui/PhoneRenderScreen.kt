package com.streamlink.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamlink.shared.ai.TouchPerceptionHub
import com.streamlink.shared.ui.SharedUiConstants
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.isActive

/**
 * Dual-reality render pipeline — predicted cursor fused with real touch via second-order dynamics.
 */
@Composable
fun PhoneRenderSurface(
    modifier: Modifier = Modifier
) {
    val perception by TouchPerceptionHub.renderState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                if (lastNanos > 0L) {
                    val dt = (frameNanos - lastNanos) / 1_000_000_000f
                    TouchPerceptionHub.reconcileFrame(dt)
                }
                lastNanos = frameNanos
            }
        }
    }

    val remoteScale by animateFloatAsState(
        targetValue = if (perception.isPressed) 0.85f else 1f,
        animationSpec = SharedUiConstants.NASA_SPRING_SPEC,
        label = "RemoteSyncScale"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SharedUiConstants.BACKGROUND_DARK),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(250.dp)
                .scale(remoteScale)
                .background(
                    color = if (perception.isPressed) {
                        SharedUiConstants.ACCENT_GLOW.copy(alpha = 0.2f)
                    } else {
                        SharedUiConstants.ACCENT_GLOW.copy(alpha = 0.08f)
                    },
                    shape = RoundedCornerShape(24.dp)
                )
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cursorColor = if (perception.isMisprediction) {
                SharedUiConstants.MISPREDICTION_RED
            } else {
                SharedUiConstants.ACCENT_GLOW
            }
            drawCircle(
                color = cursorColor,
                radius = 28f,
                center = Offset(
                    perception.renderX * size.width,
                    perception.renderY * size.height
                )
            )
        }
    }
}
