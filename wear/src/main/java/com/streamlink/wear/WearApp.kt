package com.streamlink.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
