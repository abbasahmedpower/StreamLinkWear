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
    fun provideDirectSocketClient(discovery: NetworkDiscovery): DirectSocketClient {
        return DirectSocketClient(discovery)
    }

    @Provides
    @Singleton
    fun provideDirectStreamPlayer(
        client: DirectSocketClient
    ): DirectStreamPlayer {
        return DirectStreamPlayer(client)
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
}
