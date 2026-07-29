package com.streamlink.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.PairingManager
import com.streamlink.shared.util.SystemSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: StreamingOrchestrator
) : ViewModel() {

    private val settingsStore = SystemSettingsStore.get(context)

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<MainEffect>()
    val effect: SharedFlow<MainEffect> = _effect.asSharedFlow()

    init {
        observeGlobalStreamState()
        observePairingState()
        observeSettings()
    }

    private fun observeGlobalStreamState() {
        viewModelScope.launch {
            GlobalStreamState.snapshot.collect { streamState ->
                _state.update {
                    it.copy(
                        isStreaming = streamState.state == GlobalStreamState.State.STREAMING,
                        isConnecting = streamState.state == GlobalStreamState.State.CONNECTING || 
                                       streamState.state == GlobalStreamState.State.AUTHENTICATING,
                        latencyMs = streamState.latencyMs,
                        bitrateKbps = streamState.bitrateKbps,
                        fps = streamState.fps
                    )
                }
            }
        }
    }

    private fun observePairingState() {
        viewModelScope.launch {
            PairingManager.state.collect { pairingState ->
                when (pairingState) {
                    is PairingManager.PairingState.WaitingForPin -> {
                        _effect.emit(MainEffect.LaunchQrScanner)
                    }
                    is PairingManager.PairingState.PinRejected -> {
                        _effect.emit(MainEffect.ShowToast("❌ رمز الاقتران خاطئ — يرجى المحاولة مجدداً", true))
                        PairingManager.reset()
                    }
                    is PairingManager.PairingState.Paired -> {
                        _effect.emit(MainEffect.ShowToast("✅ تم الاقتران بنجاح"))
                        PairingManager.reset()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.isPrivacyBlackoutEnabled.collect { isEnabled ->
                _state.update { it.copy(isPrivacyBlackoutEnabled = isEnabled) }
            }
        }
    }

    fun processIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.StartCaptureRequested -> {
                viewModelScope.launch {
                    _effect.emit(MainEffect.LaunchScreenCapture)
                }
            }
            is MainIntent.StreamResultReceived -> {
                orchestrator.startStream(
                    context = context,
                    url = "",
                    resultCode = intent.resultCode,
                    projectionData = intent.projectionData,
                    isDrm = false,
                    networkQuality = 1.0f
                )
            }
            is MainIntent.StopStream -> {
                orchestrator.stopStream(context)
            }
            is MainIntent.SetAiOptimizer -> {
                _state.update { it.copy(aiOptimizerEnabled = intent.enabled) }
            }
            is MainIntent.RequestOverlayPermission -> {
                viewModelScope.launch {
                    _effect.emit(MainEffect.RequestOverlayPermission)
                }
            }
            is MainIntent.SetPrivacyBlackout -> {
                viewModelScope.launch {
                    settingsStore.setPrivacyBlackout(intent.enabled)
                }
            }
            is MainIntent.ResetPairing -> {
                PairingManager.reset()
            }
        }
    }
}
