package com.streamlink.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.app.core.WearTelemetrySender

class TelemetryViewModelFactory(
    private val orchestrator: StreamingOrchestrator,
    private val wearSender: WearTelemetrySender
) : ViewModelProvider.Factory {
    
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TelemetryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TelemetryViewModel(orchestrator, wearSender) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
