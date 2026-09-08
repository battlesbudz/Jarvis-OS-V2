package com.battlesbudz.jarvis.v2.voice

/** Gives Android dictation priority over our wake/call recorder, before it opens a mic. */
object MicrophoneHandoff {
    private val requested = java.util.concurrent.atomic.AtomicBoolean(false)
    val dictationRequested: Boolean get() = requested.get()
    val backgroundRecorders = java.util.concurrent.atomic.AtomicInteger(0)
    private val events = java.util.ArrayDeque<String>()
    @Synchronized fun record(message: String) {
        events.addLast("atMs=${System.currentTimeMillis()} $message")
        while (events.size > 12) events.removeFirst()
    }
    @Synchronized fun clearDiagnostics() { events.clear() }
    @Synchronized fun diagnostics(): String =
        "Microphone handoff: interrupted=${interrupted.value} silenced=$ownRecorderSilenced dictation=$dictationRequested ownRecorders=${backgroundRecorders.get()}\n" + events.joinToString("\n")
    val interrupted = kotlinx.coroutines.flow.MutableStateFlow(false)
    @Volatile var ownRecorderSilenced = false
    val shouldYield: Boolean get() = dictationRequested || interrupted.value
    fun requestDictation(): Boolean = requested.compareAndSet(false, true)
    fun finishDictation() { requested.set(false) }
}
