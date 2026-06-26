package com.streamlink.wear.di

import android.content.Context
import com.streamlink.shared.DirectSocketClient
import com.streamlink.shared.NetworkDiscovery
import com.streamlink.wear.player.DirectStreamPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WearModule {

    @Provides
    @Singleton
    fun provideNetworkDiscovery(@ApplicationContext context: Context): NetworkDiscovery {
        return NetworkDiscovery(context)
    }

    @Provides
    @Singleton
    fun provideDirectSocketClient(): DirectSocketClient {
        // Default IP — will be overridden by NetworkDiscovery when resolved
        return DirectSocketClient("192.168.1.100")
    }

    @Provides
    @Singleton
    fun provideDirectStreamPlayer(
        client: DirectSocketClient
    ): DirectStreamPlayer {
        return DirectStreamPlayer(client)
    }
}
