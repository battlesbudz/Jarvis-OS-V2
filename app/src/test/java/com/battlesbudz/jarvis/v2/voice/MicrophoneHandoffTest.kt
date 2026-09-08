package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class MicrophoneHandoffTest {
    @After fun release() { MicrophoneHandoff.finishDictation(); MicrophoneHandoff.keyboardVisible = false }
    @Test fun dictationHasExclusivePriorityUntilCleanupFinishes() {
        assertTrue(MicrophoneHandoff.requestDictation())
        assertTrue(MicrophoneHandoff.dictationRequested)
        assertFalse(MicrophoneHandoff.requestDictation())
        MicrophoneHandoff.finishDictation()
        assertFalse(MicrophoneHandoff.dictationRequested)
        assertTrue(MicrophoneHandoff.requestDictation())
    }
    @Test fun cancellationBeforeRecognitionStartsReleasesPriority() = runBlocking {
        assertTrue(MicrophoneHandoff.requestDictation())
        val owner = Job().apply { cancel() }
        val recognition = CoroutineScope(owner).launch { error("Must never run") }
        recognition.invokeOnCompletion { MicrophoneHandoff.finishDictation() }
        recognition.join()
        assertFalse(MicrophoneHandoff.dictationRequested)
    }
    @Test fun keyboardReservesMicrophoneBeforeItRequestsRecording() {
        MicrophoneHandoff.keyboardVisible = true
        assertTrue(MicrophoneHandoff.shouldYield)
        assertTrue(MicrophoneHandoff.requestDictation())
        MicrophoneHandoff.finishDictation()
        assertTrue(MicrophoneHandoff.shouldYield)
        MicrophoneHandoff.keyboardVisible = false
        assertFalse(MicrophoneHandoff.shouldYield)
    }

}
