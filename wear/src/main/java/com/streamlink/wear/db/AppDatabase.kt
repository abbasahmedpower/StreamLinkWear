package com.streamlink.wear.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [AITrainingEvent::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun aiTrainingDao(): AITrainingDao
}
