# Voice Prototype — Audit & Implementation Plan

Status: **IN PROGRESS** — audit done, implementation not started. Nothing here is
"working" until CI and the Oppo A18 say so.

Repos inspected:

- `projects/bro-exe-android` → GitHub `Elthon-dev/autonimous-v0.1`, branch `master`.
  This is the app the user calls **Bro.exe** (`com.elthon.broexe`). It is a "giant
  assistant" (chat UI, tasks, memory, reminders, observer, worker, proactive engine)
  built on an on-device Qwen3.5-0.8B engine. Latest verified build: 69 tests, native
  APK, run 38252678417. Backup branch created: `backup/assistant-phase2`.
- `Elthon-dev/Heybro` (`com.vibeagent.dude`, Flutter + Kotlin) — an older, unrelated
  voice-automation app (cloud Gemini, Picovoice). It is **not** the Bro.exe prototype
  and is **not** the target of this work. Kept untouched.

Junk found on the machine that is unrelated and NOT touched: `needle-android/` (a
Termux-side llama server), `mr-braincells`, `time-warp`, `hass`, etc.

## 1. Root-cause audit of the broken Bro.exe prototype

Evidence is from reading the repo, not guessing.

1. **The `“thinking”` output.** `llm/BroLlm.kt:143` streams every token from the
   native engine straight into the UI (`emit(token)`) with **no filtering, no stop
   tokens, no think-block handling**. The engine prompts via the GGUF chat template
   (`common_chat_templates_init`). The bundled model `Qwen3.5-0.8B-Q4_0` is a
   **reasoning** model whose template emits ` thinking`…`/system>`-style blocks. The
   prompt instructs the model to emit `<<BROCTL>>` JSON too (`agent/BroPrompts.kt`).
   So the UI renders raw reasoning markers — and on a 3 GB phone generation often
   dies when the huge think-budget is consumed, leaving literally only the think
   header as “the answer”.
2. **No wake word.** `rg "wake|sherpa|porcupine|keyword"` over `app/src/main` finds
   nothing except a comment. `voice/` contains only a stub `VoiceController`.
3. **No offline STT/TTS.** Speech recognition/synthesis were never integrated.
4. **No microphone service.** No `FOREGROUND_SERVICE_MICROPHONE`, no `RECORD_AUDIO`
   in the manifest of this prototype.
5. **Too big, loaded wrong, downloaded late.** Qwen3.5-0.8B is ~563 MB and was
   *downloaded at runtime* (not bundled), i.e. the phone does the download *and* the
   inference. There is no adaptive load/unload; engines pile up. This matches the
   lag + crash reports.
6. **`thinking` is not cosmetic.** Fixing it by regex-stripping the token string is a
   band-aid; the real fix is the model/template/format decision (below).

Decisions implied by the user’s master prompt that this plan locks in:

- Keep the Bro.exe identity/package ID `com.elthon.broexe` (it preserves upgrade
  compatibility with what is installed).
- The voice prototype **replaces** the giant-assistant surfaces in this repo.
  Backup branch `backup/assistant-phase2` preserves history. Everything not needed
  by the voice-first app is removed from `master` builds.
- No cloud AI, no API keys, no OpenAI/Gemini. Only optional web search (DuckDuckGo
  HTML) touches the network.

## 2. Verified model/asset facts (web-verified, not assumed)

### Local language model

- **Model**: `Qwen/Qwen2.5-0.5B-Instruct-GGUF` → `qwen2.5-0.5b-instruct-q6_k.gguf`
  - HF LFS object size **650,379,104 bytes** (≈620 MiB).
  - GGUF architecture **`qwen2`**; context 32k (we use **2048**); chat template is
    ChatML (`<|im_start|>` / `<|im_end|>`). **Non-reasoning**: the template does not
    prefill a ` thinking` block, so generation starts in the answer.
  - Verified LFS SHA-256 (re-checked in CI before extraction is trusted):
    `2f82233630c349ccf6b8daccf48f9a7865713d9f08a2eadfa456cebe9b97c7f5`
  - Recommended sampling (Qwen2.5-Instruct): temp 0.7, top_p 0.8, top_k 20,
    repetition_penalty 1.05. Note the native binding decodes **greedily** and decoding
    is scale-invariant, so temp/top_p/top_k are no-ops; only penalties change output.
- **License**: **Apache-2.0** — redistribution inside the APK is permitted; the
  LICENSE text ships as an asset (`licenses/Qwen2.5-0.5B-Instruct-Apache-2.0.txt`).
