package com.streamlink.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PairingManager — Singleton event bus for the pairing flow.
 *
 * DirectSocketServer emits [PairingState.WaitingForPin] the moment a watch
 * connects and the ECDH handshake completes (before Auth Block verification).
 * The phone UI observes this and launches MobilePinInputActivity.
 * Once the user enters the PIN, MobilePinInputActivity sets
 * [DirectSocketServer.pairingCode] and emits [PairingState.Idle] to dismiss itself.
 *
 * State machine:
 *   Idle ──(watch connects)──► WaitingForPin ──(user enters PIN)──► Idle
 *                                                                  └──(wrong PIN)──► PinRejected ──► Idle
 */
object PairingManager {

    sealed class PairingState {
        object Idle : PairingState()
        /** Emitted when a watch knocked on the door — show the PIN entry UI. */
        object WaitingForPin : PairingState()
        /** Emitted after verifyAuthBlock returns false — show error UI. */
        object PinRejected : PairingState()
        /** Emitted after successful PIN verification. */
        object Paired : PairingState()
    }

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    fun notifyWatchKnocked() {
        _state.value = PairingState.WaitingForPin
    }

    fun notifyPaired() {
        _state.value = PairingState.Paired
    }

    fun notifyPinRejected() {
        _state.value = PairingState.PinRejected
    }

    fun reset() {
        _state.value = PairingState.Idle
    }
}
