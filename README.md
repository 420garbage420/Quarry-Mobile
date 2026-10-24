# Quarry Mobile

Research agent UI (upgraded Quarry) + on-device **llama.cpp** server, following the Kojot pattern.

## What changed vs stock Quarry (web)

### UI (theme patches)
- Dark palette closer to Gemini / Deepseek shells (`#121317` basalt, accent blue, softer surfaces)
- Larger touch targets, raised cards, safer area padding for phones
- Slot chip hooks for native model routing

### Web stack (Agentmem-style, Tavily-compatible)
Tool names stay `tavily_search` / `extract` / `map` / `crawl` so agent prompts keep working.

| Tool | With Tavily key | Without key (default) |
|------|-----------------|------------------------|
| search | Tavily API | DuckDuckGo HTML → optional light extract of top hits |
| extract | Tavily extract | SSRF-guarded `fetch` → HTML stripped to text |
| map | Tavily map | Same-host link harvest from one page |
| crawl | Tavily crawl | Bounded BFS (depth ≤ 3, limit ≤ 30) via local extract |

SSRF guard blocks localhost / private / link-local / CGNAT hostnames (browser-side; same idea as Agentmem’s DNS check).

### Model routing (Kojot idea)
`ModelRouter` in the page talks to `window.QuarryNative` / `window.KojotBridge`:

- `swapModel('chat'|'coder'|'vision')` → native `LlamaService.swapModel`
- Soft-swap before forge/python vs research vs OCR-style tools
- Hosted OpenAI-compatible profiles still work as before (OpenRouter, Ollama, etc.)

## Architecture

```
┌─────────────────────────────────────────┐
│  MainActivity (WebView)                 │
│   assets/www/index.html  ← Quarry UI    │
│   JS bridge: QuarryNative.swapModel()   │
└───────────────┬─────────────────────────┘
                │ bind (does not kill on destroy)
┌───────────────▼─────────────────────────┐
│  LlamaService (foreground, sticky)      │
│   llama-server  :8080  chat/coder       │
│   embed server  :8081                   │
│   OCR (optional):8082  lazy + mmproj    │
└─────────────────────────────────────────┘
```

Point Settings → base URL `http://127.0.0.1:8080/v1`, model name whatever the server reports (or `local`). No API key.

## Local model loading (Kojot-style)

Models stay on disk — **no multi-GB copy** into app storage. Paths are stored in SharedPreferences and passed straight to `llama-server`.

### Ways to bind a GGUF

1. **Folder auto-bind (default)**  
   Drop files under `Download/Local Models/` (created on first run). Optional category subfolders:
   - `llm/` or `decision/` → chat slot  
   - `coder/` → coder slot  
   - `embed/` → embedding server (`:8081`)  
   - `ocr/` or `image/` → vision/OCR (`:8082`, lazy)  
   On launch, empty slots are filled from the first readable `.gguf` in those folders (mmproj files are ignored).

2. **In-app list (preferred on Samsung)**  
   Bridge ops `list_models` / `select_model` walk the models folder and bind by real path + slot (`chat` | `coder` | `emb` | `ocr` | `auto`).

3. **System SAF picker**  
   `pick_model` / `pick_coder` / `pick_embedding` / `pick_ocr` open the document picker. Native code resolves a real filesystem path (DocumentsContract + MediaStore + name search under Local Models). If the path cannot be resolved, the UI refuses a silent multi-GB copy and points you at the folder method.

4. **Custom models root**  
   `set_models_dir` / `get_models_dir` change the scan root (default remains `Download/Local Models`).

### Bridge surface (`window.QuarryNative` / `KojotBridge`)

| Call | Purpose |
|------|---------|
| `info()` / `health()` | Bound paths, readiness, slots |
| `call(id, op, argsJson)` | Async ops → `__quarryReply(id, ok, payload)` |
| ops | `list_models`, `select_model`, `pick_*`, `delete_*`, `auto_bind`, `set_models_dir`, `grant`, `swap_model`, `restart` |
| `swapModel(slot)` | Soft-swap chat ↔ coder (service stays alive) |
| `localPreset()` | `{ baseUrl: http://127.0.0.1:8080/v1, model: local }` |

