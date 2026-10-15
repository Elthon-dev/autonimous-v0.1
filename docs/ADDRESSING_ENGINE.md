# Open-vocabulary addressing engine

Bro.exe no longer waits for a fixed wake phrase. Voice Mode decides whether the
user is talking to it from the **meaning** of what was said, using a layered
pipeline. This document explains the design, the policy, the trade-offs and how
to tune it.

## Why

The previous engine gated everything behind a closed-vocabulary keyword spotter:
only "Hey Bro", "Bro", "Yo Bro" and "Okay Bro" could start the pipeline. Every
new phrasing required regenerating a token file, and no amount of alias-listing
generalises to natural speech.

The general ASR model was already integrated — it was simply locked behind the
keyword gate. This version removes the gate and decides activation from the
transcript itself.

## Pipeline

```
mic ──► Stage A: VAD + bounded pre-roll ring buffer
          │  (speech start / end)
          ▼
        Stage B: streaming open-vocabulary ASR ──► transcript + confidence
          │
          ▼
        Stage C: AddressingDetector ──► weighted "is this for me?" score
          │
          ▼
        Stage D: ActivationDecider ──► ACTIVATE / WAIT / CLARIFY / IGNORE
          │
          ▼
        Stage E: ConversationSession ──► multi-turn, barge-in, idle timeout
```

State shown to the user:

```
IDLE → SPEECH_CANDIDATE → ACTIVATION_DECISION → LISTENING → THINKING/SEARCHING
     → SPEAKING → LISTENING → … → IDLE
```

### Stage A — voice activity (`VoiceActivityDetector`, `AudioRingBuffer`)

Model-free energy VAD with an adaptive noise floor and hysteresis (a higher
threshold to start speech, a lower one to stop). A bounded ring buffer keeps the
~1.5 s of audio *before* speech was confirmed, so the recognizer does not clip the
first syllable. No model runs in this stage, so idle CPU/memory cost is minimal —
important on a 3 GB device.

### Stage B — recognition (`CommandTranscriber`)

The many-to-one streaming zipformer already in the app. A long-lived recognizer is
created once per voice session; each utterance gets its own stream, primed with
the pre-roll. Partial transcripts are logged; the final transcript and a coarse
confidence (`mean(ysProbs)`) are handed to Stage C. There is **no phrase list** —
any wording is recognized.

### Stage C — addressing intent (`AddressingDetector`)

Accumulates weighted evidence that the utterance is directed at Bro. The cues are
linguistic categories, not phrases:

| Cue | Examples | Weight |
|---|---|---|
| Standalone vocative | "Bro" / "bruh" | 0.60 |
| Leading vocative | "Bro, turn on the lights" | 0.45 |
| Trailing vocative | "what's the weather, bro" | 0.34 |
| Meta-request | "I have a question", "can I ask" | 0.55 |
| Address frame | "can you…", "are you there", "remind me" | 0.40 |
| Imperative opener | "set a timer", "tell me a joke" | 0.40 |
| "please" | | 0.25 |
| Question mark / question word / auxiliary | | 0.18 / 0.12 / 0.10 |
| Opener marker | "hey", "yo", "okay" | 0.15 |
| In-session (hysteresis) | | 0.45 |
| Recent reply | | 0.30 |
| Keyword-spotter hint (optional) | | 0.25 |

Third-person "bro" is **subtracted**: a possessive/article before it ("my bro"),
or a verb after it ("bro is/said"), rescales the vocative weight by 0.3, so
"my bro said he's coming" and "bro is cool" stay at the bottom of the range.

### Stage D — decision (`ActivationDecider`)

Two thresholds split the score into **high ≥ 0.50**, **ambiguous 0.35–0.50** and
**low < 0.35**:

- **high** → activate (subject to a 1.2 s refractory window).
- **ambiguous** → activate if a session is already open (hysteresis); otherwise
  apply the configured policy: **wait** (default) or **clarify** (rate-limited to
  one clarification per 20 s).
- **low** → ignore.

An utterance can only be decided once (dedup by utterance id), so a re-finalized
transcript can never double-activate.

### Stage E — conversation (`ConversationSession`)

Opening a session keeps it open for follow-up turns without repeating any address
phrase, until 30 s of inactivity (configurable). Barge-in lets the user talk over
Bro's reply: playback is chunked and checks a stop flag, and the mic is watched
with an elevated VAD threshold (plus best-effort echo cancellation) so Bro's own
voice is less likely to re-trigger.

## Policy: what activates, what waits

Explicit address — a vocative ("Bro…"), an addressing frame ("can you…") or a
meta-request ("I have a question") — activates on its own.

A bare question ("what's the weather?") or bare imperative ("set a timer") is
treated as **ambiguous**: addressed people usually frame commands, and
over-firing on background/third-party speech is the worse failure. Ambiguous
utterances activate immediately while a session is open, and can be set to ask a
short clarification instead of waiting.

This is a deliberate, documented trade-off, exposed in the UI so it can be tuned.

## Configuration (`DetectionConfig`, persisted)

| Setting | Default | Meaning |
|---|---|---|
| `standaloneBro` | on | allow a bare "Bro" to activate |
| `highThreshold` | 0.50 | activate at/above this |
| `ambiguousThreshold` | 0.35 | middle-band floor |
| `followUpMs` | 8000 | follow-up window after a reply |
| `idleTimeoutMs` | 30000 | close an idle conversation |
| `bargeIn` | on | allow interrupting Bro |
| `ambiguousClarify` | off | ask instead of wait in the middle band |
| `keywordHint` | off | use the keyword spotter as extra (non-decisive) evidence |

## Memory & CPU on a 3 GB phone

- The ASR model (~43 MB) is the only model resident for the whole voice session.
- The LLM (~229 MB) is loaded only while thinking and unloaded immediately after.
- The TTS model (~21 MB) is loaded only while synthesizing.
- Pre-roll is a fixed 15-frame ring buffer (bounded); utterances are streamed into
  the recognizer and never accumulated as raw audio.
- The VAD is arithmetic only — no model.

## Metrics

Activation precision/recall, false-activations-per-hour, decision latency and peak
memory are **not measured here**: that requires labelled real captured audio, which
this environment cannot provide. The diagnostics screen reports live counters
(`candidateUtterances`, `activations`, `rejectedUtterances`, `bargeIns`,
`lastConfidence`, `lastBand`, `lastActivationReason`) so those metrics can be
computed from a real recording session. The `DETECT` self-test exercises the whole
front end (VAD → ASR → classifier → decision) on live microphone audio.

## Legacy keyword spotter

`WakeWordDetector` / `WakeKeywords` / `WakeVerifier` / `WakeConfig` are retained and
still exercised by the `WAKE` self-test. The keyword spotter is optional and off by
default; when `keywordHint` is enabled it runs alongside the general pipeline purely
as a small confidence nudge.
