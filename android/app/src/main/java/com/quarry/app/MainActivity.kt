package com.quarry.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin WebView shell for Quarry.
 * - Loads www/index.html from assets
 * - Binds LlamaService for on-device llama-server
 * - Exposes window.QuarryNative / window.KojotBridge
 * - deepExtract(): secondary off-screen WebView for post-JS DOM extraction
 *   (strengthens keyless search/extract the same way deep_fetch's CDP path does)
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var extractWeb: WebView? = null
    private var llama: LlamaService? = null
    private var bound = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val extractLock = Any()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            llama = (service as LlamaService.LocalBinder).getService()
            bound = true
            injectSlot()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            llama = null
            bound = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        configureWebView(web)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            override fun onPageFinished(view: WebView?, url: String?) {
                injectSlot()
            }
        }

        web.addJavascriptInterface(Bridge(), "QuarryNative")
        web.addJavascriptInterface(Bridge(), "KojotBridge")

        // Off-screen WebView used only for dynamic (post-JS) extraction
        extractWeb = WebView(this).also { configureWebView(it) }

        web.loadUrl("file:///android_asset/www/index.html")

        val i = Intent(this, LlamaService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 QuarryResearch/2.0"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            s.safeBrowsingEnabled = false
        }
        // Block file / content schemes for the extract WebView
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return true
                val scheme = u.scheme?.lowercase() ?: return true
                return scheme != "http" && scheme != "https"
            }
        }
    }

    private fun injectSlot() {
        val slot = llama?.activeSlot ?: "chat"
        val ready = llama?.ready == true
        val js = """
            (function(){
              window.__quarryNative = true;
              window.__quarryDeepExtract = true;
              window.__quarrySlot = ${JSONObject.quote(slot)};
              window.__quarryReady = ${if (ready) "true" else "false"};
              try {
                var el = document.getElementById('saveChip');
                if (el && $ready) el.title = 'On-device llama slot: ' + ${JSONObject.quote(slot)};
              } catch (e) {}
            })();
        """.trimIndent()
        web.post { web.evaluateJavascript(js, null) }
    }

    /**
     * SSRF-style public-host check (mirrors the JS _assertPublicUrl logic).
     * Blocks localhost, private, link-local, CGNAT, and non-http(s) schemes.
     */
    private fun assertPublicUrl(url: String): Uri {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("Only http/https allowed")
        }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("Missing host")
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host == "0.0.0.0" || host == "[::1]" || host == "::1"
        ) {
            throw IllegalArgumentException("Blocked host: $host")
        }
        try {
            val addrs = InetAddress.getAllByName(host)
            for (a in addrs) {
                if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
                    a.isSiteLocalAddress || a.isMulticastAddress
                ) {
                    throw IllegalArgumentException("Private/local address: $host")
                }
                // CGNAT 100.64.0.0/10
                val bytes = a.address
                if (bytes.size == 4) {
                    val b0 = bytes[0].toInt() and 0xff
                    val b1 = bytes[1].toInt() and 0xff
                    if (b0 == 100 && b1 in 64..127) {
                        throw IllegalArgumentException("CGNAT address: $host")
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            // DNS failure — still allow (same as many clients); the load will fail later
        }
        return uri
    }

    /**
     * Navigate the off-screen WebView, wait for load + settle, return cleaned text.
     * Called from the JS bridge (background binder thread) — posts work to main thread.
     */
    private fun runDeepExtract(url: String, timeoutMs: Long): JSONObject {
        synchronized(extractLock) {
            val result = AtomicReference<JSONObject>()
            val latch = CountDownLatch(1)
            val ew = extractWeb ?: return JSONObject()
                .put("ok", false)
                .put("error", "extract WebView not ready")

            mainHandler.post {
                try {
                    assertPublicUrl(url)
                    var finished = false
                    ew.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url ?: return true
                            val scheme = u.scheme?.lowercase() ?: return true
                            return scheme != "http" && scheme != "https"
                        }
                        override fun onPageFinished(view: WebView?, loaded: String?) {
                            if (finished) return
                            finished = true
                            // Short settle so late JS can paint
                            mainHandler.postDelayed({
                                val extractJs = """
                                    (function(){
                                      try {
                                        var doc = document;
                                        var scripts = doc.querySelectorAll('script,style,noscript,svg,iframe');
                                        for (var i=0;i<scripts.length;i++) scripts[i].remove();
                                        var body = doc.body || doc.documentElement;
                                        var text = (body && body.innerText) ? body.innerText : (body ? body.textContent : '');
                                        text = (text || '').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
                                        return JSON.stringify({
                                          ok: true,
                                          url: location.href || '',
                                          title: doc.title || '',
                                          text: text.slice(0, 220000),
                                          chars: Math.min(text.length, 220000)
                                        });
                                      } catch (e) {
                                        return JSON.stringify({ ok: false, error: String(e) });
                                      }
                                    })();
                                """.trimIndent()
                                ew.evaluateJavascript(extractJs) { raw ->
                                    try {
                                        val cleaned = raw?.trim()?.removeSurrounding("\"")
                                            ?.replace("\\\"", "\"")
                                            ?.replace("\\n", "\n")
                                            ?.replace("\\\\", "\\")
                                        val parsed = if (!cleaned.isNullOrBlank()) {
                                            try { JSONObject(cleaned) } catch (_: Exception) {
                                                // evaluateJavascript string-escapes; try a second unescape pass
                                                val unquoted = raw.trim().let {
                                                    if (it.startsWith("\"") && it.endsWith("\"")) {
                                                        JSONObject("{\"v\":$it}").getString("v")
                                                    } else it
                                                }
                                                JSONObject(unquoted)
                                            }
                                        } else {
                                            JSONObject().put("ok", false).put("error", "empty extract")
                                        }
                                        result.set(parsed)
                                    } catch (e: Exception) {
                                        result.set(JSONObject().put("ok", false).put("error", e.message ?: "parse failed"))
                                    } finally {
                                        latch.countDown()
                                    }
                                }
                            }, 450)
                        }
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            if (finished) return
                            finished = true
                            result.set(
                                JSONObject()
                                    .put("ok", false)
                                    .put("error", "load error $errorCode: ${description ?: ""}")
                                    .put("url", failingUrl ?: url)
                            )
                            latch.countDown()
                        }
                    }
                    ew.loadUrl(url)
                } catch (e: Exception) {
                    result.set(JSONObject().put("ok", false).put("error", e.message ?: "deepExtract failed"))
                    latch.countDown()
                }
            }

            val ok = latch.await(timeoutMs.coerceIn(3000, 30000), TimeUnit.MILLISECONDS)
            if (!ok) {
                mainHandler.post {
                    try { extractWeb?.stopLoading() } catch (_: Exception) {}
                }
                return JSONObject().put("ok", false).put("error", "timeout after ${timeoutMs}ms").put("url", url)
            }
            return result.get() ?: JSONObject().put("ok", false).put("error", "no result")
        }
    }

    override fun onDestroy() {
        if (bound) {
            try { unbindService(conn) } catch (_: Exception) {}
            bound = false
        }
        try { extractWeb?.destroy() } catch (_: Exception) {}
        extractWeb = null
        // Do NOT stop LlamaService — agent runs must survive Activity death
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun swapModel(slot: String): String {
            val svc = llama ?: return JSONObject().put("ok", false).put("error", "service not bound").toString()
            return try {
                svc.swapModel(slot).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "swap failed").toString()
            }
        }

        @JavascriptInterface
        fun health(): String {
            val svc = llama
            return JSONObject()
                .put("ok", svc?.ready == true)
                .put("slot", svc?.activeSlot ?: "chat")
                .put("ready", svc?.ready == true)
                .put("embReady", svc?.embReady == true)
                .put("ocrReady", svc?.ocrReady == true)
                .put("deepExtract", extractWeb != null)
                .put("error", svc?.lastError)
                .toString()
        }

        @JavascriptInterface
        fun baseUrl(): String = "http://127.0.0.1:8080/v1"

        @JavascriptInterface
        fun localPreset(): String = JSONObject()
            .put("baseUrl", "http://127.0.0.1:8080/v1")
            .put("model", "local")
            .put("name", "On-device llama.cpp")
            .toString()

        /**
         * Dynamic (post-JS) page extract via off-screen WebView.
         * Returns JSON string: { ok, url, title, text, chars } or { ok:false, error }.
         * Mirrors deep_fetch's CDP path for SPAs that plain fetch cannot see.
         */
        @JavascriptInterface
        fun deepExtract(url: String, timeoutMs: Int = 12000): String {
            return try {
                if (url.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "empty url").toString()
                }
                runDeepExtract(url.trim(), timeoutMs.toLong().coerceIn(3000, 30000)).toString()
            } catch (e: Exception) {
                Log.e("Quarry", "deepExtract failed", e)
                JSONObject().put("ok", false).put("error", e.message ?: "deepExtract failed").toString()
            }
        }

        /** Quick capability probe for the JS side. */
        @JavascriptInterface
        fun hasDeepExtract(): Boolean = extractWeb != null
    }
}
