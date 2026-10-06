package com.battlesbudz.jarvis.v2.actions

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.graphics.PixelFormat
import android.os.Bundle
import android.graphics.Rect
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button

/**
 * M1c screen-control accessibility service. Observes the active window as a
 * compact node list and performs tap/scroll/type only on nodes re-verified
 * against a fresh tree walk — a stale target ID can never dispatch.
 *
 * Touch-interaction events feed [ScreenControlSession]; while the user touches
 * the screen, dispatch pauses, and the next mutation re-observes first (T06).
 * The user enables this service in Android Accessibility settings (D09); until
 * then the bridge reports unavailable and every screen tool answers honestly.
 */
class ScreenControlService : AccessibilityService() {
    companion object {
        @Volatile private var instance: ScreenControlService? = null

        /** Process-wide screen session: one grant per task group (D26). */
        val sharedSession = ScreenControlSession()

        fun bridge(context: android.content.Context): ScreenBridge = ServiceScreenBridge(context)

        internal fun connectedInstance(): ScreenControlService? = instance

        fun isEnabled(context: android.content.Context): Boolean {
            val expected = ComponentName(context, ScreenControlService::class.java).flattenToString()
            return Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().split(':').any { it == expected }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        // Jarvis's own windows (the stop overlay, approval UI) share the app
        // package: handle them explicitly first, so they can neither
        // invalidate the observation nor inherit its approvals, regardless of
        // what the generic rules below do. They still feed touch state like
        // any window, because the user touching our own overlay is still the
        // user touching the screen.
        if (event.packageName?.toString() == packageName) {
            when (type) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> sharedSession.noteTouchStart()
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> sharedSession.noteTouchEnd()
                // Own-UI window and content events are explicitly ignored:
                // the overlay is ours, never a dispatch target.
                else -> Unit
            }
            return
        }
        when (type) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> sharedSession.noteTouchStart()
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> sharedSession.noteTouchEnd()
            // A window from a different package becoming active invalidates
            // the observation: approvals bind to one window identity and never
            // cross windows. A null event package means the window cannot be
            // identified, which fails closed like any unestablished identity.
            // Content-change events deliberately do not invalidate here: they
            // are too noisy, and dispatch-time content-generation verification
            // already covers them.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (windowChangeInvalidatesObservation(event.packageName?.toString(), packageName))
                    sharedSession.invalidateObservation()
            else -> Unit
        }
    }

    override fun onInterrupt() { /* No reservation is tied to this service's lifetime. */ }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

/** Walks [root] depth-first and returns the compact actionable/labeled nodes. Root is not recycled here. */
fun extractScreenNodes(root: AccessibilityNodeInfo, maxNodes: Int = 200): List<ScreenNode> {
    val out = mutableListOf<ScreenNode>()
    val index = intArrayOf(0)
    val windowIdentity = windowIdentityOf(root)
    walkScreenNode(root, 0, out, index, maxNodes, windowIdentity)
    return out
}

/** Identity of the active window [root] came from: the package plus the active-window id. */
private fun windowIdentityOf(root: AccessibilityNodeInfo): String =
    "${root.packageName?.toString().orEmpty()}#${root.windowId}"

private fun walkScreenNode(
    node: AccessibilityNodeInfo,
    depth: Int,
    out: MutableList<ScreenNode>,
    index: IntArray,
    maxNodes: Int,
    windowIdentity: String
) {
    if (out.size >= maxNodes || depth > 25) return
    if (isScreenActionable(node)) {
        out.add(toScreenNode(node, "n${index[0]++}", windowIdentity))
    }
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        try {
            walkScreenNode(child, depth + 1, out, index, maxNodes, windowIdentity)
        } finally {
            child.recycle()
        }
    }
}

private fun isScreenActionable(node: AccessibilityNodeInfo): Boolean =
    node.isClickable || node.isEditable || node.isScrollable ||
        !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()

private fun screenNodeLabel(node: AccessibilityNodeInfo): String =
    (node.text?.toString().takeIf { !it.isNullOrBlank() }
        ?: node.contentDescription?.toString().orEmpty()).trim().take(80)

private fun screenNodeRole(node: AccessibilityNodeInfo): String = when {
    node.isEditable -> "field"
    node.isScrollable -> "list"
    node.isClickable -> "button"
    !node.text.isNullOrBlank() -> "text"
    else -> "image"
}

private fun toScreenNode(node: AccessibilityNodeInfo, id: String, windowIdentity: String): ScreenNode {
    return ScreenNode(
        id = id,
        label = screenNodeLabel(node),
        role = screenNodeRole(node),
        bounds = screenNodeBounds(node),
        clickable = node.isClickable,
        editable = node.isEditable,
        scrollable = node.isScrollable,
        viewId = node.viewIdResourceName,
        windowIdentity = windowIdentity
    )
}

