package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * M4 approval-binding contract at the executor seam (needs an Android
 * Context for construction, hence Robolectric; the WebView backend itself
 * is replaced by [FakeBrowserBridge]).
 *
 * Every test proves the same invariant: between approval and dispatch the
 * page may change, and when it does the old token dies — the approval is
 * invalidated and the bridge performs ZERO submission/navigation/fill.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidBrowserExecutorTest {
    private var tokenCounter = 0

    private fun loginSnapshot(
        fingerprint: String = "fp-login",
        actionUrl: String = "https://example.com/session",
        links: List<BrowserLink> = listOf(BrowserLink("l0", "Home", "https://example.com/"))
    ): BrowserPageSnapshot = BrowserPageSnapshot(
        url = "https://example.com/login",
        title = "Example login",
        textExcerpt = "Sign in to Example",
        links = links,
        forms = listOf(
            BrowserForm(
                id = "form0",
                actionUrl = actionUrl,
                method = "POST",
                fields = listOf(
                    BrowserField("f0", "Email", FieldKind.EMAIL),
                    BrowserField("f1", "Password", FieldKind.PASSWORD, secret = true)
                ),
                submitLabel = "Sign in"
            )
        ),
        contentFingerprint = fingerprint
    )

    private inner class Harness {
        val session = BrowserSession(
            clock = { 1_700_000_000_000L },
            newToken = { "t${tokenCounter++}".padEnd(16, '0') }
        )
        val bridge = FakeBrowserBridge()
        val executor = AndroidBrowserExecutor(
            RuntimeEnvironment.getApplication(),
            MobileActionExecutor { ExecutionResult(true, "delegated") },
            session = session,
            bridge = bridge
        )

        /** Read the login page; returns its live token. */
        fun readLogin(): String {
            bridge.snapshotToReturn = loginSnapshot()
            val result = executor.execute(MobileAction.BrowseRead)
            assertTrue("browse_read must succeed, got ${result.message}", result.succeeded)
            return session.currentPage()!!.pageToken
        }
    }

    @Test fun mutatingDispatchRefreshesSnapshotFirst() {
        val h = Harness()
        val token = h.readLogin()
        // The SPA replaces the DOM without a navigation: same URL, new
        // fingerprint, changed form.
        h.bridge.refreshedSnapshot = loginSnapshot(
            fingerprint = "fp-v2", actionUrl = "https://example.com/session-v2")
        val result = h.executor.execute(MobileAction.BrowseFill("f0", "user@example.com", token))
        assertTrue("the backend must be re-extracted before a mutating dispatch",
            h.bridge.refreshCalls >= 1)
        assertFalse("a fill against the replaced DOM must not succeed", result.succeeded)
        assertTrue("stale token must be reported, got: ${result.message}",
            result.message.contains("page changed"))
        assertTrue("zero fill may reach the backend on a stale token",
            h.bridge.filledFields.isEmpty())
    }

    @Test fun sameUrlDomReplacementInvalidatesApprovalAndPerformsZeroSubmission() {
        val h = Harness()
        val token = h.readLogin()
        assertTrue(h.executor.execute(
            MobileAction.BrowseFill("f0", "user@example.com", token)).succeeded)
        h.session.admitSubmit(h.session.proposeSubmit(token)!!)
        // The DOM is replaced between approval and dispatch: the form now
        // posts somewhere else.
        h.bridge.refreshedSnapshot = loginSnapshot(
            fingerprint = "fp-hijacked", actionUrl = "https://evil.example/collect")
        val result = h.executor.execute(MobileAction.BrowseSubmit(token))
        assertFalse("a stale approval must never submit", result.succeeded)
        assertTrue("stale token must be reported, got: ${result.message}",
            result.message.contains("page changed"))
        assertEquals("zero submission may reach the backend", 0, h.bridge.submittedForms)
    }

    @Test fun changedFieldValuesInvalidateAdmission() {
        val h = Harness()
        val token = h.readLogin()
        assertTrue(h.executor.execute(
            MobileAction.BrowseFill("f0", "user@example.com", token)).succeeded)
        h.session.admitSubmit(h.session.proposeSubmit(token)!!)
        // A fill changes after the approval: the admission dies with it.
        assertTrue(h.executor.execute(
            MobileAction.BrowseFill("f0", "someone-else@example.com", token)).succeeded)
        val result = h.executor.execute(MobileAction.BrowseSubmit(token))
        assertFalse("changed fills need a fresh approval", result.succeeded)
        assertTrue("the receipt must ask for approval, got: ${result.message}",
            result.message.contains("Approve the submission"))
        assertEquals("zero submission may reach the backend", 0, h.bridge.submittedForms)
    }

    @Test fun manualTakeoverBetweenApprovalAndDispatchPerformsZeroSubmission() {
        val h = Harness()
        val token = h.readLogin()
        assertTrue(h.executor.execute(
            MobileAction.BrowseFill("f0", "user@example.com", token)).succeeded)
        h.session.admitSubmit(h.session.proposeSubmit(token)!!)
        // The user takes over and the DOM changes (e.g. the login completes).
        h.session.pauseForTakeover()
        h.bridge.refreshedSnapshot = loginSnapshot(fingerprint = "fp-after-login")
        val result = h.executor.execute(MobileAction.BrowseSubmit(token))
        assertFalse("a takeover must invalidate the approval", result.succeeded)
        assertTrue("takeover must be reported, got: ${result.message}",
            result.message.contains("changed while you were using"))
        assertEquals("zero submission may reach the backend", 0, h.bridge.submittedForms)
    }

    @Test fun browseClickOnReplacedDomPerformsZeroNavigation() {
        val h = Harness()
        val token = h.readLogin()
        h.bridge.refreshedSnapshot = loginSnapshot(
            fingerprint = "fp-v2",
            links = listOf(BrowserLink("l0", "Elsewhere", "https://elsewhere.example/")))
        val result = h.executor.execute(MobileAction.BrowseClick("l0", token))
        assertFalse(result.succeeded)
        assertTrue(h.bridge.clickedLinks.isEmpty())
    }

    @Test fun credentialFillOutcomeMappingIsHonest() {
        val h = Harness()
        val token = h.readLogin()
        h.bridge.credentialOutcome = CredentialFillOutcome.FILLED
        val filled = h.executor.execute(MobileAction.BrowseLogin(token))
        assertTrue("a verified fill succeeds, got: ${filled.message}", filled.succeeded)
        assertTrue(filled.message.contains("password manager"))

        h.bridge.credentialOutcome = CredentialFillOutcome.NO_CREDENTIALS
        val none = h.executor.execute(MobileAction.BrowseLogin(token))
        assertFalse(none.succeeded)
        // Neutral: the backend cannot tell "no login saved" from "the user
        // dismissed the prompt", so neither may be claimed.
        assertTrue("got: ${none.message}", none.message.contains("didn't fill a login"))
        assertFalse("got: ${none.message}", none.message.contains("no login saved"))

        h.bridge.credentialOutcome = CredentialFillOutcome.UNAVAILABLE
        val unavailable = h.executor.execute(MobileAction.BrowseLogin(token))
        assertFalse(unavailable.succeeded)
        assertTrue("got: ${unavailable.message}", unavailable.message.contains("isn't available"))
    }

    @Test fun secretFillTextIsNamedForRedaction() {
        val h = Harness()
        val token = h.readLogin()
        val secret = h.executor.secretArgumentKeys(
            ActionRequest("browse_fill", mapOf("field" to "f1", "text" to "s3cr3t", "token" to token)))
        assertEquals(setOf("text"), secret)
        val plain = h.executor.secretArgumentKeys(
            ActionRequest("browse_fill", mapOf("field" to "f0", "text" to "user", "token" to token)))
        assertTrue("non-secret fills journal verbatim", plain.isEmpty())
        val unknown = h.executor.secretArgumentKeys(
            ActionRequest("browse_fill", mapOf("field" to "f9", "text" to "x", "token" to token)))
        assertEquals("unknown fields redact conservatively", setOf("text"), unknown)
        val other = h.executor.secretArgumentKeys(ActionRequest("read_battery"))
        assertTrue(other.isEmpty())
    }

    // -- Fail-closed refresh (Jerry's review, build 1189) --------

    @Test fun failedRefreshRefusesClickWithZeroNavigation() {
        val h = Harness()
        val token = h.readLogin()
        // Missing activity / re-extract timeout: the backend reports failure.
        h.bridge.refreshFails = true
        val result = h.executor.execute(MobileAction.BrowseClick("l0", token))
        assertFalse("a click without a fresh snapshot must fail closed", result.succeeded)
        assertTrue("must report the failed re-read, got: ${result.message}",
            result.message.contains("couldn't re-read"))
        assertTrue("zero navigation may reach the backend", h.bridge.clickedLinks.isEmpty())
    }

    @Test fun failedRefreshRefusesFillWithZeroFill() {
        val h = Harness()
        val token = h.readLogin()
        h.bridge.refreshFails = true
        val result = h.executor.execute(MobileAction.BrowseFill("f0", "user@example.com", token))
        assertFalse("a fill without a fresh snapshot must fail closed", result.succeeded)
        assertTrue("must report the failed re-read, got: ${result.message}",
            result.message.contains("couldn't re-read"))
        assertTrue("zero fill may reach the backend", h.bridge.filledFields.isEmpty())
    }

    @Test fun failedRefreshRefusesLiveApprovalWithZeroSubmission() {
        // The dangerous case: the approval is live and matches the cached
        // document, but the refresh cannot re-extract — the approval must
        // NOT dispatch against a document that can no longer be verified.
        val h = Harness()
        val token = h.readLogin()
        assertTrue(h.executor.execute(
            MobileAction.BrowseFill("f0", "user@example.com", token)).succeeded)
        h.session.admitSubmit(h.session.proposeSubmit(token)!!)
        h.bridge.refreshFails = true
        val result = h.executor.execute(MobileAction.BrowseSubmit(token))
        assertFalse("a live approval must not submit without a fresh snapshot",
            result.succeeded)
        assertTrue("must report the failed re-read, got: ${result.message}",
            result.message.contains("couldn't re-read"))
        assertEquals("zero submission may reach the backend", 0, h.bridge.submittedForms)
    }

    @Test fun failedRefreshRefusesLoginHandoff() {
        val h = Harness()
        val token = h.readLogin()
        h.bridge.refreshFails = true
        val result = h.executor.execute(MobileAction.BrowseLogin(token))
        assertFalse("a login handoff without a fresh snapshot must fail closed",
            result.succeeded)
        assertTrue("must report the failed re-read, got: ${result.message}",
            result.message.contains("couldn't re-read"))
    }

    @Test fun successfulRefreshStillDispatches() {
        // Sanity: fail-closed must not break the happy path.
        val h = Harness()
        val token = h.readLogin()
        val result = h.executor.execute(MobileAction.BrowseClick("l0", token))
        assertTrue("happy path must still dispatch, got: ${result.message}", result.succeeded)
        assertEquals(listOf("l0"), h.bridge.clickedLinks)
    }

    @Test fun formFieldsReplacedBetweenApprovalAndDispatchPerformsZeroSubmission() {
        // The document is replaced between approval and dispatch: the form
        // keeps its shape but gains an extra field, so the approved target
        // no longer matches. The refresh sees the new DOM, the token
        // rotates, and the stale approval dies with zero submission.
        val h = Harness()
        val token = h.readLogin()
        assertTrue(h.executor.execute(
            MobileAction.BrowseFill("f0", "user@example.com", token)).succeeded)
        h.session.admitSubmit(h.session.proposeSubmit(token)!!)
        h.bridge.refreshedSnapshot = loginSnapshot(
            fingerprint = "fp-fields-changed",
            forms = listOf(
                BrowserForm(
                    id = "form0",
                    actionUrl = "https://example.com/session",
                    method = "POST",
                    fields = listOf(
                        BrowserField("f0", "Email", FieldKind.EMAIL),
                        BrowserField("f1", "Password", FieldKind.PASSWORD, secret = true),
                        BrowserField("f2", "One-time code", FieldKind.TEXT)
                    ),
                    submitLabel = "Sign in"
                )
            )
        )
        val result = h.executor.execute(MobileAction.BrowseSubmit(token))
        assertFalse("a replaced form must invalidate the approval", result.succeeded)
        assertTrue("stale token must be reported, got: ${result.message}",
            result.message.contains("page changed"))
        assertEquals("zero submission may reach the backend", 0, h.bridge.submittedForms)
    }
}
