package com.streamlink.wear.di

import android.content.Context
import com.streamlink.shared.DirectSocketClient
import com.streamlink.shared.NetworkDiscovery
import com.streamlink.wear.player.DirectStreamPlayer
import androidx.room.Room
import com.streamlink.wear.db.AppDatabase
import com.streamlink.wear.db.AITrainingDao
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
    fun provideDirectSocketClient(@ApplicationContext context: android.content.Context, discovery: NetworkDiscovery): DirectSocketClient {
        return DirectSocketClient(context, discovery)
    }

    @Provides
    @Singleton
    fun provideDirectStreamPlayer(
        client: DirectSocketClient,
        audioEngine: com.streamlink.wear.player.AudioPlaybackEngine
    ): DirectStreamPlayer {
        return DirectStreamPlayer(client, audioEngine)
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "streamlink_wear_db"
        ).build()
    }

    @Provides
    fun provideAITrainingDao(db: AppDatabase): AITrainingDao {
        return db.aiTrainingDao()
    }

    @Provides
    @Singleton
    fun provideSmartWatchUXEngine(): com.streamlink.wear.ai.SmartWatchUXEngine {
        return com.streamlink.wear.ai.SmartWatchUXEngine { bitrate, fps ->
            // In a real app we would send this over the socket back to the phone.
            // For now, log it.
            android.util.Log.i("SmartWatchUXEngine", "Dynamic params: ${bitrate}bps, ${fps}fps")
        }
    }
}
