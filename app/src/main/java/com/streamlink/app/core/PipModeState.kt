package com.streamlink.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks whether MainActivity is currently rendered inside a Picture-in-Picture window.
 *
 * Why a plain object instead of routing this through TelemetryViewModel: PiP state is a
 * pure Activity-lifecycle signal (owned by Android, not by our domain layer), and several
 * unrelated Composables (HorusTelemetryScreen, SettingsScreen, InfoScreen) need to read it
 * to hide chrome that makes no sense in a thumbnail-sized floating window. Same pattern as
 * GlobalStreamState — a single source of truth, no DI wiring required to observe it.
 */
object PipModeState {
    private val _isInPip = MutableStateFlow(false)
    val isInPip: StateFlow<Boolean> = _isInPip

    internal fun update(inPip: Boolean) {
        _isInPip.value = inPip
    }
}
