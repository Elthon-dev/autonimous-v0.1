# Bro.exe — Android companion (Phase 2)

A personal AI companion for Android, built with Kotlin + Jetpack Compose. The brain
runs **on-device** via a vendored [llama.cpp](https://github.com/ggml-org/llama.cpp)
binding — no cloud endpoint is involved.

- Application ID: `com.elthon.broexe`
- Min SDK 33 · Target/Compile SDK 36 · Debug build
- Toolchain: AGP 8.7.3 · Gradle 8.9 · Kotlin 2.0.21 · NDK 27.0.12077973 · CMake 3.31.6
- Native: `arm64-v8a` only; CPU variants for armv8.0–armv9.2 are built in.

What works: local tasks, memories, reminders, streaming chat, a model-directed
decision layer (no fixed cooldown), an opt-in screen observer, on-device
speech, and a LAN worker transport. See `BUILD_STATUS.md` for the honest
per-feature matrix and `docs/` for architecture.

## Building

The project builds with the checked-in Gradle wrapper. No Android Studio needed.
CI clones llama.cpp `v0.6.0` into `third_party/llama.cpp` before the native build.

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

A GitHub Actions workflow (`.github/workflows/android-build.yml`) builds on a
Linux runner, runs unit tests, and uploads the debug APK as an artifact.

## Installing on the Oppo A18 (Android 15)

1. Get `app-debug.apk`:
   - from GitHub Actions: open the latest run, download the **bro-exe-debug-apk** artifact, unzip it, or
   - locally: `app/build/outputs/apk/debug/app-debug.apk`.
2. Copy it to the phone's `Download` folder.
3. Open **Files / File Manager**, tap the APK.
4. If prompted, allow installing from this source (Files / your browser).
5. Review the install screen and tap **Install**.
6. Open **Bro.exe**.

Alternative with a working ADB connection:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First test run

- [ ] App installs and opens without crashing.
- [ ] Home shows local model state, task count, memory count and mode.
- [ ] Offline chat works before any model is loaded (type `help`, `tasks`,
      `remind me to stretch in 5 minutes`, `status`).
- [ ] Settings → Local model: download/import `Qwen3.5-0.8B-Q4_0.gguf`; verify
      the size/hash check passes and the model loads.
- [ ] Chat with the model loaded streams tokens and answers coherently.
- [ ] Decisions are model-directed: the assistant stays silent until it
      actually has something to say (there is no cooldown timer).
- [ ] Tasks: create one, fully close and reopen the app — it is still there.
- [ ] Memory: add one, close and reopen — it is still there.
- [ ] Reminder: create one a few minutes ahead; with notifications enabled a
      notification arrives (may be delayed by Android/ColorOS battery policy).
- [ ] Deny notification permission — the app must not crash, reminder stays in-app.
- [ ] Enable the observer in Android settings; confirm events only appear for the
      apps you allow-listed.
- [ ] Voice: hold-to-talk transcribes locally and the reply is spoken.
- [ ] Worker: with two devices on the same Wi-Fi, the second device discovers
      and submits a task (token-guarded).
- [ ] Toggle the proactive pause switch; it persists across restart.
- [ ] Diagnostics shows real counts, engine state and a bounded event log.

## Security notes

- Model weights are downloaded at first use (never bundled in the APK or CI) and
  verified by size + SHA-256 before loading.
- The screen-observer accessibility service is **off by default** and only
  forwards text from apps you explicitly allow-list.
- Voice uses the platform recognizer (preferring the on-device path); audio is
  not persisted.
- The worker transport is token-guarded but plain TCP on the LAN — use only on
  trusted networks.
- No API keys, tokens or signing material are committed. There is no cloud
  endpoint in this build.

## Project layout

```
app/src/main/java/com/elthon/broexe/
  core/      TimeProvider (wall + monotonic)
  data/      Room entities, DAOs, repositories, DataStore settings
  domain/    Pure logic: validation, task state machine, quiet hours, OpResult
  engine/    DecisionEngine + ProactiveManager (event-driven, no cooldown)
  llm/       TextEngine + LocalLlamaEngine (on-device inference)
  model/     ModelCatalog, ModelStore, ModelDownloader, LocalModelManager
  agent/     Event bus, BroAgent, decision parser, prompts, coordinator
  tools/     Tool registry + built-in tools
  observer/  Opt-in AccessibilityService + ObservationGate
  voice/     On-device STT + TTS controller
  worker/    LAN discovery + token-guarded socket transport
  reminder/  WorkManager scheduler, worker, boot receiver, notifications
  ui/        Compose screens, theme, view models, navigation
llama/       Vendored llama.cpp JNI module (com.arm.aichat binding)
app/src/test/  Unit tests (pure JVM + Robolectric)
```
