package com.battlesbudz.jarvis.v2.actions

/**
 * M4 browser tasks: the Android backend seam behind the internal browser.
 *
 * The JVM-pure [BrowserSession] owns history, tokens, fills and the
 * submission gate; the bridge fetches real page content. A fake bridge
 * drives the JVM contract tests; production uses the WebView backend.
 */
data class BrowserPageSnapshot(
    val url: String,
    val title: String,
    val textExcerpt: String,
    val links: List<BrowserLink>,
    val forms: List<BrowserForm>,
    /** Cheap DOM hash so takeover-resume can detect changed pages. */
    val contentFingerprint: String
)

enum class CredentialFillOutcome { FILLED, NO_CREDENTIALS, CANCELLED, UNAVAILABLE }

interface BrowserBridge {
    /** False until the backend is ready; dispatches answer honestly meanwhile. */
    fun isAvailable(): Boolean
    /** Begin loading the URL; true when the load was handed to the backend. */
    fun open(url: String): Boolean
    /** Latest observed snapshot, or null when nothing is loaded yet. */
    fun snapshot(): BrowserPageSnapshot?
    /**
     * Re-extract the live page now and return the fresh snapshot. SPAs mutate
     * the DOM without page loads, so mutating dispatches call this before
     * reconciling: the approval gate must see the DOM it is about to act on,
     * not the last navigation's snapshot.
     *
     * Fail-closed contract: return null when the live page cannot be
     * re-extracted (no live activity, backend timeout, backend gone) — never
     * a stale snapshot dressed as fresh. Callers refuse the dispatch on
     * null; a stale approval must not reach a replaced document. The default
     * answers the cached snapshot; the WebView backend re-extracts on demand.
     */
    fun refreshSnapshot(): BrowserPageSnapshot? = snapshot()
    /** The content fingerprint for the latest snapshot, or null. */
    fun snapshotFingerprint(): String?
    fun clickLink(id: String): Boolean
    fun goBack(): Boolean
    fun goForward(): Boolean
    fun fillField(id: String, text: String): Boolean
    fun submitForm(): Boolean
    /**
     * Ask the platform password manager to fill the current page's login
     * form for [host]. Only the outcome returns: credentials never leave
     * the platform autofill path. The backend reports FILLED only after
     * verifying a password field actually became non-empty (a boolean
     * check — the secret value itself is never read back); a focused-but-
     * empty field is NO_CREDENTIALS, never FILLED.
     */
    fun requestCredentialFill(host: String): CredentialFillOutcome
    fun currentUrl(): String?
}

/** JVM-test fake: scripted snapshots, records every call. */
class FakeBrowserBridge : BrowserBridge {
    var available: Boolean = true
    val opened = mutableListOf<String>()
    var snapshotToReturn: BrowserPageSnapshot? = null
    var credentialOutcome: CredentialFillOutcome = CredentialFillOutcome.FILLED
    val filledFields = mutableMapOf<String, String>()
    var submittedForms: Int = 0
    var clickedLinks = mutableListOf<String>()
    var backCount: Int = 0
    var forwardCount: Int = 0

    override fun isAvailable(): Boolean = available
    override fun open(url: String): Boolean {
        if (!available) return false
        opened.add(url)
        return true
    }
    override fun snapshot(): BrowserPageSnapshot? = snapshotToReturn
    /**
     * Scripted live-DOM change: when set, refreshSnapshot() swaps this in as
     * the current snapshot, simulating an SPA DOM replacement or a takeover
     * the backend re-extracted. [refreshCalls] counts refresh attempts.
     *
     * Fail-closed seam: when [refreshFails] is true, refreshSnapshot()
     * returns null — the production equivalent of a missing activity or a
     * re-extract timeout — and the executor must refuse the dispatch.
     */
    var refreshedSnapshot: BrowserPageSnapshot? = null
    var refreshFails: Boolean = false
    var refreshCalls: Int = 0
        private set
    override fun refreshSnapshot(): BrowserPageSnapshot? {
        refreshCalls++
        if (refreshFails) return null
        refreshedSnapshot?.let { snapshotToReturn = it }
        return snapshotToReturn
    }
    override fun snapshotFingerprint(): String? = snapshotToReturn?.contentFingerprint
    override fun clickLink(id: String): Boolean {
        if (!available) return false
        clickedLinks.add(id)
        return true
    }
    override fun goBack(): Boolean {
        if (!available) return false
        backCount++
        return true
    }
    override fun goForward(): Boolean {
        if (!available) return false
        forwardCount++
        return true
    }
    override fun fillField(id: String, text: String): Boolean {
        if (!available) return false
        filledFields[id] = text
        return true
    }
    override fun submitForm(): Boolean {
        if (!available) return false
        submittedForms++
        return true
    }
    override fun requestCredentialFill(host: String): CredentialFillOutcome =
        if (!available) CredentialFillOutcome.UNAVAILABLE else credentialOutcome
    override fun currentUrl(): String? = snapshotToReturn?.url
}
