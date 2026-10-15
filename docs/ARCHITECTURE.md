# Bro.exe — Architecture (voice prototype)

Bro.exe is a voice-first, fully on-device Android assistant. Its "brain" is a local
GGUF model (LiquidAI LFM2.5-350M, Q4_K_M) run through a vendored llama.cpp binding;
speech in/out use vendored sherpa-onnx models. Nothing routes to OpenAI, OpenCode, or
any remote LLM. The only network use is the optional DuckDuckGo web-search tool.

## Layers

```
UI (Compose)                     ui/AppRoot.kt           one switch + one status line
  │  collect StateFlow
Foreground service               voice/VoiceService.kt   FOREGROUND_SERVICE_MICROPHONE
  │                                                       persistent "Stop" notification
  └── voice/VoiceSession.kt      half-duplex loop, one engine resident at a time
        ├── voice/AudioIO.kt        AudioRecord 16 kHz mono  ↔  AudioTrack float PCM
        ├── voice/SpeechEngines.kt  WakeWordDetector (KWS) · CommandTranscriber (ASR) · Speaker (TTS)
        ├── voice/LlmResponder.kt   wraps :llama InferenceEngine; per-turn load/prompt/answer/unload
        ├── voice/WebSearch.kt      DuckDuckGo HTML(one tool) + DuckDuckGoParser
        ├── voice/SearchTrigger.kt  explicit "search for…" routing
        └── voice/VoicePaths.kt     one-time APK-asset extraction into filesDir/voice

:vendor modules
  :llama    thin JNI binding over third_party/llama.cpp (pinned SHA, arm64-only)
  :sherpa   vendored sherpa-onnx Kotlin API over libsherpa-onnx-jni.so + libonnxruntime.so

Bundled assets (extracted on first launch, never committed):
  models/lfm2.5-350m-q4_k_m.gguf · models/kws/* · models/asr/* · models/tts/* · keywords.txt
```

## Voice pipeline (one turn)

1. `VoiceService` starts in the foreground with a microphone-type notification.
2. `VoicePaths` extracts the bundled models once (staged, then atomically renamed).
3. Loop: `WakeWordDetector` listens for **"Hey Bro"** → `CommandTranscriber` captures
   one utterance until sherpa endpoint detection (or a 12 s cap) → `LlmResponder`
   loads the LLM, sets the spoken-voice system prompt, answers → `Speaker` (Piper
   VITS) synthesizes → `AudioTrack` plays → re-arm.
4. Explicit search phrases route through `WebSearch`; results are rendered as notes
   and the model answers with a search-specific prompt.
5. Engines are leased sequentially — at most one model is resident at a time. The LLM
   is unloaded after each answer (memory-safe on a 3 GB device).

## Memory / 3 GB policy
- Sequential engine lease; never two LLMs at once.
- LLM context 1024, threads bounded (LLM 4, ASR/TTS 2, KWS 1).
- Bounded audio frames (100 ms), bounded command length, bounded answer length.
- `START_NOT_STICKY`: the mic is never resurrected behind the user's back.
- Extraction gated on free space (`StatFs`) with a completion marker.

## Build & release
- CI (`.github/workflows/android-build.yml`) is the only build path: pinned
  llama.cpp + sherpa-onnx + verified asset download → unit tests → debug APK →
  content + signature verification → versioned artifact with commit/run evidence.
- Debug builds are signed with a committed, non-secret debug key so versions install
  over each other (see `keystore/README.md`).
- No model binaries, native `.so`, or secrets are committed; `third_party/` and the
  staged `assets/voice/` tree are git-ignored.
