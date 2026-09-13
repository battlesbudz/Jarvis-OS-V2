package com.battlesbudz.jarvis.v2.voice

import ai.moonshine.voice.Transcriber

/** A stream's update policy on reusable SDK weights; this does not own the native model. */
internal class MoonshineUpdateCadence(private val sdk: Transcriber, private val normalInterval: Double) {
    private var receivedBytes = 0L
    private var probeByteLimit: Long? = null
    init { restore() }

    fun prepareProbe(maxAudioMs: Long) {
        check(receivedBytes == 0L)
        require(maxAudioMs in 1..4000)
        probeByteLimit = maxAudioMs * 32
        // addAudioToStream queues PCM before the Java cadence check. stopStream
        // forces the final transcription even if no periodic update was due.
        sdk.setUpdateInterval((maxAudioMs + 1000) / 1000.0)
    }

    fun beforeAccept(bytes: Int) {
        require(bytes >= 0)
        probeByteLimit?.let { check(receivedBytes + bytes <= it) { "Probe audio exceeded its final-only bound" } }
        receivedBytes += bytes
    }

    fun restore() {
        sdk.setUpdateInterval(normalInterval)
        receivedBytes = 0
        probeByteLimit = null
    }
}
