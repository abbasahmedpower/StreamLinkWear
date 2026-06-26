package com.streamlink.shared

enum class StreamAction {
    IDLE,
    PRELOAD,
    STABLE,
    INCREASE_QUALITY,
    REDUCE_QUALITY,
    DROP_FPS,
    RECONNECT,
    PAUSE,
    // Aliases used by SessionViewModel / GlobalStreamState
    ABR_DOWN,
    ABR_UP,
    FORCE_KEYFRAME,
    SWITCH_MODE
}
