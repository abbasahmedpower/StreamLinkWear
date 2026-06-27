package com.streamlink.wear.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_training_events")
data class AITrainingEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val motionIntensity: Float,
    val rttMs: Long,
    val packetLossPct: Float,
    val thermalLevel: Int,
    val recommendedBitrate: Int,
    val chosenBitrate: Int
)
