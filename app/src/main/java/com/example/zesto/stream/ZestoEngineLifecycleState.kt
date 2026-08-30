package com.example.zesto.stream

/**
 * High-level engine lifecycle states for the Zesto streaming engine.
 */
enum class ZestoEngineLifecycleState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RUNNING,
    RECONNECTING,
    DISCONNECTING,
    ERROR
}

/**
 * Virtual camera injection pipeline stages.
 */
enum class VirtualInjectionState {
    RTSP_CONNECTED,
    DECODER_RUNNING,
    FRAME_PIPELINE_RUNNING,
    INJECTION_ATTEMPTED,
    INJECTION_CONFIRMED
}
