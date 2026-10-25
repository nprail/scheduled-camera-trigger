# AGENTS.md

Guidance for agents working in this repository.

## Repository layout

| Path | What |
| --- | --- |
| `trigger/`, `interface/`, `config.example.json` | The device side (Node, Raspberry Pi Zero W) and its web interface. |
| `docs/BLE_PROTOCOL.md` | **The BLE protocol (v1). The source of truth for everything the phone and device exchange.** |
| `docs/test-vectors.json` | Cross-implementation test vectors, generated from the Node reference (`node test/vectors.js --write`). |
| `ios/` | The native iOS app (SwiftUI) and `CameraTriggerKit`, a UI-free Swift package for the protocol. |
| `android/` | The native Android app (Kotlin, Jetpack Compose, Material 3). The protocol code is in `app/src/main/java/com/noahprail/camtrigger/protocol/`. |

The iOS app is a standard Xcode project (`ios/CameraTrigger.xcodeproj`), with no project generator. Run the kit's tests with `cd ios/CameraTriggerKit && swift test`.

The Android app is a Gradle project (`android/`, wrapper included, JDK 17+). Run its unit tests with `cd android && ./gradlew :app:testDebugUnitTest`, and build the debug APK with `./gradlew :app:assembleDebug`.

## Keep iOS and Android in sync

**Always make functional changes to both the iOS and Android apps.** The two apps must behave the same. When you add, change or remove a feature, behavior, screen, field, message text, protocol operation or bug fix in one, make the matching change in the other in the same piece of work, and update the app specification below if the behavior it describes changes.

- A change counts as functional if it affects what the user can do or see, how the app talks to the device, or how it handles errors, timing or state. Examples: a new setting, a different confirmation, a new error message, a changed timeout, a protocol change.
- Purely platform-specific work is the exception: build setup, platform permissions, secure storage, navigation and styling that follow each platform's conventions (SwiftUI and Material 3 respectively). Keep these idiomatic for each platform rather than copying the other.
- Protocol changes start in `docs/BLE_PROTOCOL.md` and the reference test vectors (`docs/test-vectors.json`). Both apps must pass the same vectors.
- Run both apps' tests before finishing (`swift test` for iOS, `./gradlew :app:testDebugUnitTest` for Android), make sure both still build, and check any changed screen on both platforms. The debug demo mode on each shows screens without Bluetooth hardware, so it works in a simulator or emulator as well as on a phone.
- If you can't change both (for example one toolchain isn't available), say so clearly in your final message and list exactly what is left undone on the other platform. Don't silently ship a change on one platform only.

---

# Mobile app specification

This section describes what the apps do. It was written from the iOS app and the Android app implements it, so it applies to both. **Behavior must match across the two apps. Platform idioms (navigation, dialogs, permissions, secure storage) follow each platform's conventions.** Where this spec and `docs/BLE_PROTOCOL.md` disagree, the protocol document wins; report the discrepancy.

## 1. Purpose

A phone app that configures and controls a **Scheduled Camera Trigger**, a Raspberry Pi device that wakes a camera and starts and stops recording at scheduled "launch attempt" times. The app talks to the device only over Bluetooth Low Energy (no Wi-Fi or internet needed), so it works in the field.

Main jobs:
1. Find nearby devices and connect.
2. Pair once with a physical button press on the device, so only that phone controls it.
3. Set the device clock from the phone (the Pi has no battery-backed clock).
4. Show live status and a countdown to the next event.
5. Edit the schedule, timing and camera settings.
6. Manually test, wake, start, stop and sleep the camera, and toggle the device's Wi-Fi.
7. Show the device log live.

## 2. Architecture (mirror this split)

- **Protocol library, with no UI** (iOS: `CameraTriggerKit`). It holds:
  - message framing and reassembly
  - authentication (HMAC proofs)
  - the request/response client (`DeviceClient`)
  - the data models
  - the BLE central and connection wrappers
  - secret storage

  It is testable without hardware by faking the transport. `DeviceClient` depends only on a small transport interface (write a fragment, a stream of notified fragments, max write length).
- **App state holder** (iOS: `AppModel`). One observable object owns the connection state machine, the data shown on screen, and every action. Views read it and call it, and hold no protocol logic.
- **Screens** (iOS: SwiftUI views): scan, connecting, pairing, device, schedule and settings editor, logs.

