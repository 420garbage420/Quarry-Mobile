package com.quarry.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Thin WebView shell for Quarry with Kojot-style local llama loading:
 * - External GGUF paths (no multi-GB copy into filesDir)
 * - Auto-bind from Download/Local Models (+ llm/embed/coder/ocr/image/decision)
 * - SAF picker with real-path resolution (Samsung-friendly fallbacks)
 * - Bridge ops: list_models, select_model, pick_*, set_models_dir, info, swap, etc.
 * - deepExtract() off-screen WebView for post-JS DOM extraction
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var extractWeb: WebView? = null
    private var llama: LlamaService? = null
    private var bound = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val extractLock = Any()
    private val prefs by lazy { getSharedPreferences("quarry", Context.MODE_PRIVATE) }

    /** Prefer external path (no copy). Never require a second copy under filesDir. */
    private fun resolveModel(prefKey: String, fallbackName: String): File {
        val external = prefs.getString(prefKey, null)
        if (!external.isNullOrBlank()) {
            val f = File(external)
            if (f.isFile && f.canRead()) return f
        }
        return File(filesDir, fallbackName)
    }

    private fun modelFile(): File = resolveModel("model_path", "model.gguf")
    private fun coderModelFile(): File = resolveModel("coder_path", "coder.gguf")
    private fun embModelFile(): File = resolveModel("emb_path", "nomic.gguf")
    private fun ocrModelFile(): File = resolveModel("ocr_path", "ocr.gguf")

    /** Default: /sdcard/Download/Local Models — optional subfolders */
    private val modelSubdirs = listOf("llm", "embed", "coder", "image", "ocr", "decision")

    private fun modelsDir(): File {
        val saved = prefs.getString("models_dir", null)
        val root = if (!saved.isNullOrBlank()) File(saved) else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Local Models")
        if (!root.exists()) try { root.mkdirs() } catch (_: Exception) {}
        for (sub in modelSubdirs) {
            try { File(root, sub).mkdirs() } catch (_: Exception) {}
        }
        return root
    }

    private fun categoryOfModel(f: File, root: File): String {
        return try {
            val rel = f.parentFile?.canonicalPath?.removePrefix(root.canonicalPath)?.trimStart('/', '\\') ?: ""
            val top = rel.substringBefore('/').substringBefore('\\').lowercase()
            if (top in modelSubdirs) top else "root"
        } catch (_: Exception) { "root" }
    }

    private fun filesOk(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                Environment.isExternalStorageManager()
            } else {
                val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                d.exists() && d.canRead()
            }
        } catch (_: Exception) { false }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            llama = (service as LlamaService.LocalBinder).getService()
            bound = true
            llama?.addReadyListener(readyCb)
            injectSlot()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            llama = null
            bound = false
        }
    }

    private val readyCb: (Boolean, String?) -> Unit = { ok, err ->
        mainHandler.post {
            injectSlot()
            try {
                val js = "try{window.__quarryReady=${ok};window.__modelLoading&&window.__modelLoading(false,'' );" +
                    "if(window.ninfo)window.ninfo()}catch(e){}"
                web.evaluateJavascript(js, null)
            } catch (_: Exception) {}
            if (!ok && !err.isNullOrBlank()) Log.w("Quarry", "llama not ready: $err")
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

        extractWeb = WebView(this).also { configureWebView(it) }

        web.loadUrl("file:///android_asset/www/index.html")

        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 99) } catch (_: Exception) {}
        }
        if (!prefs.getBoolean("battery_asked", false)) {
            prefs.edit().putBoolean("battery_asked", true).apply()
            requestBatteryExemption()
        }

        // Auto-bind empty slots from Download/Local Models (root + category subfolders)
        try { autoBindModelsFromDisk() } catch (e: Exception) {
            Log.w("Quarry", "autoBind on start: ${e.message}")
        }

        val i = Intent(this, LlamaService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        bindService(i, conn, Context.BIND_AUTO_CREATE)

        if (modelFile().exists()) {
            // Service onStartCommand will boot when model is present
        } else {
            Log.i("Quarry", "No chat model bound yet — put .gguf in Download/Local Models or use Settings → Models")
        }
    }

    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        } catch (_: Exception) {}
    }

    /**
     * Fill empty / broken model slots from the models folder without overwriting good binds.
     */
    private fun autoBindModelsFromDisk(): List<String> {
        val root = modelsDir()
        val bound = mutableListOf<String>()
        fun clearIfDead(pathKey: String, nameKey: String) {
            if (!filesOk()) return
            val p = prefs.getString(pathKey, null) ?: return
            if (p.isBlank()) return
            val f = File(p)
            if (!f.exists() && f.parentFile?.isDirectory == true) {
                prefs.edit().remove(pathKey).remove(nameKey).apply()
                Log.w("Quarry", "cleared missing $pathKey=$p")
            }
        }
        clearIfDead("model_path", "model_name")
        clearIfDead("coder_path", "coder_name")
        clearIfDead("emb_path", "emb_name")
        clearIfDead("ocr_path", "ocr_name")

        fun listGgufs(dir: File): List<File> {
            if (!dir.isDirectory) return emptyList()
            return dir.listFiles()
                ?.filter {
                    it.isFile && it.canRead() &&
                        it.name.endsWith(".gguf", true) &&
                        !it.name.contains("mmproj", true)
                }
                ?.sortedBy { it.length() }
                ?: emptyList()
        }
        fun childDir(parent: File, name: String): File? {
            val exact = File(parent, name)
            if (exact.isDirectory) return exact
            return parent.listFiles()?.firstOrNull {
                it.isDirectory && it.name.equals(name, ignoreCase = true)
            }
        }
        fun firstIn(vararg subs: String): File? {
            for (sub in subs) {
                val dir = childDir(root, sub) ?: continue
                val hit = listGgufs(dir).firstOrNull()
                if (hit != null) return hit
            }
            return null
        }
        if (prefs.getString("model_path", null).isNullOrBlank()) {
            val f = firstIn("llm", "decision") ?: listGgufs(root).firstOrNull()
            if (f != null) {
                prefs.edit().putString("model_path", f.canonicalPath).putString("model_name", f.name).apply()
                File(filesDir, "model.gguf").let { if (it.exists()) it.delete() }
                bound.add("chat→${f.name}")
            }
        }
        if (prefs.getString("coder_path", null).isNullOrBlank()) {
            firstIn("coder")?.let { f ->
                prefs.edit().putString("coder_path", f.canonicalPath).putString("coder_name", f.name).apply()
                bound.add("coder→${f.name}")
            }
        }
        if (prefs.getString("emb_path", null).isNullOrBlank()) {
            firstIn("embed")?.let { f ->
                prefs.edit().putString("emb_path", f.canonicalPath).putString("emb_name", f.name).apply()
                bound.add("emb→${f.name}")
            }
        }
        if (prefs.getString("ocr_path", null).isNullOrBlank()) {
            firstIn("ocr", "image")?.let { f ->
                prefs.edit().putString("ocr_path", f.canonicalPath).putString("ocr_name", f.name).apply()
                bound.add("ocr→${f.name}")
            }
        }
        if (bound.isNotEmpty()) Log.i("Quarry", "auto-bind: ${bound.joinToString()}")
        else Log.i("Quarry", "auto-bind: nothing found under ${root.path}")
        return bound
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
        if (Build.VERSION.SDK_INT >= 26) {
            s.safeBrowsingEnabled = false
        }
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
        val m = modelFile()
        val js = """
            (function(){
              window.__quarryNative = true;
              window.__quarryDeepExtract = true;
              window.__quarrySlot = ${JSONObject.quote(slot)};
              window.__quarryReady = ${if (ready) "true" else "false"};
              window.__quarryModel = ${JSONObject.quote(prefs.getString("model_name", null) ?: m.name)};
              try {
                var el = document.getElementById('saveChip');
                if (el && $ready) el.title = 'On-device llama slot: ' + ${JSONObject.quote(slot)};
              } catch (e) {}
            })();
        """.trimIndent()
        web.post { web.evaluateJavascript(js, null) }
    }

    private fun notifyModelLoading(loading: Boolean, message: String = "Model loading…") {
        val safe = JSONObject.quote(message)
        val js = "try{window.__modelLoading&&window.__modelLoading($loading,$safe)}catch(e){}"
        mainHandler.post { try { web.evaluateJavascript(js, null) } catch (_: Exception) {} }
    }

    private fun bindAndStartService() {
        val i = Intent(this, LlamaService::class.java).setAction(LlamaService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        if (!bound) bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    private fun restartServer() {
        notifyModelLoading(true, "Restarting model…")
        try { llama?.restartServers() } catch (_: Exception) {
            bindAndStartService()
        }
    }

    // ─── SAF / real-path resolution (zero-copy GGUF binding) ───────────────

    private fun pickModel() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_MODEL)
    }

    private fun pickEmbedding() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_EMB)
    }

    private fun pickCoder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_CODER)
    }

    private fun pickOcr() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_OCR)
    }

    /**
     * Resolve a real filesystem path from a content/file URI so we never copy multi-GB GGUFs.
     * Samsung/SAF often hide MediaStore DATA — use DocumentsContract + recursive name search.
     */
    private fun resolveRealPath(uri: Uri, displayName: String? = null): String? {
        fun readable(f: File?): String? =
            try { if (f != null && f.isFile && f.canRead()) f.canonicalPath else null } catch (_: Exception) { null }

        try {
            if ("file".equals(uri.scheme, ignoreCase = true)) {
                readable(File(uri.path ?: return null))?.let { return it }
            }

            contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx >= 0) readable(File(c.getString(idx) ?: ""))?.let { return it }
                }
            }
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    for (col in arrayOf("_data", "filePath", "path", android.provider.MediaStore.MediaColumns.DATA)) {
                        val idx = c.getColumnIndex(col)
                        if (idx >= 0) readable(File(c.getString(idx) ?: ""))?.let { return it }
                    }
                }
            }

            val docId = try {
                android.provider.DocumentsContract.getDocumentId(uri)
            } catch (_: Exception) { null }
            if (!docId.isNullOrBlank()) {
                when {
                    docId.startsWith("raw:") -> readable(File(docId.removePrefix("raw:")))?.let { return it }
                    docId.startsWith("msd:") -> readable(File(docId.substringAfter(':')))?.let { return it }
                    docId.contains(':') -> {
                        val rel = java.net.URLDecoder.decode(docId.substringAfter(':'), "UTF-8").replace(':', '/')
                        readable(File(Environment.getExternalStorageDirectory(), rel))?.let { return it }
                        if (rel.startsWith('/')) readable(File(rel))?.let { return it }
                    }
                }
            }

            uri.lastPathSegment?.let { seg ->
                val decoded = try { java.net.URLDecoder.decode(seg, "UTF-8") } catch (_: Exception) { seg }
                if (decoded.contains(':')) {
                    val rel = decoded.substringAfter(':').replace(':', '/')
                    readable(File(Environment.getExternalStorageDirectory(), rel))?.let { return it }
                }
                if (decoded.startsWith("/storage/") || decoded.startsWith("/sdcard/")) {
                    readable(File(decoded))?.let { return it }
                }
            }

            val name = displayName?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                    ?.let { try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } }
            if (!name.isNullOrBlank() && name.endsWith(".gguf", ignoreCase = true)) {
                val direct = listOf(
                    File(modelsDir(), name),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Local Models/$name")
                )
                for (f in direct) readable(f)?.let { return it }

                fun findNamed(root: File, depth: Int = 0): File? {
                    if (depth > 4 || !root.isDirectory) return null
                    val kids = root.listFiles() ?: return null
                    for (k in kids) {
                        if (k.isFile && k.name.equals(name, ignoreCase = true)) return k
                    }
                    for (k in kids) {
                        if (k.isDirectory && !k.name.startsWith(".")) {
                            findNamed(k, depth + 1)?.let { return it }
                        }
                    }
                    return null
                }
                readable(findNamed(modelsDir()))?.let { return it }
                val dlLocal = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Local Models")
                readable(findNamed(dlLocal))?.let { return it }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun bindExternalModel(r: Int, realPath: String, fileName: String?) {
        val name = fileName ?: File(realPath).name
        val ed = prefs.edit()
        when (r) {
            REQ_MODEL -> {
                ed.putString("model_path", realPath).putString("model_name", name)
                File(filesDir, "model.gguf").let { if (it.exists()) it.delete() }
            }
            REQ_CODER -> {
                ed.putString("coder_path", realPath).putString("coder_name", name)
                File(filesDir, "coder.gguf").let { if (it.exists()) it.delete() }
            }
            REQ_EMB -> {
                ed.putString("emb_path", realPath).putString("emb_name", name)
                File(filesDir, "nomic.gguf").let { if (it.exists()) it.delete() }
            }
            REQ_OCR -> {
                ed.putString("ocr_path", realPath).putString("ocr_name", name)
            }
        }
        ed.apply()
        runOnUiThread {
            when (r) {
                REQ_MODEL -> restartServer()
                REQ_EMB -> {
                    if (llama != null) llama?.restartServers() else bindAndStartService()
                }
                REQ_CODER, REQ_OCR -> { /* path only — loads on demand */ }
            }
            injectSlot()
            try {
                web.evaluateJavascript("try{window.ninfo&&window.ninfo()}catch(e){}", null)
            } catch (_: Exception) {}
        }
    }

    @Deprecated("plain Activity API for dependency-free SAF results")
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (c != Activity.RESULT_OK || d?.data == null) return
        val uri = d.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}

        val fileName = try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }

        val realPath = resolveRealPath(uri, fileName)
        if (realPath != null) {
            bindExternalModel(r, realPath, fileName)
            return
        }

        // Refuse silent multi-GB copy — guide to in-app list (real paths)
        val dir = modelsDir().path
        runOnUiThread {
            try {
                web.evaluateJavascript(
                    "try{alert(${JSONObject.quote(
                        "Could not map that file to a real path (Samsung/Android often blocks it).\\n\\n" +
                        "1. Copy or move the .gguf into:\\n$dir\\n" +
                        "   (or llm/ embed/ coder/ ocr/ subfolders)\\n" +
                        "2. Use list_models / select_model from Settings.\\n\\n" +
                        "No multi-GB copy into app storage."
                    )})}catch(e){}",
                    null
                )
            } catch (_: Exception) {}
        }
    }

    // ─── deepExtract (unchanged Quarry capability) ─────────────────────────

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
        } catch (_: Exception) {}
        return uri
    }

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
                            mainHandler.postDelayed({
                                val extractJs = """
                                    (function(){
                                      try {
                                        var doc = document;
                                        var scripts = doc.querySelectorAll('script,style,noscript,svg,iframe');
                                        for (var i=0;i<scripts.length;i++) scripts[i].remove();
                                        var body = doc.body || doc.documentElement;
                                        var text = (body && body.innerText) ? body.innerText : '';
                                        text = text.replace(/\s+/g,' ').trim();
                                        return JSON.stringify({
                                          ok: true,
                                          url: location.href,
                                          title: doc.title || '',
                                          text: text.slice(0, 200000),
                                          chars: text.length
                                        });
                                      } catch (e) {
                                        return JSON.stringify({ ok: false, error: String(e) });
                                      }
                                    })();
                                """.trimIndent()
                                ew.evaluateJavascript(extractJs) { raw ->
                                    try {
                                        val unquoted = if (raw != null && raw.startsWith("\"") && raw.endsWith("\"")) {
                                            JSONObject("{\"v\":$raw}").getString("v")
                                        } else raw
                                        result.set(JSONObject(unquoted ?: """{"ok":false,"error":"empty"}"""))
                                    } catch (e: Exception) {
                                        result.set(JSONObject().put("ok", false).put("error", e.message ?: "parse"))
                                    }
                                    latch.countDown()
                                }
                            }, 800)
                        }
                    }
                    ew.loadUrl(url)
                } catch (e: Exception) {
                    result.set(JSONObject().put("ok", false).put("error", e.message ?: "load failed"))
                    latch.countDown()
                }
            }

            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!ok) {
                mainHandler.post { try { ew.stopLoading() } catch (_: Exception) {} }
                return JSONObject().put("ok", false).put("error", "timeout")
            }
            return result.get() ?: JSONObject().put("ok", false).put("error", "no result")
        }
    }

    // ─── Bridge ops (Kojot-compatible model management) ────────────────────

    private fun dispatch(op: String, a: JSONObject): JSONObject = when (op) {
        "pick_model" -> {
            runOnUiThread { pickModel() }
            JSONObject().put("text", "model picker opened")
        }
        "pick_coder" -> {
            runOnUiThread { pickCoder() }
            JSONObject().put("text", "coder picker opened")
        }
        "pick_embedding", "pick_emb" -> {
            runOnUiThread { pickEmbedding() }
            JSONObject().put("text", "embedding picker opened")
        }
        "pick_ocr" -> {
            runOnUiThread { pickOcr() }
            JSONObject().put("text", "ocr picker opened")
        }
        "delete_model" -> {
            prefs.edit().remove("model_path").remove("model_name").apply()
            File(filesDir, "model.gguf").let { if (it.exists()) it.delete() }
            try { llama?.stopServers() } catch (_: Exception) {}
            JSONObject().put("ok", true).put("text", "main model unbound (folder file kept)")
        }
        "delete_coder" -> {
            prefs.edit().remove("coder_path").remove("coder_name").remove("srv_coder").apply()
            File(filesDir, "coder.gguf").let { if (it.exists()) it.delete() }
            if (llama?.activeSlot == "coder") {
                try { llama?.swapModel("chat") } catch (_: Exception) {}
            }
            JSONObject().put("ok", true).put("text", "coder model unbound (folder file kept)")
        }
        "delete_emb" -> {
            prefs.edit().remove("emb_path").remove("emb_name").apply()
            File(filesDir, "nomic.gguf").let { if (it.exists()) it.delete() }
            llama?.restartServers()
            JSONObject().put("ok", true).put("text", "embedding model unbound (folder file kept)")
        }
        "delete_ocr" -> {
            prefs.edit().remove("ocr_path").remove("ocr_name").remove("image_path").remove("image_name").apply()
            try { llama?.stopOcr() } catch (_: Exception) {}
            JSONObject().put("ok", true).put("text", "OCR model unbound (folder file kept)")
        }
        "set_models_dir" -> {
            val p = a.optString("path").ifBlank {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Local Models").path
            }
            val d = File(p)
            if (!d.exists()) try { d.mkdirs() } catch (_: Exception) {}
            if (!d.isDirectory) throw RuntimeException("not a folder: $p")
            prefs.edit().putString("models_dir", d.canonicalPath).apply()
            modelsDir()
            JSONObject().put("text", d.canonicalPath).put("path", d.canonicalPath)
                .put("subdirs", JSONArray(modelSubdirs))
        }
        "get_models_dir" -> {
            val d = modelsDir()
            JSONObject().put("text", d.path).put("path", d.path)
                .put("subdirs", JSONArray(modelSubdirs))
        }
        "list_models", "list_models_dir" -> {
            val d = modelsDir()
            if (!d.isDirectory) throw RuntimeException("models folder missing: ${d.path}")
            val found = ArrayList<File>()
            fun walk(dir: File, depth: Int) {
                if (depth > 3) return
                val kids = dir.listFiles() ?: return
                for (c in kids) {
                    if (c.isDirectory) walk(c, depth + 1)
                    else if (c.isFile && c.name.endsWith(".gguf", true) && !c.name.contains("mmproj", true)) {
                        found.add(c)
                    }
                }
            }
            walk(d, 0)
            found.sortBy { it.absolutePath.lowercase() }
            val arr = JSONArray()
            found.forEach { f ->
                val cat = categoryOfModel(f, d)
                val suggest = when (cat) {
                    "embed" -> "emb"
                    "coder" -> "coder"
                    "llm", "decision" -> "chat"
                    "image", "ocr" -> cat
                    else -> "chat"
                }
                arr.put(JSONObject()
                    .put("name", f.name)
                    .put("path", f.absolutePath)
                    .put("size", f.length())
                    .put("mb", f.length() / 1048576.0)
                    .put("category", cat)
                    .put("suggest_slot", suggest))
            }
            JSONObject().put("text", "${found.size} GGUF under ${d.path}")
                .put("path", d.path)
                .put("subdirs", JSONArray(modelSubdirs))
                .put("models", arr)
        }
        "select_model" -> {
            val path = a.optString("path")
            var slot = a.optString("slot", "chat")
            val f = File(path)
            if (!f.isFile || !f.canRead()) throw RuntimeException("not readable: $path")
            if (slot == "auto" || slot.isBlank()) {
                val cat = categoryOfModel(f, modelsDir())
                slot = when (cat) {
                    "embed" -> "emb"
                    "coder" -> "coder"
                    "ocr" -> "ocr"
                    "image" -> "image"
                    else -> "chat"
                }
            }
            when (slot) {
                "coder" -> {
                    prefs.edit().putString("coder_path", f.canonicalPath).putString("coder_name", f.name).apply()
                    File(filesDir, "coder.gguf").let { if (it.exists()) it.delete() }
                }
                "emb", "embedding" -> {
                    prefs.edit().putString("emb_path", f.canonicalPath).putString("emb_name", f.name).apply()
                    File(filesDir, "nomic.gguf").let { if (it.exists()) it.delete() }
                    runOnUiThread {
                        if (llama != null) llama?.restartServers() else bindAndStartService()
                    }
                }
                "ocr", "image" -> {
                    val pathKey = if (slot == "ocr") "ocr_path" else "image_path"
                    val nameKey = if (slot == "ocr") "ocr_name" else "image_name"
                    prefs.edit().putString(pathKey, f.canonicalPath).putString(nameKey, f.name).apply()
                    if (slot == "image" && prefs.getString("ocr_path", null).isNullOrBlank()) {
                        prefs.edit().putString("ocr_path", f.canonicalPath).putString("ocr_name", f.name).apply()
                    }
                }
                else -> {
                    prefs.edit().putString("model_path", f.canonicalPath).putString("model_name", f.name).apply()
                    File(filesDir, "model.gguf").let { if (it.exists()) it.delete() }
                    runOnUiThread { restartServer() }
                }
            }
            JSONObject().put("ok", true).put("text", "bound $slot → ${f.name} (no copy)").put("path", f.canonicalPath)
        }
        "auto_bind" -> {
            val bound = autoBindModelsFromDisk()
            if (modelFile().exists() && llama?.ready != true) {
                runOnUiThread {
                    if (llama != null) llama?.restartServers() else bindAndStartService()
                }
            }
            JSONObject().put("ok", true)
                .put("bound", JSONArray(bound as Collection<*>))
                .put("text", if (bound.isEmpty()) "No empty slots to fill" else "Auto-bound: ${bound.joinToString(", ")}")
        }
        "swap_model" -> {
            val slot = a.optString("slot", "chat")
            val svc = llama ?: throw RuntimeException("service not bound")
            notifyModelLoading(true, "Switching to $slot…")
            val r = svc.swapModel(slot)
            notifyModelLoading(false, "")
            injectSlot()
            r
        }
        "restart" -> {
            prefs.edit().putString("srv", (a.optJSONObject("srv") ?: JSONObject()).toString()).apply()
            runOnUiThread { restartServer() }
            JSONObject().put("text", "restarting")
        }
        "set_srv" -> {
            val s = a.optJSONObject("srv") ?: JSONObject()
            prefs.edit().putString("srv", s.toString()).apply()
            JSONObject().put("text", "saved srv")
        }
        "set_coder_srv" -> {
            val s = a.optJSONObject("srv") ?: JSONObject()
            prefs.edit().putString("srv_coder", s.toString()).apply()
            JSONObject().put("text", "saved coder srv")
        }
        "grant" -> {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            JSONObject().put("text", "opened all-files access settings")
        }
        else -> throw RuntimeException("unknown op: $op")
    }

    override fun onDestroy() {
        try { llama?.removeReadyListener(readyCb) } catch (_: Exception) {}
        if (bound) {
            try { unbindService(conn) } catch (_: Exception) {}
            bound = false
        }
        try { extractWeb?.destroy() } catch (_: Exception) {}
        extractWeb = null
        // Do NOT stop LlamaService — agent runs must survive Activity death
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun swapModel(slot: String): String {
            val svc = llama ?: return JSONObject().put("ok", false).put("error", "service not bound").toString()
            return try {
                notifyModelLoading(true, "Switching to $slot…")
                val r = svc.swapModel(slot).toString()
                notifyModelLoading(false, "")
                injectSlot()
                r
            } catch (e: Exception) {
                notifyModelLoading(false, "")
                JSONObject().put("ok", false).put("error", e.message ?: "swap failed").toString()
            }
        }

        @JavascriptInterface
        fun health(): String {
            val svc = llama
            val m = modelFile()
            return JSONObject()
                .put("ok", svc?.ready == true)
                .put("slot", svc?.activeSlot ?: "chat")
                .put("ready", svc?.ready == true)
                .put("embReady", svc?.embReady == true)
                .put("ocrReady", svc?.ocrReady == true)
                .put("deepExtract", extractWeb != null)
                .put("model_present", m.isFile)
                .put("model_name", prefs.getString("model_name", null) ?: m.name)
                .put("model_path", if (m.isFile) m.path else null)
                .put("error", svc?.lastError)
                .toString()
        }

        @JavascriptInterface
        fun info(): String {
            val m = modelFile()
            val coder = coderModelFile()
            val emb = embModelFile()
            val modelExt = !prefs.getString("model_path", null).isNullOrBlank() && m.isFile
            val coderExt = !prefs.getString("coder_path", null).isNullOrBlank() && coder.isFile
            val embExt = !prefs.getString("emb_path", null).isNullOrBlank() && emb.isFile
            return JSONObject()
                .put("files", filesOk())
                .put("models_dir", modelsDir().path)
                .put("srv", try { JSONObject(prefs.getString("srv", "{}") ?: "{}") } catch (_: Exception) { JSONObject() })
                .put("srv_coder", try { JSONObject(prefs.getString("srv_coder", "{}") ?: "{}") } catch (_: Exception) { JSONObject() })
                .put("embed", llama?.embReady == true)
                .put("embed_model", emb.isFile)
                .put("emb_name", prefs.getString("emb_name", null) ?: emb.name)
                .put("emb_path", if (emb.isFile) emb.path else null)
                .put("emb_external", embExt)
                .put("coder_model", coder.isFile)
                .put("coder_name", prefs.getString("coder_name", null) ?: coder.name)
                .put("coder_path", if (coder.isFile) coder.path else null)
                .put("coder_external", coderExt)
                .put("active_slot", llama?.activeSlot ?: "chat")
                .put("model_present", m.isFile)
                .put("model_name", prefs.getString("model_name", null) ?: m.name)
                .put("model_path", if (m.isFile) m.path else null)
                .put("model_external", modelExt)
                .put("server_ready", llama?.ready == true)
                .put("ocr_path", prefs.getString("ocr_path", null))
                .put("ocr_ready", llama?.ocrReady == true)
                .put("ocr_name", prefs.getString("ocr_name", null))
                .put("image_path", prefs.getString("image_path", null))
                .put("image_name", prefs.getString("image_name", null))
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
         * Async bridge: call(id, op, argsJson) → replies via window.__quarryReply / __kojotReply
         * Ops mirror Kojot: list_models, select_model, pick_model, auto_bind, grant, …
         */
        @JavascriptInterface
        fun call(id: Int, op: String, args: String) {
            thread {
                try {
                    val result = dispatch(op, JSONObject(if (args.isBlank()) "{}" else args)).toString()
                    reply(id, true, result)
                } catch (e: Throwable) {
                    Log.e("Quarry", "bridge op=$op", e)
                    try {
                        reply(id, false, e.message ?: e.javaClass.simpleName)
                    } catch (_: Throwable) {}
                }
            }
        }

        private fun reply(id: Int, ok: Boolean, payload: String) {
            val safe = JSONObject.quote(payload)
            val js = "try{(window.__quarryReply||window.__kojotReply||window.__nativeReply)&&" +
                "(window.__quarryReply||window.__kojotReply||window.__nativeReply)($id,$ok,$safe)}catch(e){}"
            mainHandler.post {
                try { web.evaluateJavascript(js, null) } catch (_: Exception) {}
            }
        }

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

        @JavascriptInterface
        fun hasDeepExtract(): Boolean = extractWeb != null
    }

    companion object {
        private const val REQ_MODEL = 1
        private const val REQ_EMB = 2
        private const val REQ_CODER = 3
        private const val REQ_OCR = 5
    }
}