- **Runtime**: llama.cpp arch `qwen2` is long-supported; the pin stays
  **`358a420ec4ed59b6b0be08b1d1f71dd478b99a99`**.
- This upgrades the earlier Q4_K_M build (491,400,032 B) to **Q6_K** for higher
  answer quality at ~+159 MB, still RAM-safe on the ~3 GB target device. It also
  supersedes the historic LiquidAI LFM2.5-350M candidate, which is no longer shipped.

### Offline speech assets (all English; user language support limited to English in
this milestone — Filipino/Cebuano NOT provided, documented honestly)

- **Wake word** (KWS):
  - Model tarball (official release asset, not invented):
    - URL: `https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2`
    - ~3.3M params; contents: encoder/decoder/joiner `.onnx` (+ int8), `tokens.txt`,
      `bpe.model`. Apache-2.0 family.
    - **Keywords are supplied at runtime** (no retraining): build a `keywords.txt`
      with the phrase **HEY BRO** using the official `sherpa-onnx-cli text2token`
      (tokens-type bpe). Verify detection with test audio in CI where possible.
- **Speech recognition** (ASR, streaming transducer):
  - Files from `csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26`, Apache-2.0:
    - `encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx` (71,083,163 B)
    - `decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx` (1,307,236 B)
    - `joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx` (259,335 B)
    - `tokens.txt` (5,048 B)
    - int8 chosen to save RAM (~73 MB total); upgraded from the tiny 20M-2023-02-17
      model, which was the root cause of poor recognition accuracy.
- **Speech output** (TTS, Piper via sherpa-onnx):
  - `csukuangfj/vits-piper-en_US-ryan-low` (sherpa-onnx **converted** model; male voice). The raw
    `rhasspy/piper-voices` `.onnx`+`.onnx.json` is NOT loadable by sherpa-onnx — VITS
    needs `tokens.txt` and an `espeak-ng-data/` directory alongside the model.
  - Bundled from the `sherpa-onnx` release asset `vits-piper-en_US-ryan-low-int8.tar.bz2`
    (21,212,659 B; int8 chosen to save ~48 MB vs. the fp32). Extracted tree:
    `en_US-ryan-low.onnx` (18,657,565 B int8), `en_US-ryan-low.onnx.json`, `tokens.txt`,
    `espeak-ng-data/**` (355 files), `MODEL_CARD`. License: Piper is MIT; verify text.
  - On-device config: `OfflineTtsVitsModelConfig(model=…/en_US-ryan-low.onnx,
    tokens=…/tokens.txt, dataDir=…/espeak-ng-data)`.
- **Inference engine** for all three: **sherpa-onnx v1.13.8** (2026-09-10),
  packaged for Android via the release `sherpa-onnx-v1.13.8-android.tar.bz2`
  (46,093,321 B; arm64 JNI + Java API). Approach: vendor into a `:sherpa` module
  (same pattern as the `:llama` module) rather than a gradle coordinate, so the
  build is isolated and pinned.

## 3. Target experience

Single screen: **Bro.exe** title, one **Voice Mode ON/OFF** switch (the only
interactive control), one truthful status label. States:

`Off · Requesting permission · Starting voice engine · Loading local AI ·
Listening for “Hey Bro” · Listening to your request · Thinking · Searching the web ·
Speaking · Recovering from an error · Voice Mode stopped by the system`

Flow: switch ON → permissions (RECORD_AUDIO, POST_NOTIFICATIONS) → foreground mic
service → wait for wake word → on “Hey Bro” → brief audio cue → capture until
end-of-utterance (VAD, no fixed cooldown) → local STT → local LLM decides
ANSWER / ASK / WEB_SEARCH / ANSWER_FROM_RESULTS → (optional) DuckDuckGo HTML search →
concise final answer → local TTS → half-duplex: re-arm wake word after speech
finishes. Short follow-up window without wake word; silent timeout returns to
wake-word-only. Switch OFF tears everything down (stop mic, cancel generation/web,
stop TTS, release contexts, remove notification).

## 4. Architecture / memory policy (3 GB phone)

Single audio pipeline. **Sequential engine lease**, never all-at-once:

- idle: KWS model resident only (small).
- on wake: release KWS → init streaming ASR; transcribe; release ASR.
- LLM: load (620 MB Q6_K), ctx 2048, threads measured (start 4), max new tokens
  ~96–128, cancel on mode-off; release after answer (or retain only if measurements
  prove it safe).
