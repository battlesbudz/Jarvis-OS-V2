package com.battlesbudz.jarvis.v2.actions

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

/**
 * Credential-fill completion must be truthful: FILLED is reported only for
 * an actual empty-to-filled transition on the exact field the request
 * focused, with the approved host and the live document bound through the
 * whole flow. This runs the ACTUAL credential JavaScript
 * ([WebViewBrowserBridge.credentialFocusJs] /
 * [WebViewBrowserBridge.credentialEmptyJs] /
 * [WebViewBrowserBridge.credentialFilledJs]) together with the production
 * flow ([WebViewBrowserBridge.requestCredentialFill]) inside a minimal DOM
 * shim, with a scripted live host / document fingerprint / page state.
 *
 * Every test proves the same invariant: anything short of a real,
 * attributable platform fill is NO_CREDENTIALS — never FILLED. The browser
 * gate stays closed; this tests the completion contract, not the catalog.
 */
@RunWith(RobolectricTestRunner::class)
class BrowserCredentialFillJsTest {

    private lateinit var cx: Context
    private lateinit var scope: ScriptableObject
    private lateinit var bridge: WebViewBrowserBridge
    private var liveHost: String? = "example.com"
    private var liveFingerprint: String? = "fp-1"
    private var evalCalls = 0

    @Before
    fun setUp() {
        cx = Context.enter()
        cx.languageVersion = Context.VERSION_ES6
        // Interpreted mode: Rhino 1.8.0's optimizer hits a
        // jdk.dynalink MissingResourceException under this JDK/Robolectric
        // sandbox (ExceptionInInitializerError). Interpretation is plenty
        // for a small DOM shim.
        cx.optimizationLevel = -1
        scope = cx.initStandardObjects()
        cx.evaluateString(scope, DOM_SHIM, "dom-shim", 1, null)
        bridge = WebViewBrowserBridge(RuntimeEnvironment.getApplication())
        liveHost = "example.com"
        liveFingerprint = "fp-1"
        evalCalls = 0
    }

    @After
    fun tearDown() {
        Context.exit()
    }

    private fun evalJsBoolean(js: String): Boolean? {
        evalCalls++
        return cx.evaluateString(scope, js, "cred", 1, null) as? Boolean
    }

    private fun fill(host: String = "example.com", timeoutMs: Long = 1_500L): CredentialFillOutcome =
        bridge.requestCredentialFill(
            host = host,
            liveHostMatches = { requested -> liveHost == requested },
            documentFingerprintNow = { liveFingerprint },
            evalBoolean = ::evalJsBoolean,
            waitTimeoutMs = timeoutMs
        )

    // -- JavaScript contract --------

    @Test fun focusJsStampsExactlyOneTarget() {
        assertEquals("nothing filled before focus", false,
            evalJsBoolean(WebViewBrowserBridge.credentialFilledJs()))
        assertEquals(true, evalJsBoolean(WebViewBrowserBridge.credentialFocusJs()))
        assertEquals("focus must land on the password field", true,
            evalJsBoolean("__pass.focused"))
        assertEquals("the stamp lands on the focused element", true,
            evalJsBoolean("__pass.getAttribute('data-jarvis-cred-target') === '1'"))
    }

    @Test fun filledJsRequiresStampAndValue() {
        // A value on an unstamped field is not attributable to any request.
        cx.evaluateString(scope, "__pass.value = 'preset';", "preset", 1, null)
        assertEquals("unstamped value is not a fill", false,
            evalJsBoolean(WebViewBrowserBridge.credentialFilledJs()))
        // Stamping alone is not a fill either.
        evalJsBoolean(WebViewBrowserBridge.credentialFocusJs())
        assertEquals(true, evalJsBoolean(WebViewBrowserBridge.credentialFilledJs()))
        assertEquals(false, evalJsBoolean(WebViewBrowserBridge.credentialEmptyJs()))
    }

    // -- Flow: honest completion --------

    @Test fun mismatchedHostRefusesWithoutTouchingThePage() {
        // The live page is a different host than the approved one: refuse
        // before any in-page evaluation runs.
        liveHost = "other.example"
        val outcome = fill(host = "example.com")
        assertEquals(CredentialFillOutcome.NO_CREDENTIALS, outcome)
        assertEquals("a host mismatch must not touch the page", 0, evalCalls)
    }

    @Test fun preexistingPasswordValueNeverReportsFilled() {
        // The field already held a value before the request: there is no
        // empty-to-filled transition, so FILLED must never be reported —
        // the pre-fix code returned FILLED for exactly this state.
        cx.evaluateString(scope, "__pass.value = 'already-there';", "preset", 1, null)
        val outcome = fill()
        assertEquals(CredentialFillOutcome.NO_CREDENTIALS, outcome)
        assertTrue("the flow must read the pre-state", evalCalls >= 2)
    }

    @Test fun dismissedPromptReportsNoCredentials() {
        // The password manager never fills (no saved login, or the user
        // dismissed the prompt): the field stays empty through the
        // deadline, so the outcome stays neutral — never FILLED.
        val outcome = fill(timeoutMs = 1_200L)
        assertEquals(CredentialFillOutcome.NO_CREDENTIALS, outcome)
    }

