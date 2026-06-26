package com.streamlink.shared.di

import com.streamlink.shared.EventPipeline
import com.streamlink.shared.MetricsCollector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SharedModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideEventPipeline(scope: CoroutineScope): EventPipeline {
        return EventPipeline(scope)
    }

    @Provides
    @Singleton
    fun provideMetricsCollector(scope: CoroutineScope): MetricsCollector {
        return MetricsCollector(scope)
    }
}
