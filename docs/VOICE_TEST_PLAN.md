# Voice Prototype — Manual Device Test Plan (Oppo A18)

Status: NOT STARTED. Run after the voice-prototype APK installs and boots without
crashing. Mark each row ✅ / ❌ / ⛔ (not reachable). Record screenshots + logcat
(`adb logcat | grep -iE "broexe|sherpa|llama|AndroidRuntime"`). The app has no
in-app log screen any more — logcat is the only source.

## Prep

1. Free space: need ≥ ~1.2 GB free before install (APK ≈ 400 MB + extracted assets).
2. Charge ≥ 50%. Have headphones/quiet room for stage 2–3 (KWS/ASR tuning).
3. Battery: Settings → Battery → the app → **Allow background activity**, disable
   “battery optimization” while testing (this is an optional ColorOS helper, not a
   permission bypass).
4. The giant assistant is gone; there is nothing else to enable. The only control
   is the Voice Mode switch.

## Stage 1 — Install, first launch, Voice Mode wiring

| # | Step | Pass? |
|---|------|-------|
| 1.1 | Install `app-debug.apk` (from CI artifact), package `com.elthon.broexe`. Hash matches BUILD_STATUS.md. | |
| 1.2 | Open Bro.exe. One screen: title, one Voice Mode switch, one status label. No chat box, no task/memory pages. | |
| 1.3 | Status shows `Off`. Switch tap → permission dialog(s) appear (Microphone, then Notifications); app stays responsive. | |
| 1.4 | Grant both. Foreground notification appears BEFORE listening claims `Listening for “Hey Bro”`. | |
| 1.5 | Status becomes `Loading local AI` then `Listening for "Hey Bro"`. Storage did not run out. First-launch extraction reports real progress. | |
| 1.6 | Tap switch OFF mid-listening → mic stops (mic icon disappears from status bar), notification removed, status `Off`. Re-enable twice more → state machine toggles cleanly at least 5×, no crash, no leaked services (Settings → Running Services). | |

## Stage 2 — Wake word

| # | Step | Pass? |
|---|------|-------|
| 2.1 | Say “Hey Bro” at normal volume. Status flips from `Listening for "Hey Bro"` to capture within ~2 s. Record missed/needed repeats. | |
| 2.2 | Silence for 10 s, TV/news audio, someone else talking → no false trigger. Count false positives per 10 min of ambient audio. | |
| 2.3 | After the assistant answers, it must NOT re-trigger on its own voice (half-duplex). Put it in a loop: ask it to read a long sentence; confirm no self-trigger. | |
| 2.4 | Follow-up window: after wake #1, talk again within the follow-up window WITHOUT the wake word — should be accepted; after the silence timeout, a second no-wake utterance must be ignored. | |

## Stage 3 — Recognition + local answer

| # | Step | Pass? |
|---|------|-------|
| 3.1 | “Hey Bro… what is the capital of France?” → statuses progress capture→transcribing→thinking→speaking with NO artificial delay between end-of-speech and start of processing. | |
| 3.2 | Spoken entity does NOT contain `thinking`, `system`, `<|im_start|>`, `tool_call`, JSON, or any debug text. Truncated/malformed output must be handled as an error, not spoken. | |
| 3.3 | “Hey Bro… (garbled mumble)” → app either asks for clarification or says it didn’t catch that. Never stays stuck in `Listening to your request`. | |
| 3.4 | English accent/intelligibility: count mis-transcriptions over 20 short commands. If poor, report exact phrases → threshold/model tuning follow-up. | |

## Stage 4 — Web search

| # | Step | Pass? |
|---|------|-------|
| 4.1 | “Hey Bro… search the web for today’s top tech news” → `Searching the web` appears, then a concise spoken answer citing a real source domain. | |
| 4.2 | Repeat 4.1 with Wi-Fi OFF → answer must explicitly say current info could not be verified (no fake results). | |
| 4.3 | During search, flip Voice Mode OFF → network call cancels promptly, service tears down. | |
| 4.4 | Ad-hoc direct question with no internet need → no `Searching the web` state; answered from local knowledge. | |

## Stage 5 — Stability (20+ cycles)

| # | Step | Pass? |
|---|------|-------|
| 5.1 | Run ≥ 20 full wake→answer cycles in a row. Watch for lag creep, memory growth (Developer options → Running services → memory), crashes, or increasing response time. Record any. | |
| 5.2 | Lock the screen mid-listening. App keeps listening (notification visible) for ≥ 3 min under the battery settings in Prep. If ColorOS kills it, record that as a device behavior, NOT an app defect. | |
| 5.3 | Force-stop the app; reopen; Voice Mode OFF default, no auto-restart loop. | |
| 5.4 | Kill/deny permission during run (system grants), then retry → `Recovering from an error` or clean `Off`, no crash. | |
| 5.5 | Repeat 5.1 with the storage watchdog: confirm no temporary files accumulate and no “extract every launch” behaviour (re-extract flag stays unset after restart). | |

## Reporting

At the end: number passed/failed/⛔ per stage, peak process memory observed, first
observed crash log + exact repro, and the keyword/ASR thresholds whatever was
measured. Only rows marked ✅ are “really works on the Oppo A18”.