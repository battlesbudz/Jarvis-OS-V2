package com.battlesbudz.jarvis.v2.voice

/**
 * Bridges one audio turn to the local Jarvis model without owning the model
 * runtime itself. The callback is expected to emit model output chunks in
 * order; each chunk is checkpointed immediately.
 */
class VoiceTurnCoordinator(
    private val session: VoiceSessionController
) {
    suspend fun <T> processTurn(
        transcript: String?,
        generate: suspend (onToken: (String) -> Unit) -> T
    ): T {
        if (session.state.value == VoiceSessionState.PASSIVE_LISTENING) {
            session.beginCall()
        }
        transcript?.takeIf { it.isNotBlank() }?.let {
            session.appendTranscript("You", it)
        }
        session.setState(VoiceSessionState.PROCESSING)
        val response = StringBuilder()
        return try {
            val result = generate { token ->
                response.append(token)
                session.setState(VoiceSessionState.SPEAKING)
                session.appendTranscript("Jarvis", response.toString(), complete = false)
            }
            if (response.isNotBlank()) {
                session.appendTranscript("Jarvis", response.toString(), complete = true)
            }
            result
        } catch (error: Throwable) {
            if (response.isNotBlank()) {
                session.appendTranscript("Jarvis", response.toString(), complete = false)
            }
            session.interrupt()
            throw error
        }
    }

    fun cancelActiveTurn() {
        if (session.state.value != VoiceSessionState.PASSIVE_LISTENING &&
            session.state.value != VoiceSessionState.ENDED
        ) {
            session.interrupt()
        }
    }
}
