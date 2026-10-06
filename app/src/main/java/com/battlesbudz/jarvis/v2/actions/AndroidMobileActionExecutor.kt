package com.battlesbudz.jarvis.v2.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.view.KeyEvent
import kotlin.math.round

class AndroidMobileActionExecutor(
    private val context: Context,
    private val canLaunchDirectly: () -> Boolean = { false },
    private val onDiagnostic: (String) -> Unit = {},
    private val screenBridge: ScreenBridge = ScreenControlService.bridge(context),
    val screenSession: ScreenControlSession = ScreenControlService.sharedSession,
    /**
     * Scheduling bridge behind create_reminder/show_schedule. Defaults to the
     * context itself when it implements [ReminderScheduling] (JarvisRuntime
     * does), so production call sites need no changes; journeys pass an
     * explicit coordinator over a scratch store.
     */
    reminderScheduling: ReminderScheduling? = null,
    /**
     * Background-launch seams (finding 3): injectable so the route matrix
     * and the visibility observation are unit-testable. Defaults are the
     * production Android checks.
     */
    private val assistantBindingAvailable: () -> Boolean = {
        com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService.isSelected(context)
    },
    private val assistantLaunch: (Intent, String) -> ExecutionResult? = { intent, label ->
        com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService.launch(context, intent, label)
    },
    private val overlayExempt: () -> Boolean = {
        android.provider.Settings.canDrawOverlays(context)
    },
    private val foregroundObserver: (String) -> Boolean = { packageName ->
        isPackageForeground(packageName)
    },
    /** How long to wait for the destination to reach the foreground after a submitted launch. */
    private val launchPollMs: Long = 1200L,
    private val launchPollStepMs: Long = 100L
) : MobileActionExecutor {
    private val scheduling: ReminderScheduling? = reminderScheduling ?: (context as? ReminderScheduling)
    private val appResolver = InstalledAppResolver(context)
    override fun execute(action: MobileAction): ExecutionResult = when (action) {
        MobileAction.ReadBattery -> {
            val batteryManager = context.getSystemService(BatteryManager::class.java)
            val percent = batteryManager?.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CAPACITY
            )
            if (percent == null || percent !in 0..100) {
                ExecutionResult(false, "Battery status is unavailable.")
            } else {
                ExecutionResult.battery(percent)
            }
        }
        is MobileAction.SetVolume -> {
            val audioManager = context.getSystemService(AudioManager::class.java)
            val max = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            if (max == 0) {
                ExecutionResult(false, "Media volume is unavailable.")
            } else {
                val target = round(max * action.level / 100.0).toInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                val actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (actual != target) {
                    ExecutionResult(false, "Android did not apply the requested media volume.")
                } else {
                    ExecutionResult(true, "Media volume set to ${action.level} percent.")
                }
            }
        }
        is MobileAction.OpenApp -> when (val resolution = appResolver.resolve(
            action.appName,
            action.packageNameHint
        )) {
            is AppResolution.NotFound ->
                ExecutionResult(false, "I could not find an installed app named ${resolution.requestedName}.")
            is AppResolution.Ambiguous ->
                ExecutionResult(
                    false,
                    "I found multiple apps matching ${resolution.requestedName}: " +
                        resolution.matches.joinToString { it.label } +
                        ". Please specify one."
                )
            is AppResolution.Found -> {
                val launchIntent = Intent().setClassName(
                    resolution.app.packageName,
                    resolution.app.activityName
                )
                // Background-launch reporting (finding 3): the route,
                // refusal, platform verdict and foreground observation are
                // reported distinctly — never an inferred "Android blocked"
                // diagnosis.
                launchViaRoute(launchIntent, resolution.app.label, resolution.app.packageName)
            }
        }
        is MobileAction.MediaControl -> {
            val audioManager = context.getSystemService(AudioManager::class.java)
            if (audioManager == null) {
                ExecutionResult(false, "Media control is unavailable.")
            } else {
                val keyCode = when (action.action) {
                    MediaControlAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                    MediaControlAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    MediaControlAction.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    MediaControlAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    MediaControlAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                }
                val eventTime = SystemClock.uptimeMillis()
                audioManager.dispatchMediaKeyEvent(
                    KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
                )
                audioManager.dispatchMediaKeyEvent(
                    KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
                )
                // The key event is dispatched to the active media session; Android does
                // not report whether a session consumed it, so the receipt describes the
                // dispatch honestly rather than claiming a playback state change.
                ExecutionResult(
                    true,
                    "Sent ${action.action.label} command to the active media session."
                )
            }
        }
        is MobileAction.OpenWebsite -> dispatchViewIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(action.url)),
            label = action.url,
            openedText = "Opening"
        )
        is MobileAction.OpenSettings -> dispatchViewIntent(
            Intent(action.screen.intentAction),
            label = "${action.screen.label} settings",
            openedText = "Opening"
        )
        is MobileAction.Navigate -> dispatchViewIntent(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://www.google.com/maps/dir/?api=1&destination=" +
                        Uri.encode(action.destination)
                )
            ),
            label = action.destination,
            openedText = "Showing directions to"
        )
        MobileAction.ScreenObserve -> observeScreen()
        is MobileAction.ScreenTap -> dispatchScreenMutation(
            verb = "tap",
            targetId = action.targetId,
            token = action.token,
            requireNode = { node ->
                if (!node.clickable) "Target ${node.id} (\"${node.label}\") is not tappable. " +
                    "Pick a button from the latest screen_observe result." else null
            },
            perform = { bridge, node -> bridge.tap(node) },
            successText = { node -> "Tapped \"${node.label}\"." }
        )
        is MobileAction.ScreenScroll -> dispatchScreenMutation(
            verb = "scroll ${action.direction.key}",
            targetId = action.targetId,
            token = action.token,
            requireNode = { node ->
                if (!node.scrollable) "Target ${node.id} (\"${node.label}\") is not scrollable. " +
                    "Pick a list from the latest screen_observe result." else null
            },
            perform = { bridge, node -> bridge.scroll(node, action.direction) },
            successText = { node -> "Scrolled \"${node.label}\" ${action.direction.key}." }
        )
        is MobileAction.ScreenType -> dispatchScreenMutation(
            verb = "type into",
            targetId = action.targetId,
            token = action.token,
            requireNode = { node ->
                if (!node.editable) "Target ${node.id} (\"${node.label}\") is not an editable field. " +
                    "Pick a field from the latest screen_observe result." else null
            },
            perform = { bridge, node -> bridge.type(node, action.text) },
            successText = { node -> "Typed into \"${node.label}\"." }
        )
        is MobileAction.CreateReminder -> {
            val bridge = scheduling
            if (bridge == null) {
                onDiagnostic("create_reminder result=unavailable reason=no_scheduling_bridge")
                ExecutionResult(false, "Reminders are unavailable right now, so nothing was scheduled.")
            } else {
                val result = bridge.createReminder(action.message, action.atMs)
                onDiagnostic("create_reminder result=${result.succeeded} message=${result.message.take(80)}")
                result
            }
        }
        is MobileAction.ShowSchedule -> {
            val bridge = scheduling
            if (bridge == null) {
                onDiagnostic("show_schedule result=unavailable reason=no_scheduling_bridge")
                ExecutionResult(false, "I couldn't read the schedule right now.")
            } else {
                bridge.describeSchedule()
            }
        }
        is MobileAction.PostNotification -> {
            val posted = ReminderNotification.post(context, action.title, action.text)
            onDiagnostic("post_notification result=$posted title=${action.title.take(40)}")
            if (posted) ExecutionResult(true, "Posted the reminder notification.")
            else ExecutionResult(false,
                "I couldn't post the reminder notification: notifications are disabled or permission was denied.")
        }
    }

    /**
     * One background-launch decision for app and view intents. The four
     * outcomes are reported distinctly: local refusal (Jarvis never asked
     * Android), submitted request (the assistant binding's own receipt),
     * platform rejection (startActivity threw), and observed foreground
     * transition (destination visibility confirmed — the only verified
     * launch).
     */
    private fun launchViaRoute(intent: Intent, label: String, observePackage: String?): ExecutionResult {
        val visible = canLaunchDirectly()
        val bindingAvailable = assistantBindingAvailable()
        val route = resolveBackgroundLaunchRoute(visible, bindingAvailable, overlayExempt())
        onDiagnostic("App launch route=${route.name.lowercase()} visible=$visible binding=$bindingAvailable label=$label")
        return when (route) {
            BackgroundLaunchRoute.SELECTED_ASSISTANT -> {
                val result = assistantLaunch(intent, label)
                if (result != null) return result
                // The binding was unavailable after all: fall through to the
                // remaining routes rather than misreporting a refusal.
                onDiagnostic("App launch route=assistant_binding_unavailable label=$label")
                launchDirectOrRefuse(intent, label, observePackage, visible)
            }
            BackgroundLaunchRoute.DIRECT, BackgroundLaunchRoute.OVERLAY_EXEMPT ->
                submitLaunch(intent, label, observePackage, route)
            BackgroundLaunchRoute.NONE -> {
                onDiagnostic("App launch result=local_refusal visible=$visible label=$label")
                backgroundLaunchRefusal(label)
            }
        }
    }

    /** Non-assistant routes after the binding proved unavailable: direct, overlay-exempt, or local refusal. */
    private fun launchDirectOrRefuse(
        intent: Intent,
        label: String,
        observePackage: String?,
        visible: Boolean
    ): ExecutionResult {
        val route = resolveBackgroundLaunchRoute(visible, assistantBindingAvailable = false, overlayExempt = overlayExempt())
        return when (route) {
            BackgroundLaunchRoute.DIRECT, BackgroundLaunchRoute.OVERLAY_EXEMPT ->
                submitLaunch(intent, label, observePackage, route)
            else -> {
                onDiagnostic("App launch result=local_refusal visible=$visible label=$label")
                backgroundLaunchRefusal(label)
            }
        }
    }

    /**
     * Fire a NEW_TASK launch and report the platform's verdict. Callers must
     * only reach this when the launch is eligible: the activity is visible
     * or a background-activity-start exemption applies. startActivity
     * returns void, so a clean return is only a submitted request — the
     * destination must then be observed in the foreground before the launch
     * counts as verified. Only the caught rejections are reported as
     * platform rejections; silent background drops surface as
     * submitted-but-unconfirmed, never as success.
     */
    private fun submitLaunch(
        intent: Intent,
        label: String,
        observePackage: String?,
        route: BackgroundLaunchRoute
    ): ExecutionResult = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        onDiagnostic("App launch route=${route.name.lowercase()} label=$label result=submitted")
        val observed = observePackage != null && awaitForeground(observePackage)
        onDiagnostic("App launch route=${route.name.lowercase()} label=$label " +
            "result=${if (observed) "foreground_observed" else "foreground_unobserved"}")
        verifiedLaunchReceipt(label, route, platformError = null, foregroundObserved = observed)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: android.content.ActivityNotFoundException) {
        onDiagnostic("App launch result=platform_rejected type=ActivityNotFoundException label=$label")
        verifiedLaunchReceipt(label, route,
            platformError = error.message ?: "Android rejected the launch.", foregroundObserved = false)
    } catch (error: SecurityException) {
        onDiagnostic("App launch result=platform_rejected type=SecurityException label=$label")
        verifiedLaunchReceipt(label, route,
            platformError = error.message ?: "Android rejected the launch.", foregroundObserved = false)
    }

    /** Poll briefly for the destination package to reach the foreground. */
    private fun awaitForeground(packageName: String): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + launchPollMs
        do {
            if (foregroundObserver(packageName)) return true
            android.os.SystemClock.sleep(launchPollStepMs.coerceAtLeast(1))
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        return false
    }

    /** Production foreground check behind [foregroundObserver]. */
    private fun isPackageForeground(packageName: String): Boolean {
        val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return false
        return manager.runningAppProcesses.orEmpty().any { proc ->
            proc.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                (proc.processName == packageName || proc.processName.startsWith("$packageName:"))
        }
    }

    /**
     * Dispatch a view intent through the same launch path as OpenApp: the
     * assistant service when the activity is not visible, otherwise a direct
     * or exemption-backed startActivity. A background launch with no
     * supported route is a local refusal, never an optimistic "requested"
     * success; a submitted launch only counts as verified when the resolved
     * destination package is observed in the foreground.
     */
    private fun dispatchViewIntent(
        intent: Intent,
        label: String,
        openedText: String
    ): ExecutionResult {
        val observePackage = intent.resolveActivity(context.packageManager)?.packageName
        val result = launchViaRoute(intent, label, observePackage)
        // Preserve the caller's wording for the verified launch.
        return if (result.succeeded) ExecutionResult(true, "$openedText $label.") else result
    }

    /**
     * M1c screen observation. Read-only: needs no session grant, but every
     * observation rotates the token that later mutations must present.
     */
    private fun observeScreen(): ExecutionResult {
        if (!screenBridge.isAvailable()) {
            onDiagnostic("screen_observe result=unavailable")
            return ExecutionResult(
                false,
                "Screen observation is not available. Enable Jarvis screen control " +
                    "in Android Accessibility settings, then try again."
            )
        }
        val observation = screenBridge.observe()
        if (observation == null) {
            onDiagnostic("screen_observe result=empty")
            return ExecutionResult(false, "I could not read the current screen. Nothing was tapped or typed.")
        }
        val token = screenSession.recordObservation(observation)
        onDiagnostic("screen_observe result=observed nodes=${observation.nodes.size} pkg=${observation.packageName}")
        return ExecutionResult(true, observation.compactText(token))
    }

    /**
     * M1c screen mutation with verified targets. The session gate enforces the
     * grant (D23), user-touch pause with re-observe resume (T06), and the stop
     * request; [ScreenControlSession.verifyTarget] rejects stale tokens/targets
     * and the bridge re-verifies the live node before dispatching.
     */
    private fun dispatchScreenMutation(
        verb: String,
        targetId: String,
        token: String,
        requireNode: (ScreenNode) -> String?,
        perform: (ScreenBridge, ScreenNode) -> Boolean,
        successText: (ScreenNode) -> String
    ): ExecutionResult {
        when (screenSession.dispatchGate()) {
            DispatchGate.Stopped ->
                return ExecutionResult(false, "The screen task was stopped.")
            DispatchGate.NeedsAdmission ->
                return ExecutionResult(
                    false,
                    "Screen control needs your approval for this task before I can $verb. " +
                        "Approve the screen task first."
                )
            DispatchGate.Paused ->
                return ExecutionResult(
                    false,
                    "Paused while you are touching the screen. I will look at the screen " +
                        "again and resume when you stop."
                )
            DispatchGate.ResumeReobserve -> {
                // Idle resume re-observes the changed screen without a countdown
                // (T06). The token rotates, so the caller's token is now stale by
                // design and the mutation below is rejected as stale.
                val fresh = screenBridge.observe()
                if (fresh == null) {
                    return ExecutionResult(false, "I could not read the current screen after you stopped touching it.")
                }
                screenSession.recordObservation(fresh)
                onDiagnostic("screen_$verb result=reobserved nodes=${fresh.nodes.size} pkg=${fresh.packageName}")
            }
            DispatchGate.Allowed -> Unit
        }
        val liveWindowIdentity = screenBridge.currentWindowIdentity()
        val node = when (val verified = screenSession.verifyTarget(targetId, token, liveWindowIdentity, requireNode)) {
            is TargetVerification.Verified -> verified.node
            is TargetVerification.Rejected -> {
                onDiagnostic("screen_$verb result=rejected reason=${verified.reason.take(80)}")
                return ExecutionResult(false, verified.reason)
            }
        }
        onDiagnostic("screen_$verb target=$targetId label=\"${node.label}\"")
        val completed = try {
            perform(screenBridge, node)
        } catch (_: SecurityException) {
            return ExecutionResult(
                ExecutionResult.Outcome.DENIED_PERMISSION,
                "Android denied the screen $verb."
            )
        }
        // performAction returns whether Android performed the action, so the
        // receipt reports the real outcome instead of claiming success.
        return if (completed) ExecutionResult(true, successText(node))
        else ExecutionResult(
            false,
            "I could not $verb \"${node.label}\" — the screen may have changed. " +
                "Call screen_observe again for fresh targets."
        )
    }
}