On Android, use the same split: a protocol module, a `ViewModel` (or equivalent) as the state holder, and Compose (or XML) screens.

## 3. Protocol summary

Read `docs/BLE_PROTOCOL.md` for the full detail. What the app depends on:

- One GATT service (`7a3c0001-5e9b-4d2a-8f61-c0a7b2d4e9f3`) with four characteristics: Device Info (read, JSON), Status (read and notify, a fixed 12-byte value), Command (write), Response (notify).
- Requests are JSON, `{id, op, d}`, split into fragments to fit the ATT write size. Responses and unsolicited events arrive on Response and are reassembled. **One request is in flight at a time; extra callers wait their turn.** Each request has a timeout (10 s by default, 20 s for camera actions); a timeout fails the request.
- The Status value is parsed directly from bytes: flags (recording, camera ready, Wi-Fi, pairing open, clock set), phase (idle, awake, recording, test, error), config revision, next event time (Unix seconds, 0 for none), next event type (none, wake, start, stop), attempts remaining. Integers are little-endian.
- Dates on the wire are ISO 8601 UTC with milliseconds. Parse with or without the fractional part.
- Events pushed by the device: `job`, `config`, `test`, `log`.
- Operations used: `hello`, `auth`, `auth.pair`, `status.get`, `config.get`, `config.set`, `jobs.get` (paged), `camera.wake|start|stop|sleep`, `test.run`, `logs.get` (paged), `logs.subscribe`, `wifi.set`, `time.get`, `time.set`.
- Only protocol version **1** is supported. A different version in Device Info or `hello` is an error ("unsupported"), with a message telling the user to update the app.

**Test vectors:** `docs/test-vectors.json` covers framing, HMAC proofs and status decoding. The Android protocol module must pass the same vectors (see `ios/CameraTriggerKit/Tests/` for how the iOS tests use them).

## 4. Connection state machine

States: **disconnected → connecting(name) → needsPairing(reason?) → ready**.

Connect sequence, after the user taps a device:
1. State becomes `connecting`; scanning stops; any banner is cleared.
2. Connect, discover the service, subscribe to Status and Response. Time out after **15 s** ("Could not connect to the device.").
3. Read Device Info. If `v != 1`, fail with the unsupported-version error.
4. Create the client and start listening for events and Status notifications.
5. Send `hello`.
6. If the device is paired **and** this phone has a stored key for it: run the auth handshake (section 5).
   - Success → become ready (below).
   - `unauthorized` error → go to `needsPairing` with that error text.
7. Otherwise go to `needsPairing`. The reason is "This device hasn't been paired with a phone yet." if the device is unpaired. If it is paired to another phone, there is no reason text.
8. Any other error: disconnect, return to `disconnected`, show the error as a banner, and resume scanning.