Grant **All files access** (`grant` op) so external GGUFs remain readable after reboot.

## Icon

Adaptive launcher uses a **voxel quarry cut** (stone blocks + verdigris ore vein) on basalt `#121317` — distinct from Kojot’s coyote.

## Build (GitHub Actions / local)

The included **GitHub Actions workflow** (`.github/workflows/build-apk.yml`) produces a debug APK with **no extra installation** for the native servers:

1. Downloads the official `llama-*-bin-android-arm64.tar.gz` from a pinned [llama.cpp release](https://github.com/ggml-org/llama.cpp/releases).
2. Places `llama-server` as `jniLibs/arm64-v8a/libllama_server.so` (plus its shared libs) so `LlamaService` finds and launches it from `nativeLibraryDir`.
3. Runs `./gradlew assembleDebug` and uploads the APK as a workflow artifact.

**You still need to supply GGUF model files** on the device (chat / coder / embed / vision). The service resolves paths from SharedPreferences (`model_path`, coder path, etc. — see `LlamaService.resolveModel`). Models are not bundled (they are large).

### Local build

```bash
cd android
# Optional: pre-populate jniLibs yourself, or let the workflow do it
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK platform 34 + build-tools, NDK not required (prebuilts only).

### Manual native libs (if not using CI)

Drop the contents of an Android arm64 llama.cpp binary package into `android/app/src/main/jniLibs/arm64-v8a/`, renaming the server binary:

```bash
cp llama-server app/src/main/jniLibs/arm64-v8a/libllama_server.so
cp lib*.so app/src/main/jniLibs/arm64-v8a/   # the supporting shared objects
```

Then open `android/` in Android Studio or run Gradle as above.

## Keyless search + deep_fetch integration

The free path (no Tavily key) uses DuckDuckGo HTML + SSRF-guarded fetch + HTML-to-text.

### On-device dynamic extract (deeper integration)

When running inside the Android shell, Quarry has a native bridge that mirrors deep_fetch’s CDP path:

| Piece | Role |
|-------|------|
| `MainActivity.deepExtract(url)` | Off-screen WebView navigates the URL, waits for load + settle, returns post-JS `innerText` |
| Native SSRF guard | Same public-host checks as the JS side (blocks localhost / private / CGNAT) |
| `nativeDeepExtract()` in JS | Thin async wrapper around the bridge |
| `ssrfFetch(..., { dynamic: true })` | Prefers native WebView; also auto-upgrades when static HTML yields &lt; ~280 chars |
| `WebStack.search` (advanced) | Enrichment uses dynamic extract for top hits |
| `WebStack.extract` (advanced / `dynamic:true`) | Uses native path when available; provider becomes `local-dynamic` |

Plain browser `fetch` remains the fast default. Dynamic is used for:

- `search_depth: "advanced"` enrichment
- `extract_depth: "advanced"` or explicit `dynamic: true`
- Heuristic fallback when static text is nearly empty (typical SPA shell)

### Companion script

`deep_fetch.py` remains useful on desktop / CI:

- Pure asyncio wire client + optional Chrome CDP
- Zero pip deps — drop next to the project or into a research VM
- Same mental model: wire for speed, CDP/WebView for rendered DOM

Keep tool names Tavily-compatible either way.

## Files

- `www/index.html` — upgraded single-file app (also copied into `android/.../assets/www/`)
- `android/.../LlamaService.kt` — adapted from Kojot (foreground service, model slots, health)
- `android/.../MainActivity.kt` — WebView + `QuarryNative` bridge + **deepExtract**
- `.github/workflows/build-apk.yml` — CI that bundles llama-server and produces the APK
- `deep_fetch.py` — optional dual-engine fetcher for desktop / stronger keyless extract
