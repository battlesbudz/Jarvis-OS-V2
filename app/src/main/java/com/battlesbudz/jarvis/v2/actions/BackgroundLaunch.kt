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
 * - observed foreground transition: recorded in diagnostics when an
 *   injectable observer reports it — best-effort, not a success gate, since
 *   Android offers no reliable API for observing another app's foreground
 *   status. A submitted request is reported honestly as submitted; callers
 *   that can observe visibility (e.g., instrumentation via UiDevice) verify
 *   the transition themselves.
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
 * Receipt for a launch that reached the platform.
 *
 * Four states, never conflated (see the file doc): a platform error is a
 * failure; otherwise the launch was submitted and the receipt is a success.
 * Foreground observation is best-effort and recorded in diagnostics — it is
 * not a success gate, because Android offers no reliable API for an app to
 * observe another app's foreground status (ActivityManager.runningAppProcesses
 * cannot see other apps' processes on modern Android). Callers that can
 * observe visibility (e.g., instrumentation via UiDevice) verify it themselves.
 */
fun verifiedLaunchReceipt(
    label: String,
    route: BackgroundLaunchRoute,
    platformError: String?,
): ExecutionResult {
    if (platformError != null) {
        return ExecutionResult(false, "Could not open $label: $platformError")
    }
    return ExecutionResult(true, "Opening $label.")
}