**Becoming ready**, in order:
1. Sync the clock (best effort; a failure doesn't block).
2. Fetch config, status and all jobs.
3. Read the Status characteristic for live values.
4. State becomes `ready`.
5. Load logs (section 8) and subscribe to live log lines.

**Disconnect** (user taps Disconnect): drop the link, clear all device data, return to `disconnected`, resume scanning.

**Connection lost** (the link drops on its own): clear all device data, return to `disconnected`, show the banner "The connection to the device was lost.", and resume scanning.

**Forget pairing:** delete the stored key for this device, then disconnect.

## 5. Pairing and authentication

- Pairing needs a **physical button press on the device**, which opens a 2-minute pairing window (the Status `pairingOpen` flag).
- The Pair button creates a random **16-byte secret** and sends it with `auth.pair`. On success, save it to secure storage keyed by **device ID** and become ready. On failure, stay on the pairing screen and show the error text (for example "Press the button on the device, then try again within 2 minutes.").
- Authenticating uses the HMAC-SHA256 challenge/response from the protocol doc: the client proves it holds the secret, **and the app must verify the device's returned proof**. If the device's proof does not match, fail with `unauthorized` ("The device could not prove it holds the pairing key.").
- **Secret storage:** one secret per device ID, in secure, device-local storage that is not backed up or synced. On iOS this is the Keychain (`AfterFirstUnlockThisDeviceOnly`). On Android use the Keystore (for example encrypted preferences) with backup disabled for it. A save failure is shown as "Could not save the pairing key".
- Device ID comes from Device Info `id`. In the scan list, the ID is parsed from the advertised name `{name}-{ID}`, where ID is the last `-`-separated part when it is exactly 6 hex digits (upper-cased). That is how the scan list knows whether a device is already paired (a key exists for that ID).

## 6. Screens and behavior

### 6.1 Scan screen ("Camera Triggers")

Content depends on the Bluetooth state:

| State | Shown |
| --- | --- |
| On | List of discovered devices. While the list is empty, a spinner with "Looking for camera triggers…". Footer: "Stay within a few metres. The first time, you'll pair with the button on the device." |
| Unauthorized (permission denied) | "Bluetooth is off for this app", then "Allow Bluetooth for Camera Trigger in Settings." (iOS) or "Allow Nearby devices access for Camera Trigger in Settings." (Android, the name of its permission), and an **Open Settings** button that opens the app's system settings. Android also has an **Allow** button that asks for the permission again. |
| Off | "Bluetooth is off", "Turn on Bluetooth to find your camera triggers." Android also has a **Turn on** button that asks the system to enable Bluetooth (iOS apps can't do this). |
| Unsupported | "Bluetooth isn't available", "This device doesn't support Bluetooth Low Energy." |
| Unknown or resetting | A spinner. |

Scanning rules:
- Scan only for the service UUID, allowing duplicates so signal strength stays fresh.
- Each row: device name, "Paired" or "Not paired", a lock icon (closed when paired), and a signal-strength icon mapped from RSSI (about -95 dBm = empty to -45 dBm = full).
- Sort by signal strength, strongest first.
- Remove a device that hasn't been seen for **8 s** (checked every 2 s).
- Start scanning when the screen appears and whenever Bluetooth becomes powered on while disconnected. Stop while connecting.
- Tapping a row starts the connect sequence.

Android notes: request the Bluetooth runtime permissions for the OS version (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` on Android 12+, with `neverForLocation` if applicable; location permission on older versions), and map "permission denied" to the "unauthorized" row above. Prompt the user to turn Bluetooth on rather than failing silently.

### 6.2 Connecting screen

A centered spinner: "Connecting to {device name}…".

### 6.3 Pairing screen

Title is the device name. Content: a button icon, "Pair with this device", and the text "Press the button on the camera trigger, then tap Pair within two minutes. This phone will then be the only one that can control it." Shows the reason or error in red if there is one. A **Pair** button (replaced by a spinner while working, and disabled then) and a **Cancel** button (disconnects).

### 6.4 Device screen

Title: the device name (or "Camera Trigger" if empty). Toolbar: **Disconnect**. Sections, top to bottom:

**Status**
- Phase title and icon, colored: Idle (grey), Camera awake (orange), Recording (red), Running test (blue), Needs attention (orange). On Android these use the theme's roles (muted, tertiary, error, primary, tertiary) so they follow Material You, and may not be literally orange.
- A red "REC" indicator while the Status `recording` flag is set.
- If the Status `cameraReady` flag is false: "Camera not ready. Check the wiring or camera connection." in orange.

**Next**
- The next event type: "Wake the camera", "Start recording" or "Stop recording", the attempt name beneath it, and a **live countdown that ticks every second**: `H:MM:SS` when an hour or more remains, otherwise `MM:SS`, never negative.
- An "At" row with the full local date and time.
- If none: "Nothing scheduled".

**Camera**
- **Run test** (disabled unless the phase is idle, or a test is already running). Shows a spinner while running. Test progress arrives as `test` events; show the steps joined with " → " (step names capitalized), ending in "Done" or "Failed: {error}". A failure also shows an error banner.
- Four buttons in a 2×2 grid: **Wake**, **Sleep**, **Start**, **Stop**. They are enabled only when the phase is idle or recording (the device refuses them otherwise). **Start and Stop ask for confirmation first** ("Start recording now?" and "Stop recording now?").
- After each camera action, refresh status.

**Schedule**
- List of jobs: name, launch time (local), and a state pill: active (red), complete (green), missed (orange), anything else (blue). Android uses theme container colors for these, with its own fixed green for complete. The pill text is the state, capitalized.
- "No attempts scheduled" when empty.
- A link "Edit schedule and settings" to the editor.

**Device**
- **Logs** link.
- **Wi-Fi** toggle, reflecting the Status `wifi` flag. Turning it **off** asks for confirmation: "Turn Wi-Fi off?", with "You can turn it back on here over Bluetooth, or with the button on the device." Turning it on needs no confirmation.
- **Clock**: shows "Not checked", "Set from this phone (was N ms off)" or "In sync (N ms)", with a **Sync now** button (disabled unless idle). Sync now forces a set (threshold 0).
- Read-only rows: ID, Camera (type), Software (firmware version).
- **Forget pairing** (destructive), which asks for confirmation: "Forget this device?" with "To pair again, press the button on the device."

### 6.5 Schedule and Settings editor

Opened from the device screen. Works on a **draft copy** of the config. **Save** is enabled only when the draft differs from the device's config, no save is in progress, and the device is not busy. While saving, the form is disabled.

"Busy" means the live phase is anything other than idle (awake, recording, test and error all count; see section 10). When busy, show an orange notice at the top: "The device is busy. Changes can be saved when it's idle."

Sections:
1. **Launch attempts** (up to 30). Each attempt has an editable name and a date-and-time picker labelled "Launch (T-0)". Swipe or delete to remove one. **Add attempt** appends "Attempt N", defaulting to the next full hour after now or after the latest existing attempt, whichever is later. Footer: "Times are in your local time zone ({abbrev}). The device works in UTC. Up to 30 attempts." Times are stored on the wire as UTC.
2. **Timing** (each a stepper, 0 to 600 s in steps of 1, shown as a value with a description):
   - Wake camera: "N s before recording" (`wakeUpTimeout`)
   - Start recording: "N s before T-0" (`startBefore`)
   - Stop recording: "N s after T-0" (`endAfter`)
3. **Camera**:
   - **Type** picker: "Remote trigger (GPIO)" (`generic`) or "Z CAM (network)" (`zcam`).
   - For `generic`: **Release pin** (integer GPIO) and **Focus pin** (optional integer GPIO).
   - For `zcam`: **Camera IP**, with the placeholder `192.168.1.50`, no auto-capitalization or autocorrect.
4. **Device**: **Name** (optional text) and **Button pin** (optional integer GPIO).

Behavior:
- Empty text fields for optional values are sent as null or absent. Non-numeric text in a pin field becomes empty.
- Attempt identity is local only. Equality for "is the draft changed" compares name and time, not the local ID. Local IDs are never sent to the device.
- Fields the device owns (`deviceId`, `configRev`) are ignored on write.
- **Save** sends `config.set` with the whole config. On success, refresh the config, jobs and status, then close the editor. On failure, show an alert "Couldn't save" with the error text, and keep the editor open. Validation errors (`invalid_config`) read "{field}: {message}".
- The device also pushes `config` events (for example after a change from the web interface). When one arrives, refresh config, jobs and status.

### 6.6 Logs screen

A list of log lines (namespace on the left in small bold secondary text, time on the right, message in monospace below). Empty state: "No log lines yet". Auto-scrolls to the newest line when it opens and when a new line arrives.

Log loading: after becoming ready, page through `logs.get` (100 per page, up to 12 pages) and keep the latest **500** lines. Then call `logs.subscribe` with the last sequence number so the device replays anything missed and streams new lines. New lines are appended (ignoring a sequence number already present) and the list is trimmed to 500.

### 6.7 Error banner

A dismissible red banner at the top of every screen, with a warning icon and the message. Used for connection failures, a lost connection, camera-action errors and test failures. Cleared when a new connection starts.

## 7. Live updates

- **Status notifications** (up to once a second while recording) update the live Status values immediately (phase, flags, next event, countdown). Only re-fetch the richer JSON status (`status.get`) when the phase, flags or next event time **changed**, or when the config revision differs from the one held locally. If the config revision changed, also refresh config and jobs.
- **`job` events:** re-fetch jobs and status.
- **`config` events:** re-fetch config, jobs and status.
- **`test` events:** drive the Run test progress (section 6.4). A step named `done` ends the run.
- **`log` events:** append to the log (section 6.6).

## 8. Clock sync

The phone is the time authority.

1. On every connect, measure the offset: send `time.get`, then offset = device time − (send time + half the round-trip).
2. If the absolute offset is more than **0.25 s**, send `time.set` with the phone's current time plus half the round trip (the write takes about that long to arrive).
3. If the device is busy, leave it alone: the result is "not applied".
4. The result is shown on the device screen (section 6.4). **Sync now** sets with a threshold of 0.

## 9. Errors

Show `DeviceError` messages to the user as friendly text. Mappings in the iOS app:

| Code | Message |
| --- | --- |
| `busy` | "The device is busy right now. Try again after the current recording or test." |
| `unauthorized` | "The device rejected the pairing key. Re-pair using the device's button." |
| `locked_out` | "Too many failed attempts. Try again in N s." (N from `retryAfter`; omit when absent) |
| `pairing_closed` | "Press the button on the device, then try again within 2 minutes." |
| `invalid_config` | "{field}: {message}" (message alone if no field) |
| `disconnected` | "The device disconnected." |
| `timeout` | "The device did not respond in time." |
| `not_found` | "The device is no longer in range." |
| `unsupported` | "This device uses protocol vN. Update the app." |
| anything else | The device's message text |

## 10. Details worth matching exactly

- **Busy gating differs by feature.** The iOS app treats the device as busy for *saving config* whenever the live phase is not idle (awake, recording, test and error all count). Manual camera buttons are enabled only when idle **or** recording. Run test and Sync now are enabled only when idle. Keep each rule as is.
- The countdown ticks locally every second from the next-event time; it does not wait for the device.
- Devices are identified by the device ID in Device Info, not by the Bluetooth address (platforms differ: iOS gives a stable per-phone UUID, Android gives a MAC address).
- One central, one device at a time. Don't keep the old device's data after disconnecting.
- The app never sends a config containing local-only fields (such as attempt UUIDs).

## 11. Platform notes for Android (as implemented)

- **Minimum:** pick a recent API level that supports the Bluetooth APIs you need. Request MTU (the Pi's default is 23 bytes; use a larger one if negotiated, and size writes to `MTU − 3`).
- **Writes:** use write with response for Command, and queue them one at a time (Android's GATT API allows only one outstanding operation).
- **Notifications:** enable them by writing the Client Characteristic Configuration descriptor on both Status and Response before sending the first request.
- **Device Info is read with Read Long**, since it can be up to 256 bytes.
- **Lifecycle:** drop the connection and stop scanning when the app goes to the background if that's not needed. Don't leak the GATT object: always `close()` it after disconnecting.
- **Emulators have no Bluetooth.** Use the debug demo mode (section 12) to check screens without hardware.

## 12. Debug demo mode

Both apps have a debug-only demo mode that fills the app with fixture data and skips Bluetooth, so every screen can be checked without a device, in a simulator or emulator or on a phone. Both support the same scenes, which must be kept in step: `scan`, `scanning`, `off`, `unauthorized`, `unsupported`, `connecting`, `pairing`, `pairing-error`, `ready`, `generic`, `recording`, `banner`. The fixtures should match what a real device sends (for example job states `pending`, `active`, `complete`, `missed`).

- **In the app (both):** on the scan screen, tap a hidden spot 7 times to open a "Demo mode" menu listing the scenes, plus **Exit demo** while in demo mode. The spot is the "Camera Triggers" title on Android and the "Stay within a few metres…" hint under the device list on iOS (a SwiftUI large navigation title can't take taps). In a demo scene, **Disconnect**, **Cancel** or Back returns to the scan screen.
- **iOS launch argument:** `-demo <scene>`. Code: `ios/CameraTrigger/DemoMode.swift`.
- **Android intent extras:** `--es demo <scene>`, optionally `--es screen config` or `--es screen logs`, passed to `adb shell am start -n com.noahprail.camtrigger/.MainActivity`. Code: `android/app/src/main/java/com/noahprail/camtrigger/DemoMode.kt`.

Demo mode compiles into debug builds only.

## 13. Not in either app (don't assume it)

- No background connection, no notifications, and no widgets.
- No multi-device management (one connection at a time, though several pairing keys can be stored).
- No firmware update, and no editing of the device's Wi-Fi network credentials.
- No automated UI tests; only the protocol library has unit tests.
