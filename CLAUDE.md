# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`eiP Manager` — an Android app (Kotlin + Jetpack Compose) that manages an **eiP smart stylus (Pencil X / Ultra)** over Bluetooth Low Energy. It scans for, auto-connects to, and configures the pencil: remapping its three physical buttons (top / bottom / tail, each with single/double/triple-click actions), adjusting auto-sleep timer, signal strength, USI mode, and factory reset. It also includes a pressure/drawing test board.

## Build & test commands

Use the Gradle wrapper (`./gradlew`) from the repo root.

```bash
./gradlew assembleDebug          # build debug APK
./gradlew installDebug           # build + install on connected device/emulator
./gradlew assembleRelease        # build minified, signed release (needs keystore, see below)
./gradlew bundleRelease          # build release .aab for Play Store
./gradlew test                   # JVM unit tests (app/src/test)
./gradlew connectedAndroidTest   # instrumented tests (needs a device/emulator)
./gradlew lint                   # Android lint

# Run a single unit test class or method:
./gradlew test --tests "com.example.eip.ExampleUnitTest"
./gradlew test --tests "com.example.eip.ExampleUnitTest.addition_isCorrect"
```

Note: the two files under `app/src/test` and `app/src/androidTest` are the default AndroidStudio template stubs, not real coverage for this app's logic.

## Key gotchas

- **Package vs. namespace mismatch (important).** Kotlin sources live under `com.example.eip.*`, but the app's `namespace`/`applicationId` is `com.eip.device`. Therefore the generated `R` class is `com.eip.device.R` — always `import com.eip.device.R`, never `com.example.eip.R`.
- **Release signing** reads keystore properties (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) from `local.properties`, which is gitignored and does **not** contain them by default. `assembleRelease` / `bundleRelease` will produce an unsigned/broken artifact until those keys are added locally. `debug` builds are forced to use the debug signing config so they Run directly.
- Requires **BLE permissions at runtime**. `MainActivity` requests them on launch; on Android 12+ (API 31) it needs `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`, and on ≤ API 30 the legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION`. Nothing works without these granted.
- Version catalog lives in `gradle/libs.versions.toml`; add/upgrade dependencies there, not inline in `app/build.gradle.kts`.

## Architecture

Single-activity, single-module Compose app. Three layers:

1. **`MainActivity`** — entry point. Owns the `BluetoothViewModel` via `by viewModels()`, handles runtime permission requests, and hosts `DebugScreen` inside a `MaterialTheme`.

2. **`ble/` — the BLE engine (all real logic lives here).**
   - `BluetoothViewModel` (`AndroidViewModel`) is the single source of truth. It drives everything through two forever-running coroutine loops started in `init`: `startScanning()` (4s scan / 3s idle cycle, marking nearby `eip*`-named devices) and `startAutoConnectLoop()` (every 5s, auto-connects to the nearest bonded, disconnected eiP device). There is **no manual connect flow** in normal use — connection is automatic. State is exposed to the UI as `StateFlow`s (`discoveredDevices`, `pencilSettings`, `communicationLog`, `firmwareVersion`, `shutdownTime`, `signalLevel`, `usiSettings`, `batteryLevel`).
   - `BleProtocol` defines the wire protocol: service/characteristic UUIDs (`ffe0`/`ffe2` write/`ffe3` notify), command bytes, per-button `KeyCode`s, and the `Function` code→label map used to populate button-mapping UI.
   - `BleData` holds `BleDevice`, `DeviceConnectionState` (with display text/color), and device-type detection.

3. **`ui/` — Compose UI.**
   - `DebugScreen` is the entire screen (a large stateful composable driven by `DetailPanel` enum: NONE / ERASER / MAPPING / SIGNAL / SETTINGS). It reads the ViewModel's flows via `collectAsState` and calls ViewModel setters on user action.
   - `ui/components/DrawingTestBoard` — pressure/drawing test canvas.
   - `ui/theme/` — Compose theme.

### BLE protocol details

Frames are exchanged over GATT with `WRITE_TYPE_NO_RESPONSE`:

- **App → Device** packets: header `0x55 0xAA`, then `cmd`, optional `keyCode`, `dataLen`, `data…`, and a trailing **CRC-8/Maxim** checksum (`calculateCRC8Maxim`, poly `0x8C`). Built by `sendPencilCommand(cmd, keyCode, data)`.
- **Device → App** notifications: header `0xAA 0x55`, then `cmd`, parsed in `processReceivedData()`. Handled cmds: `CMD_QUERY` (button function mapping, 7-byte frames), `CMD_FIRMWARE_QUERY`, `CMD_STATUS_QUERY` (signal + shutdown time).
- On connect → services discovered → `queryInitialStatus()` fires a scripted burst (firmware query, then queries for buttons 1–6, then status). The `hasReceivedInitialStatus` flag guards against overwriting user edits with late-arriving initial query responses — preserve this behavior when touching `processReceivedData`/setters.
- Device filtering is by BLE name containing `"eip"` (case-insensitive), explicitly excluding `"magnetix"`; keyboard vs. pencil is inferred from name keywords in `BleData`.

All communication is mirrored into the in-app `communicationLog` (capped at the last 100 entries) shown in the Communication Log panel — useful when debugging protocol issues.
