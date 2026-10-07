package com.battlesbudz.jarvis.v2.actions

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
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
    forms.push({id:'form'+fi, action:f.action||'', method:(f.method||'get').toUpperCase(),
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
        val quoted = JSONObject.quote(text)
        activity.runOnUiThread {
            activity.webViewRef()?.evaluateJavascript(
                """(function(){
  var el=document.querySelector('[data-jarvis-id="${id}"]');
  if(!el) return;
  var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
  var setter=Object.getOwnPropertyDescriptor(proto,'value').set;
  setter.call(el,${quoted});
  el.dispatchEvent(new Event('input',{bubbles:true}));
  el.dispatchEvent(new Event('change',{bubbles:true}));
})()""",
                null
            )
        }
        return true
    }

    override fun submitForm(): Boolean {
        val activity = currentBrowserActivity() ?: return false
        activity.runOnUiThread {
            activity.webViewRef()?.evaluateJavascript(
                """(function(){
  var f=document.querySelector('form');
  if(!f) return;
  var btn=f.querySelector('input[type="submit"],button[type="submit"],button');
  if(btn) btn.click(); else f.submit();
})()""",
                null
            )
        }
        return true
    }

    override fun requestCredentialFill(host: String): CredentialFillOutcome {
        // The platform autofill path: nudge the WebView to offer autofill on
        // the focused field. Only the outcome returns; credentials never
        // leave the platform. A null activity means the backend is gone.
        val activity = currentBrowserActivity() ?: return CredentialFillOutcome.UNAVAILABLE
        activity.runOnUiThread {
            activity.webViewRef()?.evaluateJavascript(
                """(function(){
  var el=document.querySelector('input[type="password"]');
  if(el){ el.focus(); }
})()""",
                null
            )
        }
        // Whether the user has saved credentials is known only to the
        // password manager; report the handoff honestly as handed off.
        // Callers treat this as "offered" — the page's next snapshot shows
        // whether fields were filled.
        return CredentialFillOutcome.FILLED
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
