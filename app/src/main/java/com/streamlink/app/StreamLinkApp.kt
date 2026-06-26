package com.streamlink.app

import android.app.Application
import com.streamlink.shared.GlobalStreamState
import com.streamlink.shared.SessionBrain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StreamLinkApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            GlobalStreamState.resetSafe()
            SessionBrain.reset()
        }
    }
}
