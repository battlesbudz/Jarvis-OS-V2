package com.battlesbudz.jarvis.v2.actions

/**
 * Finding 3 (background-launch reporting): what Jarvis actually observed
 * when launching another app, reported distinctly in receipts and
 * diagnostics.
 *
 * Four states, never conflated:
 * - local refusal: Jarvis never asked Android to launch anything because no
 *   supported route was established;
 * - submitted request: the launch was handed to the platform (the
 *   selected-assistant binding's own receipt);
 * - platform rejection: startActivity threw (ActivityNotFound,
 *   SecurityException);
 * - observed foreground transition: the destination package was seen in the
 *   foreground after the request — the only state that counts as a verified
 *   launch. startActivity returning is not enough: a background start can be
 *   silently dropped by Android's background-activity-start restrictions.
 *
 * The two recognized routes (selected assistant, overlay exemption) are not
 * an exhaustive statement of Android's background-start exceptions, so the
 * refusal wording must never imply the user must grant overlay permission
 * when Android might permit another route, and must never present an
 * inferred diagnosis ("Android blocked the background launch") as an
 * observed platform result.
 */
enum class BackgroundLaunchRoute {
    /** Jarvis's own activity is visible: a direct startActivity. */
    DIRECT,
    /** The selected-assistant VoiceInteractionService binding launches. */
    SELECTED_ASSISTANT,
    /** A background-activity-start exemption applies (overlay grant). */
    OVERLAY_EXEMPT,
    /** No supported route: Jarvis refuses locally without asking Android. */
    NONE
}

/**
 * Pure route decision behind AndroidMobileActionExecutor's launch path.
 * JVM-testable; the executor wires the real Android checks into these
 * three booleans.
 */
fun resolveBackgroundLaunchRoute(
    visible: Boolean,
    assistantBindingAvailable: Boolean,
    overlayExempt: Boolean
): BackgroundLaunchRoute = when {
    visible -> BackgroundLaunchRoute.DIRECT
    assistantBindingAvailable -> BackgroundLaunchRoute.SELECTED_ASSISTANT
    overlayExempt -> BackgroundLaunchRoute.OVERLAY_EXEMPT
    else -> BackgroundLaunchRoute.NONE
}

/**
 * Local-refusal receipt: Jarvis could not establish a supported
 * background-launch route, so nothing was attempted. Worded as Jarvis's own
 * limitation — never as an observed Android block, and never implying the
 * user must grant overlay permission when Android might permit another
 * route.
 */
fun backgroundLaunchRefusal(label: String): ExecutionResult =
    ExecutionResult(
        false,
        "Jarvis could not establish a supported background-launch route for $label, " +
            "so the launch was not attempted."
    )

/**
 * Receipt for a launch that reached the platform. Success requires the
 * destination to be observed in the foreground — a submitted request alone
 * is reported honestly as unconfirmed.
 */
fun verifiedLaunchReceipt(
    label: String,
    route: BackgroundLaunchRoute,
    platformError: String?,
    foregroundObserved: Boolean
): ExecutionResult {
    if (platformError != null) {
        return ExecutionResult(false, "Could not open $label: $platformError")
    }
    if (foregroundObserved) return ExecutionResult(true, "Opening $label.")
    return ExecutionResult(
        false,
        "The launch request for $label was submitted through the " +
            route.name.lowercase().replace('_', ' ') + " route, but $label did not come " +
            "to the foreground, so I could not confirm it opened."
    )
}
