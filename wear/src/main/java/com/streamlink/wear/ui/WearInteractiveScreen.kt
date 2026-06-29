package com.streamlink.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.streamlink.shared.TouchPhase
import com.streamlink.shared.ui.SynchronizedIllusionistSurface

/**
 * Wear-side illusionist input pad — instant local feedback before network round-trip.
 */
@Composable
fun WearInteractiveScreen(
    onTouchEvent: (phase: TouchPhase, nx: Float, ny: Float) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    SynchronizedIllusionistSurface(
        isPressedState = isPressed,
        touchPadSize = 100.dp,
        enableDrag = true,
        onCoordinatesCaptured = { nx, ny, phaseByte ->
            isPressed = phaseByte.toInt() == 0 || phaseByte.toInt() == 1
            val phase = when (phaseByte.toInt()) {
                0 -> TouchPhase.DOWN
                1 -> TouchPhase.MOVE
                2 -> TouchPhase.UP
                else -> TouchPhase.CANCEL
            }
            onTouchEvent(phase, nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
        }
    )
}
