package com.streamlink.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ControlMessage {
    @Serializable
    @SerialName("handshake")
    data class Handshake(val protocolVersion: Int) : ControlMessage

    @Serializable
    @SerialName("settings")
    data class SettingsUpdate(
        val dynamicFps: Boolean,
        val imuGestures: Boolean,
        val jitterBufferMs: Int? = null
    ) : ControlMessage

    @Serializable
    @SerialName("ack")
    data class Ack(
        val messageId: String,
        val status: String
    ) : ControlMessage
}
