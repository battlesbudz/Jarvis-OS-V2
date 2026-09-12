package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class FinalVoiceToolGuardTest {
    @Test fun lateCancellationRejectsPreparedAction() {
        assertFalse(FinalVoiceToolGuard.allows("open instagram actually cancel that", "open_app", mapOf("app" to "Instagram")))
        assertFalse(FinalVoiceToolGuard.allows("don't open instagram", "open_app", mapOf("app" to "Instagram")))
    }
    @Test fun finalAppCorrectionRejectsOldTarget() {
        val text = "open instagram actually open youtube"
        assertFalse(FinalVoiceToolGuard.allows(text, "open_app", mapOf("app" to "Instagram")))
        assertTrue(FinalVoiceToolGuard.allows(text, "open_app", mapOf("app" to "YouTube")))
    }
    @Test fun finalNumberMustMatchProposedVolume() {
        assertFalse(FinalVoiceToolGuard.allows("set volume to 50 actually 20", "set_volume", mapOf("level" to "50")))
        assertTrue(FinalVoiceToolGuard.allows("set volume to 50 actually 20", "set_volume", mapOf("level" to "20")))
    }
    @Test fun unspokenPackageCannotOverrideNamedApp() {
        assertFalse(FinalVoiceToolGuard.allows("open youtube", "open_app", mapOf("app" to "YouTube", "package" to "com.instagram.android")))
    }
}
