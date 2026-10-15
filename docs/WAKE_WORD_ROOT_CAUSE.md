# Wake-Word Root-Cause Analysis — “Hey Bro” does nothing

Status: investigation complete, fixes implemented in this change set (v0.4.0).
Scope: the installed `com.elthon.broexe` prototype where Voice Mode turns on, the
notification reads *Listening for “Hey Bro”*, and saying the wake word never fires.

This document is the evidence trail. Every claim below was checked against the
vendored sherpa-onnx sources, the official KWS Android sample, and the actual
bundled model files — not assumed.

---

## 1. What the pipeline does

`VoiceSession.run()` → `runTurn()` (half-duplex, one engine resident at a time):

```
LISTENING_WAKE  → AudioInput.start(); loop { frame = readFrame(); preRoll.add; kws.accept; poll }
WAKE_DETECTED   → (kws.poll returns a keyword)
LISTENING_COMMAND → AudioInput.start(); loop { frame; asr.accept; text; isEndpoint }
THINKING        → LlmResponder.answer()
SPEAKING        → Speaker.synthesize() + AudioOutput.play()
```

The persistent notification shows `VoiceState.label()`; `LISTENING_WAKE`’s label is
`Listening for “Hey Bro”`.

---

## 2. Things that were verified *correct* (ruled out)

| Suspect | Verdict | Evidence |
|---|---|---|
| Keyword file format | **Fine** | `scripts/voice_keywords.txt` is `▁HE Y ▁B RO\n` (16 B). The model’s own `keywords.txt` uses the identical convention (`▁HE LL O ▁WORLD`, …). All four tokens (`▁HE`, `Y`, `▁B`, `RO`) exist verbatim in the model’s `tokens.txt` (verified by direct lookup). |
| `createStream()` with empty keywords | **Fine** | C++ `KeywordSpotter::CreateStream(keywords)` calls `EncodeKeywords("")` → `EncodeBase` over zero lines returns `true` with empty `current_ids`, then appends the `keywords_id_` loaded from `keywordsFile` at construction. So the file keywords are active. No `nullptr`, no `OnlineStream(0)` throw. |
| Model/config wiring | **Fine** | `modelType="zipformer2"`, int8 encoder/decoder/joiner, tokens path — all match the official `getKwsModelConfig(1)`. 100 ms / 16 kHz framing matches the sample’s `interval * sampleRate`. |
| Decode/`getResult`/`reset` sequencing | **Fine** | `accept()` → `acceptWaveform` + `while(isReady) decode`; `poll()` reads `getResult().keyword` then `reset`. Equivalent to the official loop. |

**Conclusion:** the wake-word model, keyword file, and sherpa wiring are not the
reason detection silently fails.

---

## 3. What *is* wrong

### 3.1 Primary suspect — capture source (`VOICE_RECOGNITION`)

`AudioIO.kt` opened the microphone as:

```kotlin
MediaRecorder.AudioSource.VOICE_RECOGNITION
```

The official sherpa-onnx KWS Android sample uses:

```kotlin
private val audioSource = MediaRecorder.AudioSource.MIC
```

`VOICE_RECOGNITION` is a *voice-processing* path. On a number of HALs — notably
MediaTek platforms (the Oppo A18 is Helio G85) — it is routed through
noise-suppression / AGC blocks that can hand the app a stream that is effectively
silent or heavily gated before speech is recognized as speech. The wake word is
never “heard”, so the spotter never fires. `MIC` is the raw, universally-honoured
path.

**Fix:** open the mic as `AudioSource.MIC`.

### 3.2 The status line lies

`onState(LISTENING_WAKE)` is emitted the instant the wake loop is entered, *before*
a single sample is proven to arrive. There is no check that `AudioRecord` reached
`RECORDSTATE_RECORDING`, no frame counter, no level. So “Listening for Hey Bro”
means only “we called a function”, not “we are capturing audio”. A dead mic is
indistinguishable from a quiet room.

**Fix:** report the truth. The wake state is now only shown once `AudioRecord` is
confirmed recording, and live mic level/frame metrics are published to the UI.

### 3.3 Capture failures were swallowed

`AudioInput.readFrame()` collapsed every non-positive read into `FloatArray(0)`,
and both capture loops treated empty frames as `continue`:

```kotlin
val frame = audioIn.readFrame()
if (frame.isEmpty()) continue
```

A negative `AudioRecord.read()` result (hard error, e.g. `ERROR_INVALID_OPERATION`
because recording never actually started) therefore produced a busy spin that
looked exactly like “listening forever”. The error code was discarded.

**Fix:** validate `AudioRecord.state` at construction and `recordingState` after
`startRecording()`; throw `AudioCaptureException` on a negative read so the failure
surfaces in the UI/notification instead of silently spinning.

### 3.4 No way to tell silence from signal, no test path

There was no in-app diagnostic: no level meter, no frame counter, no per-stage
test. The only signal available was a notification that was structurally incapable
of reporting the problem.

**Fix:** a diagnostics screen (live mic meter, frame counter, peak-hold, event log,
copyable report) plus one-tap stage tests (MIC / WAKE / ASR / AI / TTS / FULL).

---

## 4. Ranked hypotheses (highest probability first)

1. **Capture source** (`VOICE_RECOGNITION` starving the buffer on this HAL) — §3.1.
2. **ColorOS per-app microphone gate.** Sideloaded apps on ColorOS sometimes need
   the mic re-enabled under Settings → App → Permissions after the runtime grant,
   and the HAL then returns silence. No code can detect this; the level meter makes
   it obvious and the report can be pasted back.
3. **Silent capture error masked as “listening”** — §3.3.

All three are addressed by this change set; (2) is made *visible* rather than assumed.

---

## 5. What the fix changes

- `AudioIO.kt`: `MIC` source; `STATE_INITIALIZED` check; `RECORDSTATE_RECORDING`
  check after start; negative reads throw; PCM16→float conversion extracted as a
  pure, unit-tested function.
- `VoiceDiagnostics.kt` (new): a process-wide singleton publishing a
  `DiagnosticsSnapshot` (state, detail, mic frames, smoothed level, peak-hold,
  wake hits, last ASR/reply/error, timestamp) plus a bounded event log and a
  `report()` string for copy/paste.
- `VoiceSession.kt`: publishes per-frame level, wake hit, ASR text, reply, errors,
  and stage events.
- `VoiceService.kt`: mirrors state/detail into diagnostics; adds a test entry point.
- `VoiceSelfTest.kt` (new): standalone MIC / WAKE / ASR / AI / TTS / FULL runs that
  reuse the real engines (never fakes).
- `ui/AppRoot.kt`: the single switch + a live diagnostics panel (meter, counters,
  signal/no-signal verdict, event list, Copy report) and the six test buttons.
- Unit tests for the new pure logic.

## 6. Acceptance (unchanged)

Say **“Hey Bro, what is two plus two?”** and hear **“Four”** (or equivalent) — on the
device, proven by the event log showing `WAKE_DETECTED → ASR_FINAL → LLM_COMPLETE →
TTS → PLAYBACK`, not by a green build.
