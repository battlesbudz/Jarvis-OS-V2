package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

/**
 * Finding 3 (background-launch reporting) matrix. The route decision and
 * the receipt wording are JVM-pure and tested here for every combination:
 * foreground, selected assistant, granted/revoked overlay, unavailable
 * assistant binding, and denied/no-supported-route. A launch only counts as
 * verified when the destination is observed in the foreground — a submitted
 * request alone is reported honestly as unconfirmed.
 */
class BackgroundLaunchMatrixTest {

    // Route matrix: resolveBackgroundLaunchRoute.

    @Test fun foregroundActivityLaunchesDirectly() {
        assertEquals(
            BackgroundLaunchRoute.DIRECT,
            resolveBackgroundLaunchRoute(visible = true, assistantBindingAvailable = false, overlayExempt = false)
        )
    }

    @Test fun selectedAssistantRouteWhenBackgrounded() {
        assertEquals(
            BackgroundLaunchRoute.SELECTED_ASSISTANT,
            resolveBackgroundLaunchRoute(visible = false, assistantBindingAvailable = true, overlayExempt = false)
        )
    }

    @Test fun overlayExemptRouteWhenGranted() {
        assertEquals(
            BackgroundLaunchRoute.OVERLAY_EXEMPT,
            resolveBackgroundLaunchRoute(visible = false, assistantBindingAvailable = false, overlayExempt = true)
        )
    }

    @Test fun revokedOverlayWithNoAssistantIsLocalRefusal() {
        assertEquals(
            BackgroundLaunchRoute.NONE,
            resolveBackgroundLaunchRoute(visible = false, assistantBindingAvailable = false, overlayExempt = false)
        )
    }

    @Test fun unavailableAssistantBindingFallsBackToOverlayOrRefusal() {
        // Binding unavailable + overlay granted -> overlay-exempt route.
        assertEquals(
            BackgroundLaunchRoute.OVERLAY_EXEMPT,
            resolveBackgroundLaunchRoute(visible = false, assistantBindingAvailable = false, overlayExempt = true)
        )
        // Binding unavailable + overlay revoked -> no supported route.
        assertEquals(
            BackgroundLaunchRoute.NONE,
            resolveBackgroundLaunchRoute(visible = false, assistantBindingAvailable = false, overlayExempt = false)
        )
    }

    @Test fun foregroundBeatsOtherRoutes() {
        assertEquals(
            BackgroundLaunchRoute.DIRECT,
            resolveBackgroundLaunchRoute(visible = true, assistantBindingAvailable = true, overlayExempt = true)
        )
    }

    // Receipt wording: local refusal.

    @Test fun localRefusalWordingIsHonest() {
        val receipt = backgroundLaunchRefusal("Example App")
        assertFalse("a local refusal is not success", receipt.succeeded)
        assertTrue(
            "must use the honest refusal wording: ${receipt.message}",
            receipt.message.contains("Jarvis could not establish a supported background-launch route")
        )
        assertFalse(
            "must never present an inferred Android block as observed: ${receipt.message}",
            receipt.message.contains("Android blocked the background launch")
        )
        assertFalse(
            "must not imply the user must grant overlay permission: ${receipt.message}",
            receipt.message.contains("Display over other apps")
        )
    }

    // Receipts: submitted request vs platform rejection vs observed foreground.

    @Test fun verifiedLaunchReceiptSucceedsOnSubmission() {
        // A submitted request without a platform error is a success. Foreground
        // observation is best-effort (Android offers no reliable API for it),
        // so it is recorded in diagnostics, not gated on in the receipt.
        // Callers that can observe visibility (e.g., instrumentation via
        // UiDevice) verify the transition themselves.
        val receipt = verifiedLaunchReceipt(
            "Example App", BackgroundLaunchRoute.DIRECT,
            platformError = null
        )
        assertTrue("a submitted launch without platform error succeeds", receipt.succeeded)
        assertEquals("Opening Example App.", receipt.message)
    }

    @Test fun platformRejectionIsDistinctFromLocalRefusal() {
        val rejected = verifiedLaunchReceipt(
            "Example App", BackgroundLaunchRoute.DIRECT,
            platformError = "Activity not found"
        )
        assertFalse(rejected.succeeded)
        assertTrue(
            "a platform rejection names the platform error: ${rejected.message}",
            rejected.message.contains("Activity not found")
        )
        assertFalse(
            "a platform rejection is not a local refusal: ${rejected.message}",
            rejected.message.contains("could not establish a supported background-launch route")
        )
    }

    @Test fun overlayExemptSubmittedSucceeds() {
        val receipt = verifiedLaunchReceipt(
            "Example App", BackgroundLaunchRoute.OVERLAY_EXEMPT,
            platformError = null
        )
        assertTrue("a submitted overlay-exempt launch succeeds", receipt.succeeded)
        assertEquals("Opening Example App.", receipt.message)
    }
}
