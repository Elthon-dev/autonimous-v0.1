# Debug keystore

`debug.keystore` is the **standard Android debug key**, not a release or production
signing key:

- alias: `androiddebugkey`
- store/key password: `android` (the well-known public defaults)
- DN: `CN=Android Debug, O=Android, C=US`

It is committed on purpose so that every CI build is signed with the *same*
certificate. GitHub-hosted runners otherwise generate a fresh debug keystore on
each run, which makes each APK un-installable over the previous one
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE` → forced uninstall → lost local data).

`app/build.gradle.kts` points the `debug` signing config at this file when present
and falls back to AGP's default debug signing otherwise.

This file must never be used to sign a release build. Release signing is not
configured here.
