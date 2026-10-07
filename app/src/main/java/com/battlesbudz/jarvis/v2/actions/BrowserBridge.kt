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
     * the platform autofill path.
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
