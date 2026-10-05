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
    @Test fun spokenReminderAllowsMatchingMessageAndTime() {
        // The voice turn that produced the build-1002 confabulation must pass
        // the final spoken-clause guard when the spoken message and time match
        // the planned arguments.
        val text = "remind me to go door dashing tomorrow at 4"
        val spec = com.battlesbudz.jarvis.v2.actions.ActionRequestText
            .reminderRequest(text, System.currentTimeMillis())
        assertNotNull(spec)
        assertTrue(FinalVoiceToolGuard.allows(text, "create_reminder",
            mapOf("message" to "go door dashing", "at_ms" to spec!!.atMs.toString())))
    }
    @Test fun spokenReminderRejectsMismatchedMessageOrTime() {
        val text = "remind me to go door dashing tomorrow at 4"
        val spec = com.battlesbudz.jarvis.v2.actions.ActionRequestText
            .reminderRequest(text, System.currentTimeMillis())
        assertNotNull(spec)
        assertFalse("wrong message must not pass",
            FinalVoiceToolGuard.allows(text, "create_reminder",
                mapOf("message" to "go grocery shopping", "at_ms" to spec!!.atMs.toString())))
        assertFalse("wrong time must not pass",
            FinalVoiceToolGuard.allows(text, "create_reminder",
                mapOf("message" to "go door dashing", "at_ms" to (spec.atMs + 3_600_000).toString())))
        assertFalse("missing time must not pass",
            FinalVoiceToolGuard.allows(text, "create_reminder", mapOf("message" to "go door dashing")))
    }
    @Test fun spokenScheduleViewIsAllowed() {
        assertTrue(FinalVoiceToolGuard.allows("show my schedule", "show_schedule", emptyMap()))
    }
}
