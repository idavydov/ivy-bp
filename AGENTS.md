# Development notes

## Platform instructions

When interacting with Cloudflare, use the cf CLI unless the project has a
Wrangler configuration file.

## Android builds and data safety

- Use JDK 17, Android platform 36 and build-tools 35.0.0.
- Release application ID: io.github.idavydov.ivybp.
- Debug ID: io.github.idavydov.ivybp.debug; never target production data with tests.
- Never clear or uninstall the user's personal-profile production app for testing.
- Build instrumentation APKs with assembleDebugAndroidTest. Install and invoke
  them using adb -s emulator-5554 explicitly, rather than connectedDebugAndroidTest
  while a real phone is connected. A runner argument named serial does NOT select
  the target device.
- Database migrations must preserve existing readings. Test migrations and deletion
  with synthetic data on the emulator before installing a release update.
- Personal signing material, APKs, third-party binaries, captures, SDK paths and
  diagnostic exports remain ignored. Never publish them.
- Do not click deletion confirmations on the real phone during verification.

## Confirmed hardware behavior

- Real IVY IVBPW / LS810-B and Pixel 6 running Android 17 (API 37).
- Locked-phone auto-sync with Reliable listening saved four readings without
  laptop connection/scanning. First packet arrived about 28 seconds after auth.
- A ten-second first-packet timeout disconnected prematurely. Preserve the
  90-second first-packet wait; use the two-second idle cutoff only after a record.
- Companion selection may return lowercase MAC strings. Canonicalize before every
  Bluetooth API boundary and when reading/writing preferences.
- Companion-only presence detection and other phones remain unverified.
- Do not assume MEM enables Bluetooth. Coordinate real measurement tests with the
  user before starting scans/connections.
- Data includes systolic, diastolic and pulse. Keep raw bytes and device timestamps;
  timestamp accuracy is not established. User requested no unverified-time UI label.
- Clock is synchronized on every authenticated sync.
- Pressure/heart-rate values matched a monitor display in the local Linux test.
  Do not claim more extensive clinical or accuracy validation.

## Verification commands

    ./gradlew testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest
    python3 -m unittest -v test_ivy

Instrumented tests cover backup merge/rollback, deletion suppression, data-preserving
migration and mutation-gate release. Run only on an explicit emulator serial.
Battery work keeps LOW_POWER and address filtering, pauses during sync, and waits
ten seconds before resuming. Savings have not been measured; do not claim a battery
percentage or replace the working mode with intermittent polling without live tests.