    @Test fun actualEmptyToFilledTransitionReportsFilled() {
        // Happy path through the real JavaScript: the password manager
        // lands the fill after the pre-state read, and the stamped target
        // transitions empty -> filled.
        val outcome = bridge.requestCredentialFill(
            host = "example.com",
            liveHostMatches = { liveHost == it },
            documentFingerprintNow = { liveFingerprint },
            evalBoolean = { js ->
                val r = evalJsBoolean(js)
                if (js == WebViewBrowserBridge.credentialEmptyJs() && r == true) {
                    cx.evaluateString(scope, "__pass.value = 'manager-filled';", "fill", 1, null)
                }
                r
            },
            waitTimeoutMs = 2_000L
        )
        assertEquals(CredentialFillOutcome.FILLED, outcome)
    }

    @Test fun navigationMidFlowAbortsTheFill() {
        // The user navigates away after the pre-state read: the host
        // re-check flips mid-flow and the request aborts instead of
        // attributing a fill to the approved host.
        var checks = 0
        val outcome = bridge.requestCredentialFill(
            host = "example.com",
            liveHostMatches = { requested ->
                checks++
                if (checks > 2) false else liveHost == requested
            },
            documentFingerprintNow = { liveFingerprint },
            evalBoolean = ::evalJsBoolean,
            waitTimeoutMs = 1_500L
        )
        assertEquals(CredentialFillOutcome.NO_CREDENTIALS, outcome)
        assertTrue("the flow must re-check the host mid-flow", checks > 2)
    }

    @Test fun documentReplacementMidFlowAbortsTheFill() {
        // The document is replaced mid-flow (same host, new fingerprint):
        // the document binding fails and the request aborts with zero
        // attribution.
        var fpReads = 0
        val outcome = bridge.requestCredentialFill(
            host = "example.com",
            liveHostMatches = { liveHost == it },
            documentFingerprintNow = {
                fpReads++
                if (fpReads > 2) "fp-replaced" else liveFingerprint
            },
            evalBoolean = ::evalJsBoolean,
            waitTimeoutMs = 1_500L
        )
        assertEquals(CredentialFillOutcome.NO_CREDENTIALS, outcome)
        assertTrue("the flow must re-check the document mid-flow", fpReads > 2)
    }

    @Test fun replacedFieldTargetNeverAttributesAFill() {
        // Mid-flow the DOM swaps the focused field for a lookalike that
        // already carries a value — but the swap drops the focus stamp.
        // The stamped completion check must not attribute the lookalike's
        // value to this request.
        val outcome = bridge.requestCredentialFill(
            host = "example.com",
            liveHostMatches = { liveHost == it },
            documentFingerprintNow = { liveFingerprint },
            evalBoolean = { js ->
                val r = evalJsBoolean(js)
                if (js == WebViewBrowserBridge.credentialEmptyJs() && r == true) {
                    cx.evaluateString(scope, "__replaceFieldWithValuedLookalike();", "swap", 1, null)
                }
                r
            },
            waitTimeoutMs = 1_500L
        )
        assertEquals("a value on an unstamped replacement field is not this request's fill",
            CredentialFillOutcome.NO_CREDENTIALS, outcome)
    }

    companion object {
        /**
         * Minimal DOM: only what the credential JavaScript uses
         * (querySelector with tag + [attr="value"] selectors, focus,
         * set/getAttribute, value).
         */
        private val DOM_SHIM = """
function __matches(el, sel) {
  var tag = sel.split('[')[0];
  if (el.tagName.toLowerCase() !== tag) return false;
  var re = /\[([\w-]+)(?:="([^"]*)")?\]/g, m;
  while ((m = re.exec(sel)) !== null) {
    var v = el.getAttribute(m[1]);
    if (v === null || v === undefined) return false;
    if (m[2] !== undefined && v !== m[2]) return false;
  }
  return true;
}
function __mkInput(attrs) {
  var el = {
    tagName: 'INPUT',
    _attrs: {},
    value: '',
    focused: false,
    getAttribute: function(n) { return (n in this._attrs) ? this._attrs[n] : null; },
    setAttribute: function(n, v) { this._attrs[n] = String(v); },
    focus: function() { this.focused = true; }
  };
  for (var k in attrs) { el._attrs[k] = String(attrs[k]); el[k] = String(attrs[k]); }
  return el;
}
var __pass = __mkInput({type: 'password', name: 'password'});
var __page = [__pass];
var document = {
  querySelector: function(sel) {
    for (var i = 0; i < __page.length; i++) {
      if (__matches(__page[i], sel)) return __page[i];
    }
    return null;
  }
};
function __replaceFieldWithValuedLookalike() {
  // The DOM swaps the focused field for a lookalike that already carries
  // a value — the swap drops the focus stamp.
  var evil = __mkInput({type: 'password', name: 'password'});
  evil.value = 'attacker-set';
  __page = [evil];
}
"""
    }
}