- TTS: load ~19 MB Piper int8 only to synth/speak, then release. No synthetic delay.
- Bounded buffers, bounded final-answer length, `onTrimMemory`, no `System.gc()`
  as strategy, storage check before asset extraction, atomic completion marker,
  partial-failure cleanup, truthful statuses.

Estimated APK: ~230 MB LLM + 44 MB ASR + ~7 MB KWS + 63 MB TTS + native libs
(llama ~15 MB, sherpa-onnxruntime ~35 MB) ≈ **~400 MB**. Installed footprint ≈
assets (compression-limited) + extracted models + free room. These WILL be
measured and reported, not assumed.

## 5. Web search (single tool)

- Tool `web_search(query)`: DuckDuckGo HTML endpoint (`html.duckduckgo.com/html`),
  bounded timeouts (~10 s), max ~5 result records (title, url, snippet, domain),
  cancellation, sanitization; provider-block/rate-limit/offline handled gracefully
  (answer says it could not verify; optionally offers opening the browser to the
  query). HTTPS only. Never executed server-side, never curated/demo results.
- Only ONE tool; only the LLM (or, for explicit search requests, a narrow recorded
  fallback) decides to call it. No other side-effecting tools exist in this milestone.

## 6. Git/GitHub wiring

- Existing remote stays `Elthon-dev/autonimous-v0.1`. CI workflow
  `.github/workflows/android-build.yml` updated: pinned llama.cpp + sherpa-onnx +
  asset-download/verify script → build arm64 → unit tests → assembleDebug → upload
  `bro-exe-debug-apk`. Assets are downloaded on the runner, never committed, never
  downloaded on the Oppo. LFM LICENSE + sherpa/Piper attribution bundled.

## 7. Implementation sequence (execution priority per master prompt)

0. **Backup + plan** (this doc; backup branch created). ✅ done
1. **Native smoke test** (CI, linux-x86 runners). ✅ done
   - Host smoke now exercises the shipped model: download
     `qwen2.5-0.5b-instruct-q6_k.gguf`; verified bytes 650,379,104 and SHA-256
     `2f82233630c349ccf6b8daccf48f9a7865713d9f08a2eadfa456cebe9b97c7f5`.
     (The earlier LiquidAI LFM2.5-350M `229,312,224`/`7e6f7264…` run is superseded.)
   - Matrix result (6 combos): `tmpl_special` + `bos_special` prompt modes answer
     cleanly ("Paris is the capital of France."). `tmpl_auto` (add_special=true)
     repaints "The assistant's response is:" — root cause = auto-BOS token.
   - **JNI/tokenization contract for the binding**: tokenize the model's rendered
     ChatML template with `add_special=false`, `parse_special=true`; never rely on
     auto-BOS (Qwen-vocab BOS is `<|im_start|>`, doubles the role header and
     breaks a 350M). Stop generation on EOS / `<|im_start|>` / `<|im_end|>`.
   - Host timings (2 threads): load ~135 ms, first token ~150 ms, ~370 ms per
     short answer. Greedy = `top_k(80),top_p(0.95),temp(0.6),penalties(.,64,1.05,0,0)`
     then greedy; sampler order confirmed via header comment.
2. Re-pin `:llama` NDK module to the same llama.cpp commit. ✅ done
   - Workflow clones `358a420ec4ed59b6b0be08b1d1f71dd478b99a99` (SHA, not tag);
     `:llama` default ctx 1024; arm64-only.
   - `ai_chat.cpp` rewritten on the proven contract (model chat template,
     `parse_special=true`/`add_special=false`, proven sampler chain, hard stop on
     EOG/role tokens), dropping fragile `llama.cpp/common` helpers. JNI surface
     unchanged, so `InferenceEngineImpl.kt` compiles as-is.
   - Example of `benchModel`/system-format UIs survive only until step 7.
3. Vendor `:sherpa` module from v1.13.8 android tarball; build token for KWS/ASR/TTS. ✅ done
   - Vendored Kotlin API (KWS/OnlineRecognizer/OnlineStream/Tts + config classes)
     from the v1.13.8 tag; `:sherpa` builds `libsherpa-onnx-jni.so` +
     `libonnxruntime.so` from the release tarball (never committed), arm64 only.
     `:sherpa:assembleRelease` green in CI.