private fun screenNodeBounds(node: AccessibilityNodeInfo): String {
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    return "${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}"
}

/** Finds the live node at the same walk position as [id] ("n<index>"). Caller must recycle the result. */
private fun findScreenNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
    val wanted = id.removePrefix("n").toIntOrNull() ?: return null
    val seen = intArrayOf(0)
    fun walk(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 25) return null
        if (isScreenActionable(node)) {
            if (seen[0]++ == wanted) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = walk(child, depth + 1)
            // getChild copies are independent instances: recycle every copy
            // except the one being returned to the caller.
            if (found !== child) child.recycle()
            if (found != null) return found
        }
        return null
    }
    return walk(root, 0)
}

private fun matchesSnapshot(live: AccessibilityNodeInfo, snapshot: ScreenNode): Boolean =
    snapshot.matchesLiveNode(
        screenNodeRole(live),
        screenNodeLabel(live),
        screenNodeBounds(live),
        live.viewIdResourceName
    )

private class ServiceScreenBridge(
    private val context: android.content.Context
) : ScreenBridge {
    private fun service(): ScreenControlService? = ScreenControlService.connectedInstance()

    override fun isAvailable(): Boolean = service() != null

    override fun observe(): ScreenObservation? {
        val service = service() ?: return null
        val root = service.rootInActiveWindow ?: return null
        return try {
            ScreenObservation(
                packageName = root.packageName?.toString().orEmpty(),
                nodes = extractScreenNodes(root),
                windowIdentity = windowIdentityOf(root)
            )
        } finally {
            root.recycle()
        }
    }

    /** Live identity of the active window; null when it cannot be established. */
    override fun currentWindowIdentity(): String? {
        val service = service() ?: return null
        val root = service.rootInActiveWindow ?: return null
        return try {
            windowIdentityOf(root)
        } finally {
            root.recycle()
        }
    }

    /**
     * Content generation of the live active window: the fingerprint of the
     * exact node content currently on screen. Null when the service or the
     * active window is unavailable — dispatch fails closed in that case.
     */
    override fun currentContentFingerprint(): String? {
        val service = service() ?: return null
        val root = service.rootInActiveWindow ?: return null
        return try {
            contentFingerprintOf(extractScreenNodes(root))
        } finally {
            root.recycle()
        }
    }

    override fun tap(node: ScreenNode): Boolean = withLiveNode(node) { target ->
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun scroll(node: ScreenNode, direction: ScreenScrollDirection): Boolean =
        withLiveNode(node) { target ->
            if (!target.isScrollable) return@withLiveNode false
            val action = when (direction) {
                ScreenScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                ScreenScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            target.performAction(action)
        }

    override fun type(node: ScreenNode, text: String): Boolean = withLiveNode(node) { target ->
        if (!target.isEditable) return@withLiveNode false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Re-walks the live tree, re-verifies the node against the snapshot, then
     * dispatches. The live active window must still carry the observed window
     * identity — an approval for one window never dispatches in another —
     * and a target whose label/role/bounds/view id changed since observation
     * is stale and never dispatches.
     */
    private inline fun withLiveNode(node: ScreenNode, perform: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val service = service() ?: return false
        val root = service.rootInActiveWindow ?: return false
        try {
            val liveIdentity = windowIdentityOf(root)
            if (node.windowIdentity.isBlank() || node.windowIdentity != liveIdentity) return false
            val target = findScreenNodeById(root, node.id) ?: return false
            try {
                if (!matchesSnapshot(target, node)) return false
                return perform(target)
            } finally {
                // The match may be the root itself; it is recycled by the outer block.
                if (target !== root) target.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    override fun showStopOverlay(taskLabel: String): Boolean {
        val service = service() ?: return false
        if (overlayView != null) return true
        if (!Settings.canDrawOverlays(service)) return false
        return try {
            val windowManager = service.getSystemService(WindowManager::class.java) ?: return false
            val button = Button(service).apply {
                text = "Stop: ${taskLabel.take(24)}"
                setOnClickListener {
                    ScreenControlService.sharedSession.requestStop()
                    hideStopOverlay()
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                y = 320
            }
            windowManager.addView(button, params)
            overlayView = button to windowManager
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun hideStopOverlay() {
        val (view, windowManager) = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) { /* Already gone; the session state is what matters. */ }
    }

    companion object {
        @Volatile private var overlayView: Pair<android.view.View, WindowManager>? = null
    }
}
