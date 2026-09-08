package com.battlesbudz.jarvis.v2.voice

/** Gives Android dictation priority over our wake/call recorder, before it opens a mic. */
object MicrophoneHandoff {
    private val requested = java.util.concurrent.atomic.AtomicBoolean(false)
    val dictationRequested: Boolean get() = requested.get()
    @Volatile var keyboardVisible = false
    val keyboardHelperConnected = kotlinx.coroutines.flow.MutableStateFlow(false)
    val shouldYield: Boolean get() = dictationRequested || keyboardVisible
    fun requestDictation(): Boolean = requested.compareAndSet(false, true)
    fun finishDictation() { requested.set(false) }
}