4. Asset pipeline: manifest + fetch/verify script, wake-word keyword generation,
   license bundling. ✅ done (bundling into the APK + first-launch extraction in step 7)
   - `scripts/voice_assets.manifest` lists the LLM, wake-word KWS, streaming ASR,
     Piper TTS and the LFM license with their URLs.
   - `scripts/fetch_voice_assets.sh` downloads every asset, extracts tar members,
     computes sha256+tty size, and verifies against `scripts/voice_assets.lock`
     (generated once on CI, committed) — or regenerates the lock with `generate`.
   - Wake word keywords generated with `sherpa-onnx-cli text2token` (v1.13.8 wheel)
     from the KWS `bpe.model`: `HEY BRO` → `▁HE Y ▁B RO`, committed as
     `scripts/voice_keywords.txt`. 
   - Verified end-to-end: `assembleDebug` packages all 371 locked files into the
     APK (`Verify APK contents` step asserts each expected entry is present). Debug
     APK ≈ 388 MB (TTS-int8 revision); native libs present: `libllama.so`,
     `libggml*.so`, `libai-chat.so`, `libsherpa-onnx-jni.so`, `libonnxruntime.so`.
5. Voice state machine + foreground microphone service (start from visible
   activity; legal service type + permissions; stop action; recovery). ✅ core done
   - `VoicePaths` extracts the 371 bundled model files once into app storage
     (staged in `voice.tmp`, renamed atomically; `StatFs` free-space gate).
   - `AudioInput`/`AudioOutput`: 16 kHz mono capture, float PCM playback.
   - `WakeWordDetector`/`CommandTranscriber`/`Speaker` wrap the vendored sherpa API;
     `VoiceState` is the single truthful status enum.
   - `VoiceService` is a foreground **microphone** service with a persistent Stop
     notification and `START_NOT_STICKY`.
6. Orchestrator: wake → STT → LLM decide → (search) → answer → TTS → re-arm. ✅ core done
   - `VoiceSession` runs the half-duplex loop with sequential engine leases and a
     12 s command cap; every error is surfaced and the loop re-arms.
   - `LlmResponder` waits (bounded) for native init, loads the model, installs the
     spoken-voice system prompt, and answers per turn (engine is stateless/turn).
   - Web search: `WebSearch` (DuckDuckGo HTML, 10 s timeouts, ≤5 results, cancellable),
     `DuckDuckGoParser` (pure, unit-tested), `SearchTrigger` (explicit "search for /
     look up / google / find" phrases). Results are rendered as compact notes and the
     model answers with `SEARCH_SYSTEM_PROMPT`; no results → answers locally. ✅ done
7. Single-switch UI; strip giant-assistant surfaces from the build. ✅ done
   - `AppRoot` is one screen: switch + status line; `VoiceService` is bound to it.
   - Removed agent, data (Room), domain, engine, model manager, observer, reminders,
     tools, worker, all screens/viewmodels + their tests; dropped Room/Work/DataStore/
     navigation/ksp. Manifest keeps only mic/foreground/notifications/net.
8. Unit tests + updated CI; run and fix until green. ✅ done
   - `DuckDuckGoParserTest`, `SearchTriggerTest`, `VoiceStateTest` (pure JVM).
   - `Unit tests + debug APK`, `Native LLM model smoke test`, and `Generate voice
     asset lock` all green on run `38381876411`.
9. Artifact verification (sizes, checksums, permissions, presence of assets) +
   `docs/VOICE_TEST_PLAN.md` executed on device. **Pending: needs the Oppo A18.**

## 8. Known unknowns to resolve with evidence before claiming anything

- Exact JNI surface of sherpa-onnx v1.13.8 Android tarball (Kotlin class names /
  constructor config, `AudioRecord` helper) — resolved in step 3 by reading the
  vendored classes.
- Qwen2.5-0.5B real-world reliability for tool decisions + spoken brevity on-device —
  Milestone 1 + device test; expect tuning.
- KWS detection threshold for “HEY BRO” — needs real audio (CiA fixtures help) and
  device tuning; false-positive/miss rates will be recorded.
- Actual phone RAM headroom with sequential leases — measured on device.
- DDG HTML stability — handled with graceful degradation.

Hard limits honored: no OpenAI key/cloud inference/login; no second LLM; no
arbitrary model-issued commands (only `web_search`); no mic capture when OFF; no
cleartext networking; no accessibility/camera/location/contacts/SMS in this
prototype; nothing claimed functional until CI + device evidence.