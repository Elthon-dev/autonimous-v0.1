# Build status

Status is updated after each CI run. Nothing below is labelled "verified" until the
corresponding CI job actually passed.

## Corrective build (VERIFIED 2026-10-12, run 38252678417)

This build replaces the OpenAI-compatible chat path with a real on-device llama.cpp
engine and removes the fixed proactive cooldown.

- `testDebugUnitTest` → **69 tests, 0 failures, 0 ignored (100%)**.
- `assembleDebug` → success. Native libs bundled: `libai-chat.so`, `libllama.so`,
  `libllama-common.so`, `libggml*.so` (CPU variants armv8.0/8.2/8.6/9.0/9.2 + base),
  arm64-v8a only.
- APK `app-debug.apk`, 35,887,427 bytes, package `com.elthon.broexe`,
  SHA-256 `68c59bd3a9f563faa00a44fcf31a0559551b6140abcc8c42b07e2687b598be4b`.

Run URL: https://github.com/Elthon-dev/autonimous-v0.1/actions/runs/38252678417

Earlier compile failures fixed before this run: missing `asSharedFlow`/`File` imports and
`WorkerHub` socket/Nsd-listener signatures.

- Native module `:llama` vendored; CI clones llama.cpp tag `v0.6.0` into `third_party/llama.cpp`.
- New: local inference (`llm/BroLlm.kt`), model store/download/verify (`model/*`), event bus +
  agent + decision parser (`agent/*`), tools (`tools/*`), observer (`observer/*`), voice
  (`voice/`), worker transport (`worker/*`), decision audit table (Room v2).
- Removed: `ModelClient`, `ModelManager`, `OpenAiCompatibleClient` and their tests.
- Target toolchain: compileSdk/targetSdk 36, minSdk 33, NDK `27.0.12077973`, CMake `3.31.6`,
  AGP 8.7.3, Gradle 8.9. CI installs `platforms;android-36` and `build-tools;36.0.0`.

## Previously verified (GitHub Actions, run 38238494350 — pre-corrective, superseded)

- `testDebugUnitTest` → 59 tests, 0 failures; `assembleDebug` → success.
- Pre-native APK (11,474,799 bytes, no bundled `.so`), package `com.elthon.broexe`.

## Toolchain (pinned)

| Component | Version | Notes |
|-----------|---------|-------|
| Android Gradle Plugin | 8.7.3 | `android.suppressUnsupportedCompileSdk=36` set |
| Gradle | 8.9 | wrapper, SHA-256 pinned |
| Kotlin | 2.0.21 | with built-in Compose compiler plugin |
| KSP | 2.0.21-1.0.28 | for Room |
| Compose BOM | 2024.10.01 | stable |
| JDK | 17 | CI uses Temurin 17 |
| compileSdk / targetSdk | 36 | |
| minSdk | 33 | required by the on-device recognizer + modern NDK |
| NDK | 27.0.12077973 | |
| CMake | 3.31.6 | |

## Not implemented / partial

- `VoiceController` has no full UI flow yet (controller only).
- Worker transport is functional but not hardened for untrusted networks.
- Release signing / Play distribution: not started (debug only).
- Native inference correctness is unproven until a real arm64 device runs it.

## Known limitations / risks

- Reminder delivery timing depends on Android/ColorOS battery policy.
- Model weights (~563 MB) are user-downloaded at runtime, never in CI or the APK.
- Robolectric-based tests run under a pinned SDK lower than the app target, so
  API-33+ instrumented behaviour is not covered by unit tests.
