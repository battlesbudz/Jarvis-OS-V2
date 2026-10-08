package com.battlesbudz.jarvis.v2.actions

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * M4 browser tasks: the WebView backend for the internal browser.
 *
 * [BrowserActivity] hosts the visible WebView (manual takeover happens
 * here: any touch pauses automation via [BrowserBackend]). [WebViewBrowserBridge]
 * implements [BrowserBridge] against the latest snapshot the activity posts.
 * Snapshots are cached and synchronous; the [BrowserSession] token rotates on
 * every reconciled navigation, so automation can never act on a stale page.
 */
object BrowserBackend {
    @Volatile var latestSnapshot: BrowserPageSnapshot? = null
    /**
     * Bumped on every successful snapshot extraction; lets the bridge wait
     * for a fresh re-extract instead of trusting a cached snapshot.
     */
    @Volatile var snapshotVersion: Long = 0
    @Volatile var takeoverListener: (() -> Unit)? = null
    fun notifyUserTakeover() {
        takeoverListener?.invoke()
    }
}

/** Intent extra carrying the URL to load. */
const val BROWSER_EXTRA_URL = "com.battlesbudz.jarvis.v2.actions.BROWSER_URL"

class BrowserActivity : Activity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Small settle delay so late DOM writes land before extraction.
                view.postDelayed({ extractSnapshot(view) }, 400)
            }
        }
        webView.setOnTouchListener { _, _ ->
            BrowserBackend.notifyUserTakeover()
            false
        }
        intent.getStringExtra(BROWSER_EXTRA_URL)?.let { webView.loadUrl(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(BROWSER_EXTRA_URL)?.let { webView.loadUrl(it) }
    }

    override fun onStart() {
        super.onStart()
        WebViewBrowserBridge.BrowserActivityHolder.current = this
    }

    override fun onStop() {
        if (WebViewBrowserBridge.BrowserActivityHolder.current === this) {
            WebViewBrowserBridge.BrowserActivityHolder.current = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /** Automation seam for the bridge; the activity stays the WebView owner. */
    fun webViewForAutomation(): WebView = webView

    /**
     * Re-extract the snapshot on demand (automation refresh). Must run on
     * the UI thread; bumps [BrowserBackend.snapshotVersion] on success.
     * Never throws: a dying activity simply yields no fresh snapshot.
     */
    fun requestSnapshot() {
        runCatching { extractSnapshot(webView) }
    }

    private fun extractSnapshot(view: WebView) {
        view.evaluateJavascript(SNAPSHOT_JS, ValueCallback { json ->
            runCatching {
                val root = JSONObject(json)
                val links = mutableListOf<BrowserLink>()
                val linkArray = root.optJSONArray("links") ?: JSONArray()
                for (i in 0 until linkArray.length()) {
                    val o = linkArray.getJSONObject(i)
                    links.add(BrowserLink(o.getString("id"), o.optString("label"), o.getString("url")))
                }
                val forms = mutableListOf<BrowserForm>()
                val formArray = root.optJSONArray("forms") ?: JSONArray()
                for (i in 0 until formArray.length()) {
                    val o = formArray.getJSONObject(i)
                    val fields = mutableListOf<BrowserField>()
                    val fieldArray = o.optJSONArray("fields") ?: JSONArray()
                    for (j in 0 until fieldArray.length()) {
                        val f = fieldArray.getJSONObject(j)
                        fields.add(
                            BrowserField(
                                id = f.getString("id"),
                                label = f.optString("label"),
                                kind = fieldKindOf(f.optString("kind")),
                                secret = f.optBoolean("secret")
                            )
                        )
                    }
                    forms.add(
                        BrowserForm(
                            id = o.getString("id"),
                            actionUrl = o.optString("action").takeIf { it.isNotEmpty() },
                            method = o.optString("method", "GET"),
                            fields = fields,
                            submitLabel = o.optString("submit").takeIf { it.isNotEmpty() }
                        )
                    )
                }
                BrowserBackend.latestSnapshot = BrowserPageSnapshot(
                    url = root.getString("url"),
                    title = root.optString("title"),
                    textExcerpt = root.optString("text"),
                    links = links,
                    forms = forms,
                    contentFingerprint = root.optString("fingerprint")
                )
                BrowserBackend.snapshotVersion++
            }
        })
    }

    companion object {
        /**
         * Extracts a JSON snapshot and tags actionable elements with stable
         * data-jarvis-id attributes (l0.., f0..) for later click/fill calls.
         */
        const val SNAPSHOT_JS = """(function(){
  function clean(s){ return (s||'').trim().replace(/\s+/g,' ').slice(0,4000); }
  var links=[];
  document.querySelectorAll('a[href],button,[role="button"]').forEach(function(el,i){
    if(i>=40) return;
    var id='l'+i;
    el.setAttribute('data-jarvis-id',id);
    var label=clean(el.innerText||el.getAttribute('aria-label')||el.value||'');
    var url=el.href||'';
    links.push({id:id,label:label.slice(0,80),url:url});
  });
  var forms=[];
  var fieldIndex=0;
  document.querySelectorAll('form').forEach(function(f,fi){
    // Jerry's review (build 1196): stamp the exact form id on the actual
    // FORM element before the snapshot fingerprint is computed. The submit
    // dispatch finds the approved form by this stamped id; without it the
    // real submission JavaScript could never locate the approved form.
    var formId='form'+fi;
    f.setAttribute('data-jarvis-id',formId);
    var fields=[];
    f.querySelectorAll('input,textarea,select').forEach(function(el){
      var t=(el.type||'text').toLowerCase();
      if(t==='hidden'||t==='submit'||t==='button') return;
      var id='f'+(fieldIndex++);
      el.setAttribute('data-jarvis-id',id);
      var kind=t;
      if(el.tagName==='TEXTAREA') kind='textarea';
      if(el.tagName==='SELECT') kind='select';
      fields.push({id:id,
        label:clean(el.getAttribute('aria-label')||el.name||el.placeholder||kind),
        kind:kind, secret:(t==='password')});
    });
    var submitEl=f.querySelector('input[type="submit"],button[type="submit"],button');
    forms.push({id:formId, action:f.action||'', method:(f.method||'get').toUpperCase(),
      fields:fields, submit:submitEl?clean(submitEl.innerText||submitEl.value||'Submit'):''});
  });
  var html=document.documentElement?document.documentElement.outerHTML:'';
  var fp=0;
  for(var k=0;k<html.length;k++){ fp=((fp*31)+html.charCodeAt(k))|0; }
  // Return the object itself: evaluateJavascript delivers it as JSON text,
  // which JSONObject parses directly (no string-unwrapping needed).
  return {url:location.href, title:document.title||'',
    text:clean(document.body?document.body.innerText:''), links:links, forms:forms,
    fingerprint:String(fp)};
})()"""

        private fun fieldKindOf(kind: String): FieldKind = when (kind.lowercase()) {
            "text" -> FieldKind.TEXT
            "password" -> FieldKind.PASSWORD
            "email" -> FieldKind.EMAIL
            "number" -> FieldKind.NUMBER
            "checkbox" -> FieldKind.CHECKBOX
            "select" -> FieldKind.SELECT
            "textarea" -> FieldKind.TEXTAREA
            "submit" -> FieldKind.SUBMIT
            else -> FieldKind.UNKNOWN
        }
    }
}

/**
 * [BrowserBridge] over the [BrowserActivity] WebView. Loads hand the URL to
 * the activity (singleTask: a new URL reuses the visible browser);
 * automation reads the cached snapshot. JavaScript is confined to page
 * introspection and the field/link actions below; no native bridge is
 * exposed to page content.
 */
class WebViewBrowserBridge(
    private val context: Context,
    onUserTakeover: () -> Unit = {}
) : BrowserBridge {
    init {
        BrowserBackend.takeoverListener = onUserTakeover
    }

    private var lastRequestedUrl: String? = null

    companion object {
        /**
         * The in-page submit dispatch, shared with the JVM regression test
         * ([BrowserSubmitJsTest]): finds the approved form by the id the
         * snapshot extractor stamped on the FORM element, recomputes the
         * document fingerprint with the same algorithm as the extractor, and
         * clicks the submit control — all atomically in one evaluation. A
         * form or document replaced since the approval refuses with zero
         * submission. [safeFormId] must already be sanitized (letters,
         * digits, '-' and '_' only); [quotedFingerprint] must be a
         * [JSONObject]-quoted string literal.
         */
        internal fun submitDispatchJs(safeFormId: String, quotedFingerprint: String): String =
            """(function(){
  function fpOf(){var html=document.documentElement?document.documentElement.outerHTML:'';var fp=0;for(var k=0;k<html.length;k++){fp=((fp*31)+html.charCodeAt(k))|0;}return String(fp);}
  var f=document.querySelector('[data-jarvis-id="${safeFormId}"]');
  if(!f||f.tagName!=='FORM') return false;
  if(fpOf()!==${quotedFingerprint}) return false;
  var btn=f.querySelector('input[type="submit"],button[type="submit"],button');
  if(btn) btn.click(); else f.submit();
  return true;
})()"""

        /** Bounded wait for a fill's in-page verification to report back. */
        private const val FILL_CONFIRM_TIMEOUT_MS = 5_000L
        /** Bounded wait for the submit click's JS to report back. */
        private const val SUBMIT_CLICK_TIMEOUT_MS = 5_000L
        /** Bounded settle window for a submit's observable effect. */
        private const val SUBMIT_CONFIRM_TIMEOUT_MS = 8_000L
        /** Bounded wait for the user's password-manager fill to land. */
        private const val CREDENTIAL_FILL_TIMEOUT_MS = 20_000L
        /** Bounded wait for an on-demand snapshot re-extract. */
        private const val REFRESH_TIMEOUT_MS = 2_500L
    }

    override fun isAvailable(): Boolean = true

    override fun open(url: String): Boolean {
        lastRequestedUrl = url
        val intent = Intent(context, BrowserActivity::class.java)
            .putExtra(BROWSER_EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }

    override fun snapshot(): BrowserPageSnapshot? = BrowserBackend.latestSnapshot

    override fun snapshotFingerprint(): String? = BrowserBackend.latestSnapshot?.contentFingerprint

    /**
     * Re-extract the live DOM now (bounded wait): mutating dispatches must
     * see the page as it is, not as it was at the last navigation.
     *
     * Fail-closed: a missing activity or a re-extract that never lands
     * within the bounded wait returns null — never the cached snapshot.
     * Returning the cache as "fresh" would let a stale approval dispatch
     * against a replaced document, so callers must refuse the dispatch
     * when this returns null.
     */
    override fun refreshSnapshot(): BrowserPageSnapshot? {
        val activity = currentBrowserActivity() ?: return null
        val before = BrowserBackend.snapshotVersion
        activity.runOnUiThread { activity.requestSnapshot() }
        val deadline = SystemClock.uptimeMillis() + REFRESH_TIMEOUT_MS
        while (BrowserBackend.snapshotVersion == before && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(50)
        }
        // Only a version bump proves the DOM was actually re-extracted; on
        // timeout the cache may describe a page that no longer exists.
        if (BrowserBackend.snapshotVersion == before) return null
        return BrowserBackend.latestSnapshot
    }

    override fun currentUrl(): String? =
        BrowserBackend.latestSnapshot?.url ?: lastRequestedUrl

    override fun clickLink(id: String): Boolean {
        val activity = currentBrowserActivity() ?: return false
        activity.runOnUiThread {
            activity.webViewRef()?.evaluateJavascript(
                "document.querySelector('[data-jarvis-id=\"${id}\"]')?.click()",
                null
            )
        }
        return true
    }

    override fun goBack(): Boolean {
        val activity = currentBrowserActivity() ?: return false
        activity.runOnUiThread {
            activity.webViewRef()?.let { if (it.canGoBack()) it.goBack() }
        }
        return true
    }

    override fun goForward(): Boolean {
        val activity = currentBrowserActivity() ?: return false
        activity.runOnUiThread {
            activity.webViewRef()?.let { if (it.canGoForward()) it.goForward() }
        }
        return true
    }

    override fun fillField(id: String, text: String): Boolean {
        val activity = currentBrowserActivity() ?: return false
        // Defense in depth: ids are validated upstream, but never let one
        // break out of the JS string.
        val safeId = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (safeId.isEmpty() || safeId != id) return false
        val quoted = JSONObject.quote(text)
        // The equality check runs inside the page: only a boolean crosses
        // back, so a secret value is confirmed set without ever being read
        // out of the field.
        return evalBoolean(
            activity,
            """(function(){
  var el=document.querySelector('[data-jarvis-id="${safeId}"]');
  if(!el) return false;
  var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
  var desc=Object.getOwnPropertyDescriptor(proto,'value');
  if(desc&&desc.set) desc.set.call(el,${quoted}); else el.value=${quoted};
  el.dispatchEvent(new Event('input',{bubbles:true}));
  el.dispatchEvent(new Event('change',{bubbles:true}));
  return el.value===${quoted};
})()""",
            FILL_CONFIRM_TIMEOUT_MS
        ) == true
    }

    override fun submitForm(target: BrowserSubmitTarget): Boolean {
        val activity = currentBrowserActivity() ?: return false
        // Defense in depth: the form id is validated upstream, but never let
        // one break out of the JS string.
        val safeFormId = target.formId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (safeFormId.isEmpty() || safeFormId != target.formId) return false
        val quotedFp = JSONObject.quote(target.contentFingerprint)
        val beforeUrl = AtomicReference<String?>(null)
        val beforeFingerprint = BrowserBackend.latestSnapshot?.contentFingerprint
        // The approved target is validated inside the same page evaluation
        // that dispatches the click: the form is found by its stable id
        // (never "the first form"), and the document fingerprint is
        // recomputed in-page with the same algorithm as the snapshot
        // extractor. A form or document replaced between the refresh and
        // this dispatch refuses — the click can never land on a document
        // the approval did not see.
        val clicked = evalBoolean(
            activity,
            submitDispatchJs(safeFormId, quotedFp),
            SUBMIT_CLICK_TIMEOUT_MS,
            beforeEval = { view -> beforeUrl.set(view.url) }
        )
        if (clicked != true) return false
        // The click landed. Confirm an actual effect — navigation or DOM
        // change — within a bounded settle window. A queued click with no
        // observable effect is reported as a failure, never a success.
        val deadline = SystemClock.uptimeMillis() + SUBMIT_CONFIRM_TIMEOUT_MS
        var lastRefresh = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val snap = BrowserBackend.latestSnapshot
            if (snap != null &&
                (snap.url != beforeUrl.get() || snap.contentFingerprint != beforeFingerprint)
            ) return true
            // Nudge a re-extract so XHR-driven DOM changes surface; page-load
            // navigations surface on their own via onPageFinished.
            val now = SystemClock.uptimeMillis()
            if (now - lastRefresh > 1500) {
                lastRefresh = now
                activity.runOnUiThread { activity.requestSnapshot() }
            }
            Thread.sleep(250)
        }
        return false
    }

    override fun requestCredentialFill(host: String): CredentialFillOutcome {
        // The platform autofill path: focus the password field so the
        // password manager offers the fill, then verify a field actually
        // became non-empty. Only booleans cross back — the secret value
        // itself is never read out of the field. A null activity means the
        // backend is gone.
        val activity = currentBrowserActivity() ?: return CredentialFillOutcome.UNAVAILABLE
        val focused = evalBoolean(
            activity,
            """(function(){
  var el=document.querySelector('input[type="password"]');
  if(!el) return false;
  el.focus();
  return true;
})()""",
            FILL_CONFIRM_TIMEOUT_MS
        )
        if (focused != true) return CredentialFillOutcome.NO_CREDENTIALS
        // The user answers the password-manager prompt on their own time;
        // wait bounded for the fill to land.
        val deadline = SystemClock.uptimeMillis() + CREDENTIAL_FILL_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val filled = evalBoolean(
                activity,
                """(function(){
  var el=document.querySelector('input[type="password"]');
  return !!(el&&el.value&&el.value.length>0);
})()""",
                FILL_CONFIRM_TIMEOUT_MS
            )
            if (filled == true) return CredentialFillOutcome.FILLED
            Thread.sleep(500)
        }
        // The backend cannot tell "no login saved" from "the user dismissed
        // the prompt" — both leave the field empty.
        return CredentialFillOutcome.NO_CREDENTIALS
    }

    /**
     * Evaluate [js] (expected to return a JS boolean) on the browser thread
     * and synchronously return it, or null on timeout. [beforeEval] runs on
     * the UI thread just before the evaluation — used to capture pre-state.
     */
    private fun evalBoolean(
        activity: BrowserActivity,
        js: String,
        timeoutMs: Long,
        beforeEval: (WebView) -> Unit = {}
    ): Boolean? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Boolean?>(null)
        activity.runOnUiThread {
            val view = activity.webViewRef()
            if (view == null) {
                latch.countDown()
                return@runOnUiThread
            }
            beforeEval(view)
            view.evaluateJavascript(js, ValueCallback { value ->
                result.set(value?.trim() == "true")
                latch.countDown()
            })
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result.get()
    }

    /**
     * The live activity, if the browser is on screen. Registered by the
     * activity's own lifecycle callbacks above; null when the browser UI
     * is not visible, in which case actions report honestly.
     */
    private fun currentBrowserActivity(): BrowserActivity? = BrowserActivityHolder.current

    object BrowserActivityHolder {
        @Volatile var current: BrowserActivity? = null
    }

    private fun BrowserActivity.webViewRef(): WebView? = runCatching { webViewForAutomation() }.getOrNull()
}
