package com.streamlink.wear.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AITrainingDao {
    @Insert
    suspend fun insertBatch(events: List<AITrainingEvent>)

    @Insert
    suspend fun insert(event: AITrainingEvent)

    @Query("SELECT * FROM ai_training_events ORDER BY timestamp ASC")
    suspend fun getAllEvents(): List<AITrainingEvent>

    @Query("DELETE FROM ai_training_events")
    suspend fun clearAll()
}
