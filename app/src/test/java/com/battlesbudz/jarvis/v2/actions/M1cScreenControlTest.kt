package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * M1c screen control slice: screen_observe, screen_tap, screen_scroll, screen_type.
 * Covers catalog/schema parity, strict decode, validator, tolerant decode,
 * text-parser routing, and the session state machine (grant, verified targets,
 * touch pause/idle resume, stop, release). FinalVoiceToolGuard stays untouched:
 * screen tools remain voice-denied by design.
 */
class M1cScreenControlTest {
    private val validator = MobileActionValidator()

    private fun strict(name: String, args: Map<String, String>) =
        MobileToolCatalog.decodeStrict(name, JSONObject(args as Map<*, *>))

    private fun observation(vararg nodes: ScreenNode) =
        ScreenObservation(packageName = "com.example.app", nodes = nodes.toList())

    private fun button(id: String = "n0", label: String = "Search") =
        ScreenNode(id, label, "button", "10,20-100,80", clickable = true)

    private fun field(id: String = "n1", label: String = "Name") =
        ScreenNode(id, label, "field", "10,100-400,160", editable = true)

    private fun list(id: String = "n2", label: String = "Results") =
        ScreenNode(id, label, "list", "0,200-1080,1800", scrollable = true)

    // Catalog

    @Test fun catalogDeclaresAllFourScreenTools() {
        val names = MobileToolCatalog.all().map { it.name }
        assertTrue(names.contains("screen_observe"))
        assertTrue(names.contains("screen_tap"))
        assertTrue(names.contains("screen_scroll"))
        assertTrue(names.contains("screen_type"))
    }

    @Test fun strictDecodeEnforcesTargetTokenAndDirectionShapes() {
        val token = "abcdef1234567890"
        assertNotNull(strict("screen_observe", emptyMap()))
        assertNotNull(strict("screen_tap", mapOf("target" to "n3", "token" to token)))
        assertNotNull(strict("screen_scroll", mapOf("target" to "n3", "direction" to "down", "token" to token)))
        assertNotNull(strict("screen_type", mapOf("target" to "n3", "text" to "hi", "token" to token)))
        // Bad shapes never decode.
        assertNull(strict("screen_tap", mapOf("target" to "n3", "token" to "stale")))
        assertNull(strict("screen_tap", mapOf("target" to "n3", "token" to token, "extra" to "1")))
        assertNull(strict("screen_tap", mapOf("target" to "button3", "token" to token)))
        assertNull(strict("screen_tap", mapOf("target" to "n3", "token" to token.uppercase())))
        assertNull(strict("screen_scroll", mapOf("target" to "n3", "direction" to "left", "token" to token)))
        assertNull(strict("screen_scroll", mapOf("target" to "n3", "direction" to "DOWN", "token" to token)))
        assertNull(strict("screen_type", mapOf("target" to "n3", "text" to "", "token" to token)))
        assertNull(strict("screen_observe", mapOf("unused" to "1")))
    }

    // Validator

