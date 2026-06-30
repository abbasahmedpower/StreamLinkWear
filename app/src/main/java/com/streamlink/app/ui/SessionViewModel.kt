package com.streamlink.app.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.LatencyTracker
import com.streamlink.shared.QualityController
import com.streamlink.shared.StreamMetrics
import com.streamlink.shared.StreamProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SessionViewModel — complete state management for the UI layer.
 *
 * Owns: stream state, metrics display, user actions.
 * Delegates: all business logic to StreamingOrchestrator (no direct engine access from UI).
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val orchestrator: StreamingOrchestrator,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ── Public UI state ───────────────────────────────────────────────────
    data class UiState(
        val streamState: GlobalStreamState.State = GlobalStreamState.State.IDLE,
        val bitrateKbps: Int = 0,
        val fps: Int = StreamProtocol.WEAR_FPS_FULL,
        val latencyMs: Long = 0L,
        val thermalLevel: Int = 0,
        val mode: String = StreamProtocol.MODE_MIRROR,
        val errorMessage: String = "",
        val isConnecting: Boolean = false,
        val isStreaming: Boolean = false,
        val recoveryAttempt: Int = 0,
        val e2eLatencyMs: Long = 0L,
        val networkQuality: NetworkQuality = NetworkQuality.GOOD
    )

    enum class NetworkQuality { EXCELLENT, GOOD, DEGRADED, POOR }

    sealed class UserAction {
        data class StartStream(
            val url: String,
            val resultCode: Int,
            val projectionData: Intent?,
            val isDrm: Boolean,
            val networkQuality: Float = 1.0f
        ) : UserAction()
        object StopStream   : UserAction()
        object ForceKeyframe : UserAction()
        object ToggleMode   : UserAction()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbar: SharedFlow<String> = _snackbar

    private val latencyTracker = LatencyTracker()

    init {
        // Observe GlobalStreamState → UI
        viewModelScope.launch {
            GlobalStreamState.snapshot.collect { snap ->
                _uiState.update {
                    it.copy(
                        streamState   = snap.state,
                        bitrateKbps   = snap.bitrateKbps,
                        fps           = snap.fps,
                        latencyMs     = snap.latencyMs,
                        thermalLevel  = snap.thermalLevel,
                        mode          = snap.mode,
                        errorMessage  = snap.errorMessage,
                        isConnecting  = snap.state == GlobalStreamState.State.CONNECTING ||
                                        snap.state == GlobalStreamState.State.STREAM_STARTING,
                        isStreaming   = snap.state == GlobalStreamState.State.STREAMING
                    )
                }
                // Show snackbar on errors
                if (snap.errorMessage.isNotEmpty()) {
                    _snackbar.emit(snap.errorMessage)
                }
            }
        }
    }

    fun onAction(action: UserAction) {
        when (action) {
            is UserAction.StartStream -> viewModelScope.launch {
                _uiState.update { it.copy(isConnecting = true, errorMessage = "") }
                try {
                    orchestrator.startStream(
                        context        = context,
                        url            = action.url,
                        resultCode     = action.resultCode,
                        projectionData = action.projectionData,
                        isDrm          = action.isDrm,
                        networkQuality = action.networkQuality
                    )
                } catch (e: Exception) {
                    _uiState.update { it.copy(isConnecting = false, errorMessage = e.message ?: "Unknown error") }
                    _snackbar.emit("Failed to start stream: ${e.message}")
                }
            }
            is UserAction.StopStream -> viewModelScope.launch {
                orchestrator.stopStream(context)
                GlobalStreamState.transition(GlobalStreamState.State.STOPPED)
            }
            is UserAction.ForceKeyframe -> viewModelScope.launch {
                orchestrator.requestKeyframe()
                _snackbar.emit("Keyframe requested")
            }
            is UserAction.ToggleMode -> viewModelScope.launch {
                val currentMode = _uiState.value.mode
                val newMode = if (currentMode == StreamProtocol.MODE_MIRROR)
                    StreamProtocol.MODE_DIRECT else StreamProtocol.MODE_MIRROR
                GlobalStreamState.update { copy(mode = newMode) }
                _snackbar.emit("Switched to $newMode mode")
            }
        }
    }

    fun updateNetworkQuality(rttMs: Long, lossRate: Float) {
        val quality = when {
            rttMs < 50  && lossRate < 0.01f -> NetworkQuality.EXCELLENT
            rttMs < 100 && lossRate < 0.03f -> NetworkQuality.GOOD
            rttMs < 200 && lossRate < 0.10f -> NetworkQuality.DEGRADED
            else                             -> NetworkQuality.POOR
        }
        _uiState.update { it.copy(networkQuality = quality) }
    }

    fun updateE2ELatency(latencyMs: Long) {
        _uiState.update { it.copy(e2eLatencyMs = latencyMs) }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
