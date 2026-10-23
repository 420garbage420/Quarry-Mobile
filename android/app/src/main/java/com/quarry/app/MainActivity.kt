package com.quarry.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.json.JSONObject

/**
 * Thin WebView shell for Quarry.
 * - Loads www/index.html from assets (or filesDir/www)
 * - Binds LlamaService for on-device llama-server
 * - Exposes window.QuarryNative / window.KojotBridge with swapModel + health
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var llama: LlamaService? = null
    private var bound = false

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

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Local OpenAI endpoint is http://127.0.0.1:8080
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            s.safeBrowsingEnabled = false
        }

        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                // keep navigation inside the app for file/asset; open http(s) in-place
                return false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                injectSlot()
            }
        }

        web.addJavascriptInterface(Bridge(), "QuarryNative")
        // Alias so ModelRouter's KojotBridge path works without changes
        web.addJavascriptInterface(Bridge(), "KojotBridge")

        // Prefer assets/www shipped in the APK
        web.loadUrl("file:///android_asset/www/index.html")

        // Start + bind model service
        val i = Intent(this, LlamaService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    private fun injectSlot() {
        val slot = llama?.activeSlot ?: "chat"
        val ready = llama?.ready == true
        val js = """
            (function(){
              window.__quarryNative = true;
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

    override fun onDestroy() {
        if (bound) {
            try { unbindService(conn) } catch (_: Exception) {}
            bound = false
        }
        // Do NOT stop LlamaService — agent runs must survive Activity death (Kojot pattern)
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
                .put("error", svc?.lastError)
                .toString()
        }

        @JavascriptInterface
        fun baseUrl(): String = "http://127.0.0.1:8080/v1"

        /** Suggest Settings preset values for on-device server. */
        @JavascriptInterface
        fun localPreset(): String = JSONObject()
            .put("baseUrl", "http://127.0.0.1:8080/v1")
            .put("model", "local")
            .put("name", "On-device llama.cpp")
            .toString()
    }
}