    @Test fun validatesScreenActions() {
        val token = "abcdef1234567890"
        assertEquals(
            ActionValidation.Valid(MobileAction.ScreenObserve),
            validator.validate(ActionRequest("screen_observe"))
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.ScreenTap("n3", token)),
            validator.validate(ActionRequest("screen_tap", mapOf("target" to "n3", "token" to token)))
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.ScreenScroll("n3", ScreenScrollDirection.DOWN, token)),
            validator.validate(
                ActionRequest("screen_scroll", mapOf("target" to "n3", "direction" to "down", "token" to token))
            )
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.ScreenScroll("n3", ScreenScrollDirection.UP, token)),
            validator.validate(
                ActionRequest("screen_scroll", mapOf("target" to "n3", "direction" to "up", "token" to token))
            )
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.ScreenType("n3", "hello", token)),
            validator.validate(
                ActionRequest("screen_type", mapOf("target" to "n3", "text" to "hello", "token" to token))
            )
        )
    }

    @Test fun rejectsMalformedScreenTargets() {
        val token = "abcdef1234567890"
        for (request in listOf(
            ActionRequest("screen_tap", mapOf("target" to "n3", "token" to "short")),
            ActionRequest("screen_tap", mapOf("target" to "3", "token" to token)),
            ActionRequest("screen_scroll", mapOf("target" to "n3", "direction" to "sideways", "token" to token)),
            ActionRequest("screen_type", mapOf("target" to "n3", "text" to "  ", "token" to token)),
            ActionRequest("screen_type", mapOf("target" to "n3", "text" to "x".repeat(201), "token" to token)),
            ActionRequest("screen_tap", mapOf("target" to "n3"))
        )) {
            // Note: extra keys are rejected by the strict decoder (see
            // strictDecodeEnforcesTargetTokenAndDirectionShapes), not the
            // validator — the validator checks value shapes, matching the
            // existing tools' convention.
            val result = validator.validate(request)
            assertTrue("${request.name} ${request.arguments} must be rejected", result is ActionValidation.Rejected)
        }
        // Exactly 200 characters is the bound, not a rejection.
        assertTrue(
            validator.validate(
                ActionRequest("screen_type", mapOf("target" to "n3", "text" to "x".repeat(200), "token" to token))
            ) is ActionValidation.Valid
        )
    }

    // Tolerant decoder

    @Test fun tolerantDecodeMapsScreenArgs() {
        assertEquals(
            ActionRequest("screen_observe"),
            NativeActionDecoder.decode(ToolCall("screen_observe", "{}"))
        )
        assertEquals(
            ActionRequest("screen_tap", mapOf("target" to "n3", "token" to "abcdef1234567890")),
            NativeActionDecoder.decode(ToolCall("screen_tap", """{"target":"n3","token":"abcdef1234567890"}"""))
        )
        assertEquals(
            ActionRequest(
                "screen_scroll",
                mapOf("target" to "n3", "direction" to "up", "token" to "abcdef1234567890")
            ),
            NativeActionDecoder.decode(
                ToolCall("screen_scroll", """{"args":{"target":"n3","direction":"up","token":"abcdef1234567890"}}""")
            )
        )
        assertEquals(
            ActionRequest(
                "screen_type",
                mapOf("target" to "n3", "text" to "hello", "token" to "abcdef1234567890")
            ),
            NativeActionDecoder.decode(
                ToolCall("screen_type", """{"target":"n3","text":"hello","token":"abcdef1234567890"}""")
            )
        )
    }

    // Text parser routing

    private fun ready(text: String): ActionTurnPlan.Ready {
        val plan = ActionTurnPlan.parse(text)
        assertTrue("'$text' must parse as an action plan, was $plan", plan is ActionTurnPlan.Ready)
        return plan as ActionTurnPlan.Ready
    }

    @Test fun screenObservationFormsRouteToScreenObserve() {
        for (text in listOf(
            "what's on my screen",
            "what is on the screen",
            "look at my screen",
            "read the screen",
            "describe my screen",
            "show me the screen"
        )) {
            assertEquals(
                ActionRequest("screen_observe"),
                ready(text).steps.single().request
            )
        }
    }

    @Test fun screenMutationsAreNotTextRouted() {
        // Targets must come from a fresh screen_observe result, which text cannot supply.
        for (text in listOf("tap the search button", "scroll down", "type hello into the name field")) {
            val plan = ActionTurnPlan.parse(text)
            assertTrue("'$text' must not become an action plan, was $plan", plan !is ActionTurnPlan.Ready)
        }
    }

    // Compact snapshot

    @Test fun compactSnapshotIsBoundedAndNamesToken() {
        val nodes = (0 until 80).map { button(id = "n$it", label = "Button number $it with a very long label that keeps going past sixty characters") }
        val text = observation(*nodes.toTypedArray()).compactText("abcdef1234567890")
        assertTrue(text.contains("Screen: com.example.app (80 elements)"))
        assertTrue(text.contains("[n0] button \"Button number 0 with a very long label that keeps going past\" (10,20-100,80)"))
        assertTrue(text.contains("... 16 more elements omitted"))
        assertTrue(text.contains("observation token: abcdef1234567890"))
        assertFalse(text.contains("[n64]"))
    }

    // Session: grants

    @Test fun admissionRequiresApprovalAndIsOneGroupAtATime() {
        val session = ScreenControlSession()
        assertEquals(AdmitResult.NeedsApproval, session.admit("group-1", userApproved = false))
        assertFalse(session.isAdmitted)
        assertEquals(AdmitResult.Admitted, session.admit("group-1", userApproved = true))
        assertTrue(session.isAdmitted)
        assertEquals(AdmitResult.AlreadyAdmitted, session.admit("group-1", userApproved = true))
        val denied = session.admit("group-2", userApproved = true)
        assertTrue("a second group must not inherit the lease: $denied", denied is AdmitResult.Denied)
        assertTrue(session.isAdmitted)
        session.release()
        assertFalse(session.isAdmitted)
        // After release a later group admits fresh; nothing is silently inherited.
        assertEquals(AdmitResult.Admitted, session.admit("group-2", userApproved = true))
        assertEquals(DispatchGate.Allowed, session.dispatchGate())
    }

    @Test fun unadmittedMutationsHitTheAdmissionGate() {
        val session = ScreenControlSession()
        assertEquals(DispatchGate.NeedsAdmission, session.dispatchGate())
    }

    // Session: verified targets

    @Test fun observationRotatesTokenAndVerifiesTargets() {
        val session = ScreenControlSession()
        session.admit("group-1", userApproved = true)
        val token1 = session.recordObservation(observation(button(), field(), list()))
        val token2 = session.recordObservation(observation(button()))
        assertNotEquals(token1, token2)
        assertTrue(token1.matches(Regex("^[0-9a-f]{16}$")))
        // Old token is stale even for a target that still exists.
        val stale = session.verifyTarget("n0", token1) { null }
        assertTrue("stale token must be rejected: $stale", stale is TargetVerification.Rejected)
        // Current token verifies; kind requirements are enforced.
        val verified = session.verifyTarget("n0", token2) { node ->
            if (!node.clickable) "not tappable" else null
        }
        assertTrue(verified is TargetVerification.Verified)
        assertEquals("Search", (verified as TargetVerification.Verified).node.label)
        val wrongKind = session.verifyTarget("n0", token2) { node ->
            if (!node.editable) "not editable" else null
        }
        assertTrue(wrongKind is TargetVerification.Rejected)
        val missing = session.verifyTarget("n9", token2) { null }
        assertTrue(missing is TargetVerification.Rejected)
    }

    // Target binding (finding 3): the live node must match role, label AND
    // bounds. The walk index is positional, so a shifted tree resolving the
    // same index to a same-labeled node at a different position must not bind.
    @Test fun liveNodeBindingRequiresRoleLabelAndBounds() {
        val node = button() // n0, "Search", button, "10,20-100,80"
        assertTrue(node.matchesLiveNode("button", "Search", "10,20-100,80"))
        assertFalse("same label at a different position must not bind",
            node.matchesLiveNode("button", "Search", "10,300-100,360"))
        assertFalse("same position with a different label must not bind",
            node.matchesLiveNode("button", "Send", "10,20-100,80"))
        assertFalse("same label with a different role must not bind",
            node.matchesLiveNode("text", "Search", "10,20-100,80"))
    }

    // Session: touch pause and idle resume (T06)

    @Test fun manualTouchPausesDispatchAndIdleResumeRequiresReobserve() {
        var now = 1_000L
        val session = ScreenControlSession(clock = { now }, touchIdleMs = 3_000L)
        session.admit("group-1", userApproved = true)
        val token = session.recordObservation(observation(button()))
        assertEquals(DispatchGate.Allowed, session.dispatchGate())

        session.noteTouchStart()
        assertEquals(DispatchGate.Paused, session.dispatchGate())
        session.noteTouchEnd()
        // Still within the idle interval: paused, no countdown shown to the user.
        now += 1_000L
        assertEquals(DispatchGate.Paused, session.dispatchGate())
        // Idle interval elapsed: resume by re-observing, without a countdown.
        now += 3_000L
        assertEquals(DispatchGate.ResumeReobserve, session.dispatchGate())
        // The gate fires once; the caller re-observes, which rotates the token
        // and makes the pre-touch token stale by design.
        assertEquals(DispatchGate.Allowed, session.dispatchGate())
        val fresh = session.recordObservation(observation(button()))
        assertNotEquals(token, fresh)
        val stale = session.verifyTarget("n0", token) { null }
        assertTrue("pre-touch token must be stale after resume re-observe: $stale", stale is TargetVerification.Rejected)
        assertTrue(session.verifyTarget("n0", fresh) { null } is TargetVerification.Verified)
    }

    // Session: stop and release

    @Test fun stopRequestBlocksDispatchAndReleaseClearsEverything() {
        val session = ScreenControlSession()
        session.admit("group-1", userApproved = true)
        val token = session.recordObservation(observation(button()))
        session.requestStop()
        assertTrue(session.isStopRequested)
        assertEquals(DispatchGate.Stopped, session.dispatchGate())
        session.release()
        assertFalse(session.isStopRequested)
        assertEquals(DispatchGate.NeedsAdmission, session.dispatchGate())
        assertTrue(
            session.verifyTarget("n0", token) { null } is TargetVerification.Rejected
        )
    }

    // Voice stays denied by design (FinalVoiceToolGuard untouched).

    @Test fun screenToolsStayVoiceDenied() {
        for (name in listOf("screen_observe", "screen_tap", "screen_scroll", "screen_type")) {
            assertFalse(
                "$name must stay voice-denied",
                FinalVoiceToolGuard.allows("tap the button", name, mapOf("target" to "n0", "token" to "abcdef1234567890"))
            )
        }
        // Existing voice-allowed tools are unaffected.
        assertTrue(FinalVoiceToolGuard.allows("what is my battery", "read_battery", emptyMap()))
    }
}
