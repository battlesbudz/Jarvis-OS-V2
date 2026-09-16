package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the microphone session, replay boundary and resident model session for a call.
 * Microphone handoff is serialized independently of the turn's native-model work.
 * Model methods are called only by the runtime's single, operation-locked turn,
 * and closeModels must run after all native borrowers have joined.
 */
internal class VoiceCallResources(
    private val createAudio: () -> VoiceAudioSession,
    private val createModels: () -> VoiceModelSession
) {
    private val audioLock = Mutex()
    private var audio: VoiceAudioSession? = null
    private var models: VoiceModelSession? = null
    private var modelsKey: String? = null
    private val playbackEndedAt = AtomicLong(0)

    fun playbackEnded(atMs: Long) { playbackEndedAt.set(atMs) }
    fun consumeFollowupBoundary(): Long? = playbackEndedAt.getAndSet(0).takeIf { it > 0 }

    suspend fun borrowMicrophone(label: String, replayAfterMs: Long? = null): AudioInput = audioLock.withLock {
        val reused = audio?.usable == true
        if (!reused) {
            audio?.close()
            playbackEndedAt.set(0)
            audio = createAudio()
        }
        requireNotNull(audio).borrow(label, if (reused) replayAfterMs else null)
    }

    suspend fun closeMicrophone(reason: Throwable? = null) = audioLock.withLock {
        audio?.close(reason)
        audio = null
        playbackEndedAt.set(0)
    }

    fun modelsFor(key: String): VoiceModelSession {
        if (modelsKey != key) {
            models?.close()
            models = createModels()
            modelsKey = key
        }
        return requireNotNull(models)
    }

    fun closeModels() {
        val resident = models
        models = null
        modelsKey = null
        resident?.close()
    }
}
