package com.streamlink.app.di

import com.streamlink.app.capture.HardwareEncoder
import com.streamlink.app.core.StreamingOrchestrator
import com.streamlink.app.stream.BackpressureController
import com.streamlink.app.stream.MirrorDataPlane
import com.streamlink.shared.DirectSocketServer
import com.streamlink.shared.EventPipeline
import com.streamlink.shared.MetricsCollector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
    fun provideHardwareEncoder(): HardwareEncoder {
        return HardwareEncoder()
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
        socketServer: DirectSocketServer,
        metrics: MetricsCollector,
        backpressure: BackpressureController
    ): MirrorDataPlane {
        return MirrorDataPlane(encoder, socketServer, metrics, backpressure)
    }

    @Provides
    @Singleton
    fun provideStreamingOrchestrator(
        scope: CoroutineScope,
        events: EventPipeline,
        socketServer: DirectSocketServer,
        mirrorDataPlane: MirrorDataPlane,
        hardwareEncoder: HardwareEncoder
    ): StreamingOrchestrator {
        return StreamingOrchestrator(scope, events, socketServer, mirrorDataPlane, hardwareEncoder)
    }
}
