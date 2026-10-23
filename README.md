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

## Build notes

1. Drop a `libllama_server.so` (and related llama.cpp Android build) into `jniLibs` the same way Kojot does.
2. Place GGUF paths via the service prefs keys `model_path` / coder path (see `LlamaService.resolveModel`).
3. Open the `android/` folder in Android Studio, sync Gradle, run on device.
4. For pure web use, open `www/index.html` (or the top-level `artifacts/quarry.html`) in a modern browser — search works without the native shell.

## Files

- `www/index.html` / `artifacts/quarry.html` — upgraded single-file app
- `android/.../LlamaService.kt` — adapted from Kojot
- `android/.../MainActivity.kt` — WebView + bridge
