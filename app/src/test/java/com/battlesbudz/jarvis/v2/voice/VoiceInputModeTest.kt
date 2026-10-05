package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputModeTest {
    @Test fun existingInstallKeepsRecognizedTextInput() {
        assertEquals(VoiceInputMode.TRANSCRIBED_TEXT, VoiceInputMode.fromId(null))
        assertEquals(VoiceInputMode.TRANSCRIBED_TEXT, VoiceInputMode.fromId("unknown_future_mode"))
    }
    @Test fun persistedAudioChoiceDoesNotChangeOtherInputModes() {
        assertEquals(VoiceInputMode.GEMMA_AUDIO, VoiceInputMode.fromId("gemma_audio"))
        assertEquals(VoiceInputMode.TRANSCRIBED_TEXT, VoiceInputMode.fromId("transcribed_text"))
    }
}
