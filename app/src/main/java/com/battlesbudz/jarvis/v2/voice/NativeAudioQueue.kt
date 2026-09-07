package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking

/** Bridges a synchronous JNI callback to suspending playback with bounded backpressure. */
internal class NativeAudioQueue<T>(capacity: Int = 2) {
    private val channel = Channel<T>(capacity)
    val chunks: ReceiveChannel<T> get() = channel

    fun sendFromNative(value: T) = runBlocking { channel.send(value) }
    fun close(cause: Throwable? = null) { channel.close(cause) }
    // Cancel BEFORE joining the native producer, so a callback blocked on send can return.
    fun cancel() { channel.cancel() }
}
