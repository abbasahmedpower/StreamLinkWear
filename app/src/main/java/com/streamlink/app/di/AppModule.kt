package com.streamlink.app.di

import android.content.Context
import com.streamlink.app.capture.HardwareEncoder
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.app.stream.BackpressureController
import com.streamlink.app.stream.MirrorDataPlane
import com.streamlink.shared.AdaptiveResolutionController
import com.streamlink.shared.ConnectionManager
import com.streamlink.shared.DirectSocketServer
import com.streamlink.shared.EventPipeline
import com.streamlink.shared.LatencyTracker
import com.streamlink.shared.MetricsCollector
import com.streamlink.shared.ThermalMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDirectSocketServer(): DirectSocketServer {
        return DirectSocketServer()
    }

    @Provides
    @Singleton
    fun provideStreamRouter(): com.streamlink.shared.StreamRouter {
        return com.streamlink.shared.StreamRouter()
    }

    @Provides
    @Singleton
    fun provideHardwareEncoder(@ApplicationContext context: Context): HardwareEncoder {
        val quality = com.streamlink.app.core.SettingsPrefs.get(context).quality.value
        // targetBitrateKbps is defined directly on QualityMode — no switch needed here.
        return HardwareEncoder(initialBitrateKbps = quality.targetBitrateKbps)
    }

    @Provides
    @Singleton
    fun provideBackpressureController(
        encoder: HardwareEncoder,
        scope: CoroutineScope
    ): BackpressureController {
        return BackpressureController(
            buffer = encoder.outputChannel,
            onBitrateChange = { kbps -> encoder.setBitrate(kbps) },
            scope = scope
        )
    }

    @Provides
    @Singleton
    fun provideMirrorDataPlane(
        encoder: HardwareEncoder,
        streamRouter: com.streamlink.shared.StreamRouter,
        metrics: MetricsCollector,
        backpressure: BackpressureController
    ): MirrorDataPlane {
        return MirrorDataPlane(encoder, streamRouter, metrics, backpressure)
    }


    @Provides
    @Singleton
    fun provideNetworkDiscovery(@ApplicationContext context: Context): com.streamlink.shared.NetworkDiscovery {
        return com.streamlink.shared.NetworkDiscovery(context)
    }


    @Provides
    @Singleton
    fun provideThermalMonitor(@ApplicationContext context: Context): ThermalMonitor {
        return ThermalMonitor(context).apply { start() }
    }

}
