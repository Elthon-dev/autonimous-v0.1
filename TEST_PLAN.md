# Test plan

## Required scenarios and where they are covered

| # | Scenario | Test |
|---|----------|------|
| 1 | App starts with no model loaded | `StartupAndNotificationTest.app starts with an empty database and no model loaded` |
| 2 | App starts with an empty database | `DatabasePersistenceTest.empty database has no tasks or memories`, `Startup...` |
| 3 | Creating a task persists after restart | `DatabasePersistenceTest.task persists after closing and reopening the database` |
| 4 | Editing and deleting a task work | `DatabasePersistenceTest.task persists...` (edit + delete assertions) |
| 5 | Memory create/search/edit/delete + restore after restart | `DatabasePersistenceTest.memory can be created searched edited deleted and restored after restart` |
| 6 | Invalid dates / missing fields rejected | `ValidationTest` |
| 7 | Quiet hours suppress proactive messages | `DecisionEngineTest.quiet hours suppress proactive output`, `QuietHoursTest` |
| 8 | Pause suppresses proactive messages | `DecisionEngineTest.paused suppresses proactive output` |
| 9 | Cancelled task does not execute again | `TaskStateMachineTest.cancelled is terminal and cannot run again`, `ReminderRepositoryTest.cancelled reminder never fires` |
| 10 | Retry does not create duplicate reminders | `ReminderRepositoryTest.markFiredIfPending is idempotent - no duplicate notifications` |
| 11 | Denied notification permission does not crash | `StartupAndNotificationTest.denied notification permission does not crash` |
| 12 | There is no fixed cooldown (silence is model-directed) | `DecisionEngineTest.there is no cooldown ...` |
| 13 | Control-block parser handles valid/malformed/bare/spanned input | `DecisionParserTest` |
| 14 | Observation gate respects opt-in, self, length, allow-list, de-bounce, rate cap | `ObservationGateTest` |
| 15 | Worker framing round-trips and state machine rejects illegal transitions | `WorkerCodecTest` |
| 16 | Tool registry lookup + catalog + invocation | `ToolRegistryTest` |
| 17 | Offline use for local tasks and memories | `OfflineAssistantTest`, `StartupAndNotificationTest.app remains usable offline for tasks and memories` |

## Commands

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

## Test environment note

Tests run on the JVM. Android-touching tests (Room, notifications, org.json) use
Robolectric (pinned via `app/src/test/resources/robolectric.properties`).

## Requires real-device testing (not covered by unit tests)

- First-launch experience on the Oppo A18 / ColorOS.
- Actual WorkManager reminder delivery timing and battery restrictions.
- Notification permission dialog on Android 13+.
- **On-device llama.cpp inference** (load, streaming, tok/s) — JNI, arm64 only.
- **Model download/import** end-to-end (~563 MB) and SHA-256 verification timing.
- **Accessibility observer** gaining events from real apps once enabled.
- **Voice (STT/TTS)** with a downloaded on-device language pack.
- **Second-device worker** discovery and task submission on a real LAN.
- UI rendering and layout on a narrow phone screen.
