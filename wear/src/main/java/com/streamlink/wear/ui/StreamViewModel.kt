package com.streamlink.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.MetricsCollector
import com.streamlink.shared.SessionBrain
import com.streamlink.shared.StreamAction
import com.streamlink.shared.StreamProtocol
import com.streamlink.wear.ai.AIEventLogger
import com.streamlink.wear.ai.LocalPredictiveEngine
import com.streamlink.wear.player.DirectStreamPlayer
import com.streamlink.wear.sensor.WristMotionSensor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class WearUiState(
    val isStreaming: Boolean = false,
    val isConnecting: Boolean = false,
    val isRecovering: Boolean = false,
    val isDegraded: Boolean = false,
    val bitrateKbps: Int = 0,
    val latencyMs: Long = 0L,
    val fps: Int = StreamProtocol.WEAR_FPS_FULL,
    val predictedAction: StreamAction = StreamAction.IDLE,
    val errorMessage: String = "",
    val reconnectCount: Int = 0,
    val sessionNetworkProfile: String = "WIFI"
)

@HiltViewModel
class StreamViewModel @Inject constructor(
    private val wristSensor: WristMotionSensor,
    private val predictiveEngine: LocalPredictiveEngine,
    val player: DirectStreamPlayer,
    private val metrics: MetricsCollector,
    val aiLogger: AIEventLogger
) : ViewModel() {

    val uiState: StateFlow<WearUiState> = GlobalStreamState.snapshot
        .map { snap ->
            WearUiState(
                isStreaming  = snap.state == GlobalStreamState.State.STREAMING,
                isConnecting = snap.state in connectingStates,
                isRecovering = snap.state == GlobalStreamState.State.RECOVERING,
                isDegraded   = snap.state == GlobalStreamState.State.DEGRADED,
                bitrateKbps  = snap.bitrateKbps,
                latencyMs    = snap.latencyMs,
                fps          = snap.fps,
                predictedAction = snap.predictedAction,
                errorMessage = snap.errorMessage,
                reconnectCount  = SessionBrain.state.reconnectCount,
                sessionNetworkProfile = SessionBrain.state.networkProfile
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), WearUiState())

    init {
        wristSensor.start()
        metrics.start()
        predictiveEngine.startWithScope(
            scope = viewModelScope,
            motionProvider  = { wristSensor.currentMagnitude },
            networkProvider = { GlobalStreamState.snapshot.value.latencyMs.toFloat() }
        )
    }

    override fun onCleared() {
        wristSensor.stop()
        predictiveEngine.stop()
        metrics.stop()
        player.release()
        super.onCleared()
    }

    companion object {
        private val connectingStates = setOf(
            GlobalStreamState.State.PRELOADING,
            GlobalStreamState.State.CONNECTING,
            GlobalStreamState.State.STREAM_STARTING
        )
    }
}
