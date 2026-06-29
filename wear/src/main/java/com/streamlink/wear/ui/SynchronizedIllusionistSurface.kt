package com.streamlink.wear.ui

import com.streamlink.shared.ui.SharedUiConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SynchronizedIllusionistSurface(
    isPressedState: Boolean,
    touchPadSize: Dp = 140.dp,
    enableDrag: Boolean = true,
    onCoordinatesCaptured: (nx: Float, ny: Float, phase: Byte) -> Unit
) {
    val scaleMultiplier by animateFloatAsState(
        targetValue = if (isPressedState) 0.88f else 1f,
        animationSpec = SharedUiConstants.NASA_SPRING_SPEC,
        label = "PerceptionHackingScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SharedUiConstants.BACKGROUND_DARK),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(touchPadSize)
                .scale(scaleMultiplier)
                .shadow(
                    elevation = if (isPressedState) 20.dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = SharedUiConstants.ACCENT_GLOW,
                    spotColor = SharedUiConstants.ACCENT_GLOW
                )
                .background(
                    color = if (isPressedState) {
                        SharedUiConstants.ACCENT_GLOW.copy(alpha = 0.35f)
                    } else {
                        SharedUiConstants.ACCENT_GLOW
                    },
                    shape = CircleShape
                )
                .pointerInput(enableDrag) {
                    if (enableDrag) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                onCoordinatesCaptured(
                                    offset.x / size.width,
                                    offset.y / size.height,
                                    0
                                )
                            },
                            onDrag = { change, _ ->
                                onCoordinatesCaptured(
                                    change.position.x / size.width,
                                    change.position.y / size.height,
                                    1
                                )
                            },
                            onDragEnd = {
                                onCoordinatesCaptured(0.5f, 0.5f, 2)
                            },
                            onDragCancel = {
                                onCoordinatesCaptured(0.5f, 0.5f, 3)
                            }
                        )
                    } else {
                        detectTapGestures(
                            onPress = { offset ->
                                onCoordinatesCaptured(
                                    offset.x / size.width,
                                    offset.y / size.height,
                                    0
                                )
                                tryAwaitRelease()
                                onCoordinatesCaptured(
                                    offset.x / size.width,
                                    offset.y / size.height,
                                    2
                                )
                            }
                        )
                    }
                }
        )
    }
}
