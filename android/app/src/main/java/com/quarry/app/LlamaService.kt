package com.quarry.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Foreground service that owns llama-server (chat) and the embedding server.
 * Survives Activity destruction so long agent runs are not killed when the UI is backgrounded.
 */
class LlamaService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): LlamaService = this@LlamaService
    }

    private val binder = LocalBinder()
    private val prefs by lazy { getSharedPreferences("quarry", Context.MODE_PRIVATE) }

    @Volatile private var proc: Process? = null
    @Volatile private var embProc: Process? = null
    @Volatile private var ocrProc: Process? = null
    @Volatile var embReady: Boolean = false
        private set
    @Volatile var ocrReady: Boolean = false
        private set
    @Volatile var activeSlot: String = "chat"
        private set
    @Volatile var ready: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    /** Bumps on every boot/swap so a stale boot thread aborts instead of orphaning a process. */
    @Volatile private var bootGen: Int = 0
    // Flash attention + q8_0 KV cache are the defaults; if the server can't start with them, we fall back once to f16.
    @Volatile private var safeKv: Boolean = false
    @Volatile private var kvDefaultsUsed: Boolean = false
    @Volatile private var booting: Boolean = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var readyListeners = mutableListOf<(Boolean, String?) -> Unit>()
    private val bootLock = Any()

    /** Prefer external path (no copy) when set and readable; else optional filesDir fallback. */
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

    /** Sibling mmproj for vision models. */
    private fun findMmproj(model: File): File? {
        val dir = model.parentFile ?: return null
        val stem = model.nameWithoutExtension
        val candidates = listOf(
            File(dir, "$stem.mmproj.gguf"),
            File(dir, "$stem-mmproj.gguf"),
            File(dir, "mmproj-$stem.gguf"),
            File(dir, "mmproj.gguf")
        )
        candidates.firstOrNull { it.isFile && it.canRead() }?.let { return it }
        return dir.listFiles()?.firstOrNull { f ->
            f.isFile && f.canRead() && f.name.contains("mmproj", ignoreCase = true) && f.name.endsWith(".gguf", true)
        }
    }

    private fun activeModelFile(): File =
        if (activeSlot == "coder" && coderModelFile().isFile) coderModelFile() else modelFile()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServers()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                promoteForeground("Starting model…")
                // Only boot if nothing is alive and no boot in flight (avoids double-bind on :8080)
                if (modelFile().exists() && proc?.isAlive != true && !booting) {
                    startServers()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServers()
        releaseWakeLock()
        super.onDestroy()
    }

    fun addReadyListener(cb: (Boolean, String?) -> Unit) {
        synchronized(readyListeners) { readyListeners.add(cb) }
        if (ready) cb(true, null)
        else if (lastError != null) cb(false, lastError)
    }

    fun removeReadyListener(cb: (Boolean, String?) -> Unit) {
        synchronized(readyListeners) { readyListeners.remove(cb) }
    }

    private fun notifyReady(ok: Boolean, err: String?) {
        ready = ok
        lastError = err
        val copy: List<(Boolean, String?) -> Unit>
        synchronized(readyListeners) { copy = readyListeners.toList() }
        copy.forEach { try { it(ok, err) } catch (_: Exception) {} }
    }

    fun startServers() {
        synchronized(bootLock) {
            if (booting) {
                Log.i(TAG, "startServers ignored — boot already in flight")
                return
            }
            if (proc?.isAlive == true && ready) {
                Log.i(TAG, "startServers ignored — already ready")
                return
            }
            booting = true
        }
        promoteForeground("Loading model…")
        val gen = ++bootGen
        thread { boot(null, null, gen) }
    }

    fun restartServers() {
        synchronized(bootLock) { booting = true }
        promoteForeground("Restarting model…")
        val old = proc; proc = null
        val oldEmb = embProc; embProc = null
        embReady = false
        ocrReady = false
        ready = false
        val gen = ++bootGen
        thread { boot(old, oldEmb, gen) }
    }

    fun swapModel(slot: String): JSONObject {
        val target = if (slot == "coder") "coder" else "chat"
        if (target == "coder" && !coderModelFile().isFile) {
            return JSONObject().put("ok", false).put("error", "Coder model not installed").put("slot", activeSlot)
        }
        if (target == activeSlot && proc?.isAlive == true) {
            return JSONObject().put("ok", true).put("slot", activeSlot).put("cached", true)
        }
        // Never keep vision OCR up while loading chat/coder (RAM)
        stopOcr()
        activeSlot = target
        promoteForeground(if (target == "coder") "Switching to coder model…" else "Switching to chat model…")
        proc?.destroy()
        try { proc?.waitFor() } catch (_: Exception) {}
        proc = null
        ready = false
        val www = File(filesDir, "www")
        val exe = File(applicationInfo.nativeLibraryDir, "libllama_server.so")
        return try {
            val p = ProcessBuilder(serverArgs(exe.path, www.path)).redirectErrorStream(true).start()
            proc = p
            thread { p.inputStream.bufferedReader().forEachLine { Log.d("llama", it) } }
            // Match boot() patience (~5 min) — big models on FUSE exceed 21s easily
            for (i in 0 until 300) {
                if (!p.isAlive) {
                    val err = "Exited with code ${p.exitValue()}"
                    notifyReady(false, err)
                    return JSONObject().put("ok", false).put("error", err).put("slot", activeSlot)
                }
                try {
                    val c = URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection
                    c.connectTimeout = 300; c.readTimeout = 300
                    if (c.responseCode == 200) {
                        ready = true
                        updateNotification(activeModelFile().name)
                        notifyReady(true, null)
                        return JSONObject().put("ok", true).put("slot", activeSlot).put("model", activeModelFile().name)
                    }
                } catch (_: Exception) {}
                Thread.sleep(1000)
            }
            val err = "Startup timed out"
            notifyReady(false, err)
            JSONObject().put("ok", false).put("error", err).put("slot", activeSlot)
        } catch (e: Exception) {
            val err = e.message ?: "swap failed"
            notifyReady(false, err)
            JSONObject().put("ok", false).put("error", err).put("slot", activeSlot)
        }
    }

    /**
     * Demand-driven router: one heavy role at a time.
     * - chat / coder: soft-swap on :8080 (stops OCR first)
     * - ocr: pause :8080 to free RAM, start vision :8082 if mmproj present
     * - chat_resume: after OCR, bring previous chat/coder slot back
     * Embed (:8081) stays up when possible (small).
     */
    @Volatile private var slotBeforeOcr: String? = null

    fun ensureSlot(role: String): JSONObject {
        val r = role.lowercase().trim()
        return when (r) {
            "chat", "coder" -> {
                val prevOcr = ocrReady
                stopOcr()
                val out = swapModel(r)
                out.put("stopped_ocr", prevOcr)
            }
            "ocr", "vision" -> {
                // Do NOT kill chat for OCR (that caused minimize/reset). Try vision alongside chat;
                // PdfTools falls back to ML Kit if :8082 is down.
                val ocrFile = ocrModelFile()
                val canVision = ocrFile.isFile && findMmproj(ocrFile) != null
                val ok = if (canVision) {
                    try { ensureOcr(45_000L) } catch (_: Exception) { false }
                } else false
                JSONObject()
                    .put("ok", true)
                    .put("slot", "ocr")
                    .put("ocr_ready", ok)
                    .put("mlkit", !ok)
                    .put("text", if (ok) "OCR vision :8082 ready" else "OCR via ML Kit")
            }
            "chat_resume", "resume" -> {
                stopOcr()
                val target = when {
                    slotBeforeOcr == "coder" && coderModelFile().isFile -> "coder"
                    else -> "chat"
                }
                slotBeforeOcr = null
                swapModel(target).put("resumed", true)
            }
            "embed" -> {
                val emb = embModelFile()
                if (!emb.isFile) {
                    return JSONObject().put("ok", false).put("error", "Embedding model not bound")
                }
                if (embReady && embProc?.isAlive == true) {
                    return JSONObject().put("ok", true).put("slot", "embed").put("cached", true)
                }
                val exe = File(applicationInfo.nativeLibraryDir, "libllama_server.so")
                startEmbedding(exe)
                val deadline = System.currentTimeMillis() + 45_000L
                while (System.currentTimeMillis() < deadline) {
                    if (embReady) break
                    try { Thread.sleep(300) } catch (_: Exception) {}
                }
                JSONObject().put("ok", embReady).put("slot", "embed").put("emb_ready", embReady)
            }
            else -> JSONObject().put("ok", false).put("error", "unknown role: $role")
        }
    }

    fun stopServers() {
        proc?.destroy()
        embProc?.destroy()
        ocrProc?.destroy()
        try { proc?.waitFor() } catch (_: Exception) {}
        try { embProc?.waitFor() } catch (_: Exception) {}
        try { ocrProc?.waitFor() } catch (_: Exception) {}
        proc = null
        embProc = null
        ocrProc = null
        embReady = false
        ocrReady = false
        ready = false
        releaseWakeLock()
    }

    fun isChatAlive(): Boolean = proc?.isAlive == true
    fun isEmbAlive(): Boolean = embProc?.isAlive == true
    fun isOcrAlive(): Boolean = ocrProc?.isAlive == true && ocrReady

    private fun boot(old: Process?, oldEmb: Process?, gen: Int) {
        try {
            old?.let { it.destroy(); try { it.waitFor() } catch (_: Exception) {} }
            oldEmb?.let { it.destroy(); try { it.waitFor() } catch (_: Exception) {} }
            // Also kill any leftover OCR from a previous generation
            try { stopOcr() } catch (_: Exception) {}

            if (gen != bootGen) {
                Log.i(TAG, "boot gen $gen aborted (current $bootGen)")
                return
            }

            acquireWakeLock()
            val www = File(filesDir, "www").apply { if (!exists()) mkdirs() }
            val exe = File(applicationInfo.nativeLibraryDir, "libllama_server.so")
            startEmbedding(exe)

            if (!modelFile().isFile && !(activeSlot == "coder" && coderModelFile().isFile)) {
                notifyReady(false, "No model file")
                updateNotification("No model")
                return
            }

            if (gen != bootGen) return

            var p = ProcessBuilder(serverArgs(exe.path, www.path)).redirectErrorStream(true).start()
            proc = p
            run { val pp = p; thread { pp.inputStream.bufferedReader().forEachLine { Log.d("llama", it) } } }
            var retried = false

            repeat(300) {
                if (gen != bootGen) {
                    try { p.destroy() } catch (_: Exception) {}
                    return
                }
                if (!p.isAlive) {
                    if (kvDefaultsUsed && !safeKv && !retried) {
                        retried = true
                        safeKv = true
                        Log.w(TAG, "Server exited with flash-attn/q8 KV defaults; retrying once with f16 KV")
                        updateNotification("Retrying without KV quantization…")
                        p = ProcessBuilder(serverArgs(exe.path, www.path)).redirectErrorStream(true).start()
                        proc = p
                        val pp = p
                        thread { pp.inputStream.bufferedReader().forEachLine { Log.d("llama", it) } }
                        return@repeat
                    }
                    if (retried) safeKv = false   // the retry failed too, so the KV flags weren't the cause
                    val code = try { p.exitValue() } catch (_: Exception) { -1 }
                    val err = "Model server exited (code $code)"
                    Log.e(TAG, err)
                    notifyReady(false, err)
                    updateNotification(err)
                    return
                }
                try {
                    val c = URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection
                    c.connectTimeout = 500; c.readTimeout = 500
                    if (c.responseCode == 200) {
                        ready = true
                        lastError = null
                        if (retried) prefs.edit().putBoolean("kv_safe", true).apply()  // remember: this device/build needs f16 KV
                        updateNotification(activeModelFile().name)
                        notifyReady(true, null)
                        return
                    }
                } catch (_: Exception) {}
                Thread.sleep(1000)
            }
            notifyReady(false, "Server didn't become ready in time")
            updateNotification("Startup timeout")
        } catch (e: Exception) {
            Log.e(TAG, "boot failed", e)
            notifyReady(false, e.message ?: "boot failed")
            updateNotification("Error: ${e.message}")
        } finally {
            synchronized(bootLock) {
                if (gen == bootGen) booting = false
            }
        }
    }

    private fun startEmbedding(exe: File) {
        embProc?.let { it.destroy(); try { it.waitFor() } catch (_: Exception) {} }
        embProc = null
        embReady = false
        val emb = embModelFile()
        if (!emb.isFile) return
        try {
            // -c 2048: nomic paths/snippets exceed 512 tokens; ubatch matches
            val args = listOf(
                exe.path, "-m", emb.path,
                "--host", "127.0.0.1", "--port", "8081",
                "--embedding", "-c", "2048", "-b", "2048", "-ub", "2048", "-t", "2"
            )
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            embProc = p
            thread { p.inputStream.bufferedReader().forEachLine { Log.d("llama-emb", it) } }
            thread {
                // Longer wait — chat model may be loading on the same cores
                repeat(180) {
                    if (!p.isAlive) return@thread
                    try {
                        val c = URL("http://127.0.0.1:8081/health").openConnection() as HttpURLConnection
                        c.connectTimeout = 500; c.readTimeout = 500
                        if (c.responseCode == 200) {
                            embReady = true
                            Log.i(TAG, "embedding server ready on :8081")
                            return@thread
                        }
                    } catch (_: Exception) {}
                    Thread.sleep(500)
                }
                Log.w(TAG, "embedding server health timeout")
            }
        } catch (e: Exception) {
            Log.e(TAG, "embedding failed: ${e.message}")
        }
    }

    /**
     * Optional vision/OCR server on :8082.
     * Needs ocr_path GGUF. With a matching mmproj next to it, starts multimodal llama-server.
     * Without mmproj, PdfTools falls back to ML Kit for image OCR.
     */
    private fun startOcr(exe: File) {
        ocrProc?.let { it.destroy(); try { it.waitFor() } catch (_: Exception) {} }
        ocrProc = null
        ocrReady = false
        val ocr = ocrModelFile()
        if (!ocr.isFile) {
            Log.d(TAG, "OCR GGUF not bound — ML Kit fallback only")
            return
        }
        try {
            val mmproj = findMmproj(ocr)
            if (mmproj == null) {
                // Without mmproj, image OCR cannot work — skip starting a second full model (OOM risk)
                Log.i(TAG, "OCR model ${ocr.name} has no mmproj — not starting :8082 (use ML Kit)")
                return
            }
            val args = mutableListOf(
                exe.path, "-m", ocr.path,
                "--host", "127.0.0.1", "--port", "8082",
                "-c", "2048", "-t", "2", "--jinja",
                "--mmproj", mmproj.path
            )
            Log.i(TAG, "OCR vision: ${ocr.name} + mmproj ${mmproj.name}")
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            ocrProc = p
            thread { p.inputStream.bufferedReader().forEachLine { Log.d("llama-ocr", it) } }
            thread {
                repeat(90) {
                    if (!p.isAlive) return@thread
                    try {
                        val c = URL("http://127.0.0.1:8082/health").openConnection() as HttpURLConnection
                        c.connectTimeout = 500; c.readTimeout = 500
                        if (c.responseCode == 200) {
                            ocrReady = true
                            Log.i(TAG, "OCR server ready on :8082")
                            return@thread
                        }
                    } catch (_: Exception) {}
                    Thread.sleep(500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR server failed: ${e.message}")
        }
    }

    fun restartOcr() {
        thread { ensureOcr(timeoutMs = 45_000L) }
    }

    /** Stop OCR server to free RAM (e.g. before loading coder model). */
    fun stopOcr() {
        try {
            ocrProc?.destroy()
            try { ocrProc?.waitFor() } catch (_: Exception) {}
        } catch (_: Exception) {}
        ocrProc = null
        ocrReady = false
        Log.i(TAG, "OCR server stopped")
    }

    /**
     * Start OCR server on demand and wait until healthy or timeout.
     * Returns true if :8082 responds. Safe to call from a worker thread.
     */
    fun ensureOcr(timeoutMs: Long = 60_000L): Boolean {
        if (ocrReady && ocrProc?.isAlive == true) {
            if (probeOcr()) return true
            ocrReady = false
        }
        val ocr = ocrModelFile()
        if (!ocr.isFile) return false
        val exe = File(applicationInfo.nativeLibraryDir, "libllama_server.so")
        startOcr(exe)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ocrProc?.isAlive != true) {
                Log.w(TAG, "OCR process died during ensure")
                return false
            }
            if (probeOcr()) {
                ocrReady = true
                return true
            }
            try { Thread.sleep(400) } catch (_: Exception) {}
        }
        Log.w(TAG, "OCR ensure timed out")
        return ocrReady
    }

    private fun probeOcr(): Boolean {
        return try {
            val c = URL("http://127.0.0.1:8082/health").openConnection() as HttpURLConnection
            c.connectTimeout = 400
            c.readTimeout = 400
            c.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun serverArgs(exe: String, www: String): List<String> {
        val srvKey = if (activeSlot == "coder") "srv_coder" else "srv"
        val s = try { JSONObject(prefs.getString(srvKey, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val currentModel = activeModelFile()
        val a = mutableListOf(exe, "-m", currentModel.path, "--host", "127.0.0.1", "--port", "8080", "--path", www, "--jinja")
        fun num(key: String, flag: String, def: Int) {
            val v = if (s.has(key)) s.optInt(key, 0) else def
            if (v > 0) { a.add(flag); a.add(v.toString()) }
        }
        fun word(key: String, flag: String) {
            val v = s.optString(key, "").trim()
            if (Regex("[a-z0-9_]{1,12}").matches(v)) { a.add(flag); a.add(v) }
        }
        // Coder default 8k — 32k KV on a 7B model is a common OOM on 12GB phones
        val defaultCtx = if (activeSlot == "coder") 8192 else 8192
        num("ctx", "-c", defaultCtx); num("threads", "-t", 4); num("threads_batch", "-tb", 0)
        num("batch", "-b", 0); num("ubatch", "-ub", 0); num("parallel", "-np", 1)
        // Flash attention + q8_0 K/V cache by default: roughly half the KV memory at near-identical quality.
        // A quantized V cache REQUIRES flash attention, so FA is forced on whenever V is quantized.
        // Anything set explicitly in Settings (even "off"/"f16") wins over these defaults.
        val safe = safeKv || prefs.getBoolean("kv_safe", false)
        fun pick(key: String, def: String): String {
            val v = s.optString(key, "").trim().lowercase()
            return if (Regex("[a-z0-9_]{1,12}").matches(v)) v else def
        }
        val ctk = pick("ctk", if (safe) "f16" else "q8_0")
        val ctv = pick("ctv", if (safe) "f16" else "q8_0")
        var fa = pick("fa", if (safe) "auto" else "on")
        if (ctv != "f16" && ctv != "f32" && ctv != "bf16" && fa == "off") fa = "on"
        kvDefaultsUsed = !safe && (s.optString("fa", "").isBlank() || s.optString("ctk", "").isBlank() || s.optString("ctv", "").isBlank())
        a.add("-fa"); a.add(fa); a.add("-ctk"); a.add(ctk); a.add("-ctv"); a.add(ctv)
        if (s.optBoolean("mlock", false)) a.add("--mlock")
        if (s.optBoolean("no_mmap", false)) a.add("--no-mmap")
        s.optString("extra", "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { a.add(it) }
        return a
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL_ID, "Quarry model server", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the on-device model running while you use other apps"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun promoteForeground(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LlamaService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Quarry")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(0, "Stop", stop).build())
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "quarry:fgs")
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire(4 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    companion object {
        private const val TAG = "LlamaService"
        const val CHANNEL_ID = "quarry_server"
        const val NOTIF_ID = 42
        const val ACTION_START = "com.quarry.app.START_SERVER"
        const val ACTION_STOP = "com.quarry.app.STOP_SERVER"

        fun start(ctx: Context) {
            val i = Intent(ctx, LlamaService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LlamaService::class.java).setAction(ACTION_STOP))
        }
    }
}
