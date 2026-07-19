package com.streamlink.shared.di

import android.content.Context
import com.streamlink.shared.EventPipeline
import com.streamlink.shared.MetricsCollector
import com.streamlink.shared.TrustedDeviceStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object SharedModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideApplicationScope(
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
    ): CoroutineScope {
        return CoroutineScope(SupervisorJob() + defaultDispatcher)
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

    // NANO-FIX: TrustedDeviceStore was fully implemented (Keystore-backed,
    // EncryptedSharedPreferences, Trust-on-First-Use) but never wired into any
    // Hilt graph or referenced from app/ or wear/ — same dead-code-via-missing-DI
    // pattern as the earlier StreamHapticFeedback bug. Now available for
    // constructor injection on both the phone and watch side.
    @Provides
    @Singleton
    fun provideTrustedDeviceStore(@ApplicationContext context: Context): TrustedDeviceStore {
        return TrustedDeviceStore.get(context)
    }
}
