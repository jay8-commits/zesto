package com.example.zesto.frame

/**
 * Consumer interface for downstream frame sinks.
 * Examples include: UI Preview, Diagnostics, Camera Virtualization Backend, Recording sinks.
 */
interface FrameConsumer {
    val consumerId: String
    val preferredFormat: PixelFormat? get() = null

    fun onConsumerAttached(provider: FrameProvider)
    fun onFrameAvailable(frame: VideoFrame)
    fun onConsumerDetached()
}

/**
 * Provider interface supplying decoded frames to registered consumers.
 * Decouples the decoder and transport from all downstream rendering and camera integration layers.
 */
interface FrameProvider {
    fun registerConsumer(consumer: FrameConsumer): Boolean
    fun unregisterConsumer(consumerId: String): Boolean
    fun unregisterAll()
    fun getActiveConsumerCount(): Int
    fun getRegisteredConsumerIds(): List<String>
    fun isRunning(): Boolean
}
