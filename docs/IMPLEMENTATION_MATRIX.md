# Bro.exe — Implementation Matrix

Honest status per capability of the **voice prototype** (the giant assistant was
removed in milestone 7; see `backup/assistant-phase2` for its history). Legend:

- **CI** — implemented + exercised by automation (unit tests / build checks).
- **COMPILED** — implemented; type-checks and builds, but never executed on hardware.
- **REVIEW** — verified only by reading the code.
- **TODO** — not implemented.
- **DEVICE** — needs the Oppo A18 to confirm.

| Capability | Status | Evidence / notes |
|---|---|---|
| Single-switch voice UI | COMPILED | `ui/AppRoot.kt`: one Voice-Mode switch + diagnostics panel (live mic meter, signal verdict, counters, event log). |
| On-device diagnostics + stage self-tests | COMPILED | `VoiceDiagnostics` snapshot/event log; `VoiceSelfTest` runs MIC/WAKE/ASR/AI/TTS/FULL against the real engines. DEVICE. |
| Foreground microphone service | COMPILED | `voice/VoiceService.kt`; `foregroundServiceType="microphone"`, `START_NOT_STICKY`, Stop action, `ACTION_TEST` stage runner. |
| Permission flow (RECORD_AUDIO, POST_NOTIFICATIONS) | COMPILED | Launcher in `AppRoot`; service re-checks mic grant. |
| One-time asset extraction | REVIEW | `VoicePaths`: staged tmp→rename, `StatFs` gate, version marker. |
| Mic capture (16 kHz mono, float) | COMPILED | `AudioIO.AudioInput` (`AudioSource.MIC`); `STATE_INITIALIZED`/`RECORDSTATE_RECORDING` checked, negative reads throw `AudioCaptureException`. |
| Wake word — multi-trigger KWS (legacy, optional) | COMPILED | Kept but no longer on the hot path. Four triggers: Hey Bro / Bro / Yo Bro / Okay Bro (`WakeConfig` → `keywords.active.txt`). Off by default; `DetectionConfig.keywordHint` uses it only as a small confidence nudge. DEVICE. |
| Open-vocabulary activation (no wake phrase) | CI (logic) + COMPILED | `AddressingDetector` scores addressing intent from linguistic cues (vocative, frames, meta-requests, imperatives, questions, openers); `ActivationDecider` bands + refractory + dedup; `ConversationSession` follow-up/idle. `VoiceSession` runs the continuous VAD→ASR→classify→decide loop. Unit-tested; acoustic accuracy unmeasured. DEVICE. |
| "bro" inside ordinary sentences | CI (logic) | Third-person "bro" (possessive before, verb after) is rescaled to 0.3, so "my bro said…"/"bro is cool" stay low. Unit-tested. DEVICE. |
| Voice activity detection (VAD) | CI (logic) + COMPILED | `VoiceActivityDetector` energy VAD, adaptive noise floor + hysteresis; unit-tested. DEVICE. |
| Two-stage wake verification | CI (logic) + COMPILED | `WakeVerifier` energy gate + refractory window (legacy KWS path); unit-tested. DEVICE. |
| Trigger-stripped request extraction | CI + COMPILED | `RequestExtractor` keeps pre-trigger words; unit-tested and used in `VoiceSession`. DEVICE. |
| Crash capture / diagnostics | CI (logic) + COMPILED | `CrashReporter` (uncaught handler + `ApplicationExitInfo` history) → `last_crash.txt`, shown in diagnostics + report. Root cause unconfirmed. DEVICE. |
| Streaming speech recognition (ASR) | COMPILED | `CommandTranscriber` (zipformer en-20M int8), greedy, endpoint on; `result()` + confidence (`ysProbs`) exposed. DEVICE. |
| Local LLM inference (llama.cpp) | COMPILED | `:llama` + `LlmResponder`; pinned commit + GGUF SHA verified by the smoke test. DEVICE. |
| Local TTS (Piper VITS) | COMPILED | `Speaker` (en_US-ryan-low int8, male + espeak-ng-data). DEVICE. |
| On-device answer quality | COMPILED | Model answers proven on the host smoke test; phone timing/quality unverified. DEVICE. |
| Web search tool | CI (parser/trigger) | `WebSearch` + `DuckDuckGoParser` (unit-tested) + `SearchTrigger` (unit-tested). Live network call unverified. DEVICE. |
| Search decision by the model | TODO | Search fires only on explicit phrases, not on model judgement. |
| Conversation context across turns (LLM) | TODO | Engine clears KV and re-decodes each turn (stateless per turn). |
| Multi-turn session (activation) | CI (logic) + COMPILED | `ConversationSession` keeps a session open for follow-ups (8 s) and closes it on idle (30 s); unit-tested. DEVICE. |
| Barge-in / interruption | CI (logic) + COMPILED | Cancellable chunked `AudioOutput.stop()`; while speaking, elevated-threshold VAD catches the interruption and captures it as a new turn. AEC best-effort. DEVICE. |
| Pre-roll buffer (pre-trigger audio) | CI (buffer) + COMPILED (wiring) | `AudioRingBuffer` unit-tested; `VoiceSession` primes the recognizer with ~1.5 s. DEVICE. |
| Follow-up window without wake word | CI (logic) + COMPILED | `ConversationSession` follow-up window; ambiguous utterances activate in-session. DEVICE. |
| Tunable detection settings | CI (logic) + COMPILED | `DetectionConfig` + `DetectionConfigStore` (persisted); UI `DetectionSettingsSection`. DEVICE. |
| Error recovery + re-arm | COMPILED | `VoiceSession.run` retries a failed turn after 800 ms without tearing down voice mode. |
| Resource release on switch OFF | COMPILED | `VoiceSession.stop` + service teardown; audio/model released. DEVICE (timing). |
| Data-safe upgrades | CI | Committed debug keystore → identical signer across runs (`eb79f4b0…`). |
| APK asset/signature verification | CI | "Verify APK contents" + "Verify APK signature" steps. |
| Versioned, immutable artifacts | CI | `bro-exe-v<version>-<sha7>-debug.apk` + `BUILD_INFO.txt` + `.sha256`. |
| English only | PARTIAL | Filipino/Cebuano not provided. |
| Extraction progress UI | TODO | Shows a status word only. |
