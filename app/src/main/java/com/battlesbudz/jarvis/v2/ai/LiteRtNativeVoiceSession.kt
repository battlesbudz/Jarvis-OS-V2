package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.Session

/** SDK boundary. The streaming lifecycle can be tested without loading Android JNI classes. */
internal class LiteRtNativeVoiceSession(private val session: Session) : VoiceNativeSession {
    override fun runPrefill(input: List<String>) = session.runPrefill(input.map { InputData.Text(it) })

    override fun generateContentStream(input: List<String>, callback: VoiceNativeCallback) {
        require(input.isNotEmpty()) { "Streaming generation requires a final input chunk" }
        session.generateContentStream(input.map { InputData.Text(it) }, object : ResponseCallback {
            override fun onNext(response: String) = callback.onNext(response)
            override fun onDone() = callback.onDone()
            override fun onError(throwable: Throwable) = callback.onError(throwable)
        })
    }

    override fun cancelProcess() = session.cancelProcess()
    override fun close() = session.close()
}
