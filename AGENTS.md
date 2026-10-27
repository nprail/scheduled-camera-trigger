# AGENTS.md

Guidance for agents working in this repository.

## Repository layout

| Path | What |
| --- | --- |
| `firmware/esp32/` | The recommended device: firmware for an ESP32-S3 or ESP32-C3 (C++, Arduino-ESP32 on PlatformIO), with an external RTC and optional Ethernet for a Z CAM. Controlled over Bluetooth only. See `docs/esp32-port.md`. |
| `firmware/legacy-pi/` (`trigger/`, `test/`, `config.example.json`) | The **legacy** device program for a Raspberry Pi Zero W (Node). The same protocol and behavior as `firmware/esp32/`. It is the reference the test vectors are generated from. |
| `docs/ble-protocol.md` | **The BLE protocol (v2, binary). The source of truth for everything the phone and device exchange.** |
| `protocol/test-vectors.json` | Cross-implementation test vectors, generated from the Node reference (`cd firmware/legacy-pi && node test/vectors.js --write`). The ESP32 firmware and both apps pass the same file. |
| `apps/ios/` | The native iOS app (SwiftUI) and `KairosKit`, a UI-free Swift package for the protocol. |
| `brand/` | Kairos brand assets for the apps: the mark (SVG) and the two fonts. The design system itself is the "Kairos" artifact. |
| `docs/`, `website/` | The user and developer docs (`docs/`, including the protocol spec), and the marketing page (`website/`). The root `docmd.config.json` and `package.json` build `docs/` into `website/docs/` with docmd. |
| `release/`, `scripts/` | The semantic-release configuration (one for the apps, one for the firmware) and the script that writes the app version into both projects. See "Commit messages and releases". |
| `apps/android/` | The native Android app (Kotlin, Jetpack Compose, Material 3). The protocol code is in `app/src/main/java/com/noahprail/kairos/protocol/`. |

The iOS app is a standard Xcode project (`apps/ios/Kairos.xcodeproj`), with no project generator. Run the kit's tests with `cd apps/ios/KairosKit && swift test`.

The Android app is a Gradle project (`apps/android/`, wrapper included, JDK 17+). Run its unit tests with `cd apps/android && ./gradlew :app:testDebugUnitTest`, and build the debug APK with `./gradlew :app:assembleDebug`.

The ESP32 firmware is a PlatformIO project (`firmware/esp32/`). Run its host tests with `cd firmware/esp32 && pio test -e native`, and build it with `pio run -e device` (ESP32-S3) or `pio run -e device-c3` (ESP32-C3). Firmware updates over Bluetooth are signed: the signing tool's tests are `python3 -m unittest discover -s firmware/esp32/tools` (needs `pip install cryptography`), and `pio run -e device-dev` builds the developer firmware that accepts unsigned updates. The tests for the legacy Node device are `cd firmware/legacy-pi && npm test`.

## Keep iOS and Android in sync

**Always make functional changes to both the iOS and Android apps.** The two apps must behave the same. When you add, change or remove a feature, behavior, screen, field, message text, protocol operation or bug fix in one, make the matching change in the other in the same piece of work, and update the app specification below if the behavior it describes changes.

- A change counts as functional if it affects what the user can do or see, how the app talks to the device, or how it handles errors, timing or state. Examples: a new setting, a different confirmation, a new error message, a changed timeout, a protocol change.
- Purely platform-specific work is the exception: build setup, platform permissions, secure storage, navigation and styling that follow each platform's conventions (SwiftUI and Material 3 respectively). Keep these idiomatic for each platform rather than copying the other.
- Protocol changes start in `docs/ble-protocol.md` and the reference test vectors (`protocol/test-vectors.json`). Both apps must pass the same vectors.
- Run both apps' tests before finishing (`swift test` for iOS, `./gradlew :app:testDebugUnitTest` for Android), make sure both still build, and check any changed screen on both platforms. The debug demo mode on each shows screens without Bluetooth hardware, so it works in a simulator or emulator as well as on a phone.
- If you can't change both (for example one toolchain isn't available), say so clearly in your final message and list exactly what is left undone on the other platform. Don't silently ship a change on one platform only.

## Commit messages and releases

Versions are not edited by hand. Every push to `next` runs [semantic-release](https://semantic-release.gitbook.io) (`.github/workflows/release.yml`), which reads the commit messages, picks the next version and publishes it. **Commit messages must follow [Conventional Commits](https://www.conventionalcommits.org):**

```
<type>(<scope>): <summary in the imperative, lower case, no full stop>

<optional body: what and why>

<optional footer: BREAKING CHANGE: what breaks and how to migrate>
```

| Type | Use for | Release |
| --- | --- | --- |
| `feat` | a new capability the user or device gains | minor |
| `fix` | a bug fix | patch |
| `perf` | a performance improvement | patch |
| `feat!`, `fix!`, or a `BREAKING CHANGE:` footer | an incompatible change (for example a protocol version bump) | major |
| `docs`, `refactor`, `test`, `build`, `ci`, `chore`, `style` | no change in behavior | none |

Scopes: `ios`, `android`, `esp32`, `protocol`, `docs`, `site`, `ci`. A change to both apps uses `apps` (`feat(apps): …`). Use one commit per logical change, and the same type and summary for the matching iOS and Android change.

**Two version lines, decided by the files a commit touches** (not by its scope; the scope is for the reader):

| Line | Tag | Covers | Released as |
| --- | --- | --- | --- |
| Apps | `app-v1.2.3` | `apps/`, `brand/` | one number for iOS and Android together; `apps/CHANGELOG.md`; the version and the build number (`versionCode`, `CURRENT_PROJECT_VERSION`) in `build.gradle.kts` and the Xcode project are written by `scripts/set-app-version.mjs` in a `chore(release)` commit; the build number is the release workflow's `GITHUB_RUN_NUMBER`, which only goes up |
| Firmware | `v1.2.3` | `firmware/esp32/` | a GitHub Release with the signed images and `manifest.json`; the tag is the version the device reports |

`protocol/` and `docs/ble-protocol.md` count for both lines, so a `feat(protocol)` releases the apps and the firmware. The firmware has its own line because the apps offer "Update X" for every new firmware release: an app-only fix must not look like a firmware update, and a firmware-only fix must not force an app release. What ties the two together is the protocol version (`v` in Device Info), not the release numbers. `firmware/legacy-pi/` is not released.

Rules:
- Never edit a version number, `apps/CHANGELOG.md` or a `chore(release)` commit by hand, and never create a `v*` or `app-v*` tag yourself.
- A fix that touches both a firmware and an app file releases both, so keep app and firmware changes in separate commits unless they are one change.
- Mark a breaking protocol change with `!` or a `BREAKING CHANGE:` footer, and describe it in the body. Only protocol changes that need the app and the device updated together are breaking.
- Releases come from `next`, not `main`: merge to `next` with the commit messages intact (a rebase, or a squash with a conventional title). A merge commit message is ignored.
- Releases from the `beta` branch are pre-releases (`1.3.0-beta.1`).
- Preview a release without publishing: `npm run release:app -- --dry-run --no-ci` or `npm run release:firmware -- --dry-run --no-ci` (set `GITHUB_TOKEN`, and add `--branches <current branch>` off `next`). The plugin that filters commits by path is `release/by-path.cjs`.
- Never include the Claude co-author in commit messages

---

# Mobile app specification

This section describes what the apps do. It was written from the iOS app and the Android app implements it, so it applies to both. **Behavior must match across the two apps. Platform idioms (navigation, dialogs, permissions, secure storage) follow each platform's conventions.** Where this spec and `docs/ble-protocol.md` disagree, the protocol document wins; report the discrepancy.

## 1. Purpose

A phone app that configures and controls **Kairos**, an ESP32 device (or the legacy Raspberry Pi version) that wakes a camera and starts and stops recording at scheduled "launch attempt" times. The app talks to the device only over Bluetooth Low Energy (no Wi-Fi or internet needed), so it works in the field.

Main jobs:
1. Find nearby devices and connect.
2. Pair once with a physical button press on the device, so only that phone controls it.
3. Set the device clock from the phone (the Pi has no battery-backed clock).
4. Show live status and a countdown to the next event.
5. Edit the schedule, timing and camera settings.
6. Manually test, wake, start, stop and sleep the camera.
7. Show the device log live.
8. Update the device's firmware over Bluetooth, when the user chooses to (section 6.8).

## 2. Architecture (mirror this split)

- **Protocol library, with no UI** (iOS: `KairosKit`). It holds:
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

Read `docs/ble-protocol.md` for the full detail. What the app depends on:

- One GATT service (`6b610001-6169-4f72-8f73-b3e1c5a97d24`) with four characteristics: Device Info (read, binary), Status (read and notify, a fixed 13-byte value), Command (write), Response (notify). A device that can be updated has a fifth, Firmware (`6b610006-…`, write without response), for the image bytes.
- Messages are a compact **binary** format (little-endian): a request is `[op][id][body]`, a response `[0x80][id][status][body]`, an event `[0x81][event][body]`. Each message is split into fragments to fit the ATT write size. Responses and unsolicited events arrive on Response and are reassembled. **One request is in flight at a time; extra callers wait their turn.** Each request has a timeout (10 s by default, 20 s for camera actions); a timeout fails the request.
- The Status value is parsed directly from bytes: flags (recording, camera ready, pairing open, clock valid), phase (idle, awake, recording, test, error), config revision, next event time (Unix seconds, 0 for none), next event type (none, wake, start, stop), attempts remaining, and the index of the attempt that owns the next event. It carries everything the screens show, so there is no separate status request.
- Attempt times are Unix **seconds**; the clock operations use Unix **milliseconds**. Strings are length-prefixed, with names limited to 32 **bytes** of UTF-8. The config is one fixed binary layout (at most about 1.2 KB), the same bytes the device stores.
- Events pushed by the device: `job`, `config`, `test`, `log`, and `fw` (firmware transfer progress; the client keeps it on its own channel, because the uploader waits for it).
- Operations used: `hello`, `auth`, `auth.pair`, `config.get`, `config.set`, `jobs.get` (the state of each attempt, in config order; the app computes the times itself), `camera.wake|start|stop|sleep`, `test.run`, `logs.get` (paged), `logs.subscribe`, `time.get`, `time.set`, and for firmware updates `fw.begin`, `fw.end`, `fw.abort`.
- Errors are codes with structured detail (`invalid_config` carries a field, an attempt index and a reason). The device sends no message text, so the app builds it (section 9).
- Device Info has three optional trailing fields: `caps` (bit0 = can be updated over Bluetooth), `chip` (1 ESP32-S3, 2 ESP32-C3) and `slot` (the largest image, in bytes). A device without them (the legacy Pi, older firmware) is simply not updatable: the Firmware row is hidden.
- Only protocol version **2** is supported. A different version in Device Info or `hello` is an error ("unsupported"), with a message telling the user to update the app.

**Test vectors:** `protocol/test-vectors.json` covers framing, HMAC proofs, Status, Device Info, the config layout, every message and every error. The Android protocol module must pass the same vectors (see `apps/ios/KairosKit/Tests/` for how the iOS tests use them).

## 4. Connection state machine

States: **disconnected → connecting(name) → needsPairing(reason?) → ready**.

Connect sequence, after the user taps a device:
1. State becomes `connecting`; scanning stops; any banner is cleared.
2. Connect, discover the service, subscribe to Status and Response. Time out after **15 s** ("Could not connect to the device.").
3. Read Device Info. If `v != 2`, fail with the unsupported-version error.
4. Create the client and start listening for events and Status notifications.
5. Send `hello`.
6. If the device is paired **and** this phone has a stored key for it: run the auth handshake (section 5).
   - Success → become ready (below).
   - `unauthorized` error → go to `needsPairing` with that error text.
7. Otherwise go to `needsPairing`. The reason is "This device hasn't been paired with a phone yet." if the device is unpaired. If it is paired to another phone, there is no reason text.
8. Any other error: disconnect, return to `disconnected`, show the error as a banner, and resume scanning.

**Becoming ready**, in order:
1. Sync the clock (best effort; a failure doesn't block).
2. Read the Status characteristic for live values.
3. Fetch the config, then the state of each attempt (`jobs.get`).
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

### 6.1 Scan screen (the Kairos lockup)

Content depends on the Bluetooth state:

| State | Shown |
| --- | --- |
| On | List of discovered devices. While the list is empty, a spinner with "Looking for Kairos devices…". Footer: "Stay within a few metres. The first time, you'll pair with the button on the device." |
| Unauthorized (permission denied) | "Bluetooth is off for this app", then "Allow Bluetooth for Kairos in Settings." (iOS) or "Allow Nearby devices access for Kairos in Settings." (Android, the name of its permission), and an **Open Settings** button that opens the app's system settings. Android also has an **Allow** button that asks for the permission again. |
| Off | "Bluetooth is off", "Turn on Bluetooth to find your Kairos devices." Android also has a **Turn on** button that asks the system to enable Bluetooth (iOS apps can't do this). |
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

Title is the device name. Content: a button icon, "Pair with this device", and the text "Press the button on the Kairos device, then tap Pair within two minutes. This phone will then be the only one that can control it." Shows the reason or error in red if there is one. A **Pair** button (replaced by a spinner while working, and disabled then) and a **Cancel** button (disconnects).

### 6.4 Device screen

Title: the device name (or "Kairos" if empty). Toolbar: **Disconnect**. Sections, top to bottom:

**Status**
- Phase title (in the `label` style, uppercase) and an icon: Idle, Camera awake, Recording, Running test, Needs attention. The state is always in the text and the icon shape, never in color alone. Idle uses `muted`; the others use `ink` (section 14).
- A "REC" indicator with a `signal` dot while the Status `recording` flag is set. This is the one place `signal` is used on this card.
- If the Status `cameraReady` flag is false: "Camera not ready. Check the wiring or camera connection." with a warning icon, in `ink`.

**Next**
- The next event type: "Wake the camera", "Start recording" or "Stop recording", the attempt name beneath it, and a **live countdown that ticks every second**: T-minus notation in mono, `T-HH:MM:SS`, never negative.
- An "At" row with the full local date and time.
- If none: "Nothing scheduled".

**Camera**
- **Run test** (disabled unless the phase is idle, or a test is already running). Shows a spinner while running. Test progress arrives as `test` events; show the steps joined with " → " (step names capitalized), ending in "Done" or "Failed: {error}". A failure also shows an error banner.
- Four buttons in a 2×2 grid: **Wake**, **Sleep**, **Start**, **Stop**. They are enabled only when the phase is idle or recording (the device refuses them otherwise). **Start and Stop ask for confirmation first** ("Start recording now?" and "Stop recording now?").
- After each camera action, refresh status.

**Schedule**
- List of jobs: name, launch time (local), and a state tag: a small outlined tag (hairline border, `radius-sm`) whose uppercase text is the state (Pending, Active, Complete, Missed). An active attempt also shows a filled dot. The tags carry no state colors.
- "No attempts scheduled" when empty.
- A link "Edit schedule and settings" to the editor.

**Device**
- **Logs** link.
- **Firmware** link, only when the device can be updated (Device Info `canUpdate`: `caps` bit0, a known `chip` and a `slot`). When the last check found a newer release, a small outlined tag "Update X" sits on the row. That is all an update ever does unasked (section 6.8).
- **Clock**: shows "Not checked", "Set from this phone (was N ms off)" or "In sync (N ms)", with a **Sync now** button (disabled unless idle). Sync now forces a set (threshold 0).
- Read-only rows: ID, Camera (type), Software (firmware version).
- **Forget pairing** (destructive), which asks for confirmation: "Forget this device?" with "To pair again, press the button on the device."

### 6.5 Schedule and Settings editor

Opened from the device screen. Works on a **draft copy** of the config. **Save** is enabled only when the draft differs from the device's config, no save is in progress, and the device is not busy. While saving, the form is disabled.

"Busy" means the live phase is anything other than idle (awake, recording, test and error all count; see section 10). When busy, show a notice at the top (a lock icon and the text, in `ink`): "The device is busy. Changes can be saved when it's idle."

Sections:
1. **Launch attempts** (up to 30). Each attempt has an editable name and a date-and-time picker labelled "Launch (T-0)". Swipe or delete to remove one. **Add attempt** appends "Attempt N", defaulting to the next full hour after now or after the latest existing attempt, whichever is later. Footer: "Times are in your local time zone ({abbrev}). The device works in UTC. Up to 30 attempts." Times are stored on the wire as UTC.
2. **Timing** (each a stepper, 0 to 600 s in steps of 1, shown as a value with a description):
   - Wake camera: "N s before recording" (`wakeUpTimeout`)
   - Start recording: "N s before T-0" (`startBefore`)
   - Stop recording: "N s after T-0" (`endAfter`)
3. **Camera**:
   - **Type** picker: "Remote trigger (GPIO)" (`generic`) or "Z CAM (network)" (`zcam`). A type is offered only if Device Info `cams` lists it (a unit without the Ethernet module lists only `generic`), plus the draft's current type, so an existing selection never disappears.
   - For `zcam`: **Camera IP**, with the placeholder `192.168.1.50`, no auto-capitalization or autocorrect.
4. **Device**: **Name** (optional text).

There are no pin fields: GPIO pins are wiring, kept on the device (`config.json` on the Pi, constants in the ESP32 firmware) and not part of the protocol.

Behavior:
- Empty text fields for optional values are sent as "none". Non-numeric text in a pin field becomes empty. The camera IP must be an IPv4 address (like `192.168.1.50`); anything else is refused in the app before it is sent, as `zcam.cameraIp: must be an IPv4 address like 192.168.1.50`. Names are limited to 32 bytes of UTF-8.
- Attempt identity is local only. Equality for "is the draft changed" compares name and time, not the local ID. Local IDs are never sent to the device.
- The device's own fields (`deviceId`, `configRev`) are not part of the config the app sends. Attempt times are whole seconds.
- **Save** sends `config.set` with the whole config. On success, refresh the config and jobs, then close the editor. On failure, show an alert "Couldn't save" with the error text, and keep the editor open. Validation errors (`invalid_config`) read "{field}: {message}", built from the error's field and reason (section 9).
- The device also pushes `config` events (for example after another phone changes the schedule). When one arrives, refresh config and jobs.

### 6.6 Logs screen

A list of log lines (namespace on the left in small bold secondary text, time on the right, message in monospace below). Empty state: "No log lines yet". Auto-scrolls to the newest line when it opens and when a new line arrives.

Log loading: after becoming ready, page through `logs.get` (100 per page, up to 12 pages) and keep the latest **500** lines. Then call `logs.subscribe` with the last sequence number so the device replays anything missed and streams new lines. New lines are appended (ignoring a sequence number already present) and the list is trimmed to 500.

### 6.7 Error banner

A dismissible banner at the top of every screen, with a warning icon and the message, in `ink` with `ground` text (an inverse surface, with no shadow). Used for connection failures, a lost connection, camera-action errors and test failures. Cleared when a new connection starts.

### 6.8 Firmware screen

Opened from the **Firmware** link. **An update is always the user's choice**: the app never installs on its own, never blocks a device for being out of date, and shows no dialog, notification or banner about a new release.

**This device:** version (Device Info `fw`) and chip.

**Updates** (while idle):
- If the last check found a release that is newer and installable: "Version X is available" with its notes. A **Download** button fetches the image (or takes it from the cache); the downloaded image is then shown with its file name, size, "signed" or "unsigned" and the start of its SHA-256, and **Install…** and **Remove**.
- **Check for updates** always asks GitHub (ignoring the 10-minute window below) and shows the result as text: "Version X is available.", "Up to date (X).", "This device runs a newer version (X) than the latest release (Y).", "This device runs a development build (X), so it can't be compared with the latest release (Y).", "Version Y needs a newer version of this app. Update the app first.", "Version Y can't be installed over Bluetooth on this device. It needs a USB flash.", "The latest release has no firmware for this device (ESP32-S3).", "The release information from GitHub could not be read.", or the network error ("Could not reach GitHub to look for updates. Check the phone's internet connection.").
- Footer: "Updates are always your choice. The device keeps its pairing and schedule."

**From a file:** **Choose a file…** opens the system picker, which takes the firmware and optionally its signature (multi-select; a file ending in `.sig`, or of at most 200 bytes, is the signature). It is checked on the phone before anything is sent: an ESP image for this chip with an application descriptor (`FirmwareImage.check`; the factory image is refused with "That's a factory image. Use the file ending in -app.bin to update a device."), at most `slot` bytes. A file with no signature shows "No signature. A normal device refuses unsigned firmware; developer builds accept it." and an **Add signature file…** action.

**Advanced** (collapsed): **Check for updates when connecting** (on by default), **Include pre-releases**, and **Choose a version…**, a list of GitHub releases; picking one checks it against the device as above and downloads it.

**Install…** asks for confirmation: "Install {title}?" and "The device will restart. Keep the app open until it finishes." An older version adds "This is older than the version on the device." first; a file adds "This file didn't come from a Kairos release download." first. It is disabled, with the reason, unless the phase is idle and the next event is at least 15 minutes away ("The device must be idle to take an update.", "An event is scheduled within 15 minutes. Try again after it.").

**Progress:** Downloading…, Sending… (a bar and a percentage, with **Cancel**), Restarting the device…, Reconnecting… ("Keep this screen open. The device restarts when it's done."). Back is unavailable while busy, and the screen stays on during a transfer. Results: "Updated to X." ("Pairing and the schedule are unchanged."), "The update was rolled back." ("The device restarted on its previous firmware (X). Its log may say why."), or "The update didn't finish." with the error text. Each has **Done**.

**Flow** (`FirmwareUploader`): `fw.begin` (with the 64-byte signature, or zeros), the bytes as `[offset u32][bytes]` writes on the Firmware characteristic within the device's `window`, then `fw.end`. The device's `fw` events say how far it has got; an event that does not move while writes are outstanding means a write was lost, and the uploader sends again from the device's offset. With no event for 3 s it sends the unconfirmed part again, and after 4 such stalls fails with the timeout error. Cancelling sends `fw.abort`. A drop mid-transfer leaves the partial image on the device for 2 minutes: the banner says "The connection was lost during the update. The device keeps what it received for 2 minutes: connect again and install the same firmware to continue."

**After `fw.end`** the device restarts and the link drops. That is expected, not "connection lost": the app stays on the screen showing Reconnecting…, scans for the same device ID (up to 60 s; otherwise it goes back to the scan screen with "The device didn't come back after the update. Check it, then connect again."), connects, authenticates, reads Device Info and runs the normal ready sequence. If the version now equals the expected one (the image's tag; for a file with no tag, any change from the old one) it is "Updated"; otherwise "rolled back".

**Looking for a release** (`UpdateService`): the newest stable release's `manifest.json` from `https://github.com/nprail/kairos/releases/latest/download/manifest.json` (no API, no identifying data sent), kept with its ETag. `releases/latest` never returns a pre-release, so with **Include pre-releases** on the check instead takes the newest non-draft release from the releases list (`https://api.github.com/repos/nprail/kairos/releases?per_page=30`, which has a rate limit) and reads that release's `manifest.json` asset; turning the toggle on or off clears the 10-minute window. **When the device becomes ready**, the app checks in the background if Check on connect is on, at most once every 10 minutes (only a successful check starts the window; inside it, the saved copy answers with no network), with no error shown and no wait. A newer, installable release only sets the "Update X" tag. A downloaded image is verified against the manifest's size and SHA-256, kept in the phone's cache (not backed up; the newest two are kept), and nothing is sent to a device that does not match.

## 7. Live updates

- **Status notifications** (up to once a second while recording) update the live Status values immediately (phase, flags, next event, next attempt, countdown). The only thing to fetch is the config, when the Status config revision differs from the one held locally; then also refresh jobs.
- **`job` events:** re-fetch the job states.
- **`config` events:** re-fetch config and jobs.
- **`test` events:** drive the Run test progress (section 6.4). A step named `done` ends the run.
- **`log` events:** append to the log (section 6.6).
- **`fw` events:** only the firmware uploader reads them (section 6.8).

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
| `invalid_config` | "{field}: {reason}", for example "attempts[2].time: must be in the future" (the reason alone if the field is unknown) |
| `bad_request` | "The device could not understand the request." |
| `not_supported` | "The device does not support that." |
| `too_large` | "The request is too large for the device." |
| `disconnected` | "The device disconnected." |
| `timeout` | "The device did not respond in time." |
| `not_found` | "The device is no longer in range." |
| `unsupported` | "This device uses protocol vN. Update the app." |
| `bad_image` | By reason: "The firmware file is too small to be a Kairos update.", "That isn't a Kairos application update. Use the file ending in -app.bin, not -factory.bin.", "That firmware is for a different chip.", "The firmware didn't arrive intact. Try again.", "The device could not write the update. Try again.", "That firmware isn't signed, and this device only accepts firmware signed by Kairos.", "The firmware's signature isn't valid for this device.", "The device did not receive the whole update. Try again.", "The update stopped because the transfer stalled or a scheduled event came close." |
| `update_blocked` (a `busy` from `fw.begin`) | "The device can't take an update now. It must be idle, with no scheduled event in the next 15 minutes." |
| `internal` | The device's detail text, or "The device had an internal error." |

The `invalid_config` field names are `name`, `cameraType`, `wakeUpTimeout`, `startBefore`, `endAfter`, `zcam.cameraIp`, `attempts`, `attempts[i].name` and `attempts[i].time`. The reasons read: "is required", "is out of range", "is not valid", "must be in the future", "duplicates another attempt", "is too soon: the camera wake-up time has already passed", and "has too many attempts (at most 30)".

## 10. Details worth matching exactly

- **Busy gating differs by feature.** The iOS app treats the device as busy for *saving config* whenever the live phase is not idle (awake, recording, test and error all count). Manual camera buttons are enabled only when idle **or** recording. Run test and Sync now are enabled only when idle. Keep each rule as is. An update needs idle **and** no event within 15 minutes (section 6.8).
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

Both apps have a debug-only demo mode that fills the app with fixture data and skips Bluetooth, so every screen can be checked without a device, in a simulator or emulator or on a phone. Both support the same scenes, which must be kept in step: `scan`, `scanning`, `off`, `unauthorized`, `unsupported`, `connecting`, `pairing`, `pairing-error`, `ready`, `generic`, `recording`, `banner`, `firmware` (an update is available), `firmware-progress` (sending, 42%), `firmware-failed`. The fixtures should match what a real device sends (for example job states `pending`, `active`, `complete`, `missed`).

- **In the app (both):** on the scan screen, tap a hidden spot 7 times to open a "Demo mode" menu listing the scenes, plus **Exit demo** while in demo mode. The spot is the Kairos lockup (mark and wordmark) on Android and the "Stay within a few metres…" hint under the device list on iOS (a SwiftUI large navigation title can't take taps). In a demo scene, **Disconnect**, **Cancel** or Back returns to the scan screen.
- **iOS launch argument:** `-demo <scene>`, optionally `-screen config`, `-screen logs` or `-screen firmware` to open that screen. Code: `apps/ios/Kairos/DemoMode.swift`.
- **Android intent extras:** `--es demo <scene>`, optionally `--es screen config`, `--es screen logs` or `--es screen firmware`, passed to `adb shell am start -n com.noahprail.kairos/.MainActivity`. Code: `apps/android/app/src/main/java/com/noahprail/kairos/DemoMode.kt`.

Demo mode compiles into debug builds only.

## 13. Not in either app (don't assume it)

- No background connection, no notifications, and no widgets. (The one thing the app does without being asked is the update check when it connects, section 6.8: it runs while connected, makes one small request, and can be turned off.)
- No multi-device management (one connection at a time, though several pairing keys can be stored).
- No automatic or scheduled firmware update (an update is always started by the user), and no editing of the device's Wi-Fi network credentials.
- No automated UI tests; only the protocol library has unit tests.

## 14. Brand and look and feel

The apps use the **Kairos** brand system (the "Kairos" design system artifact; the files the apps need are copied to `brand/`). Both apps wear the same brand and keep their platform's native controls (SwiftUI and Material 3).

- **Name.** The wordmark is lowercase `kairos` (Space Grotesk SemiBold, -0.03em). In sentences and OS labels (home-screen name, permission text) it is "Kairos". The scan screen's title is the lockup: the mark, then the wordmark, with a gap of 0.3 of the mark's height.
- **Color.** `ground` is the screen background, `raised` is for cards and rows, `ink` is text and the interactive color (buttons, links, toolbar actions), `muted` is secondary text, `hairline` separates surfaces. Both light and dark themes are supported (the values are in `brand/README.md`). Material You dynamic color is **off** on Android.
- **`signal` (the only hue).** Spend it once per view: the record dot, or the single primary action (the **Pair** button, with `on-signal` text). Never use it for body text. An active attempt is marked by an `ink` dot, not `signal`, so a recording screen has only one signal element.
- **Type.** Space Grotesk for the wordmark, headlines, titles and device names; JetBrains Mono for every time, duration, countdown and config value (pins, IP); the platform's own font (SF Pro, Roboto) for everything else, so the apps stay native. Section headers and state names use the uppercase `label` style.
- **Shape.** Cards and buttons use 8 pt/dp corners, tags and inputs 4. Separate surfaces with hairline borders, not shadows. Motion is functional only.
- **Errors and warnings** use an icon and text, never color alone: the banner is an inverse (`ink`) surface, and destructive actions (Forget pairing) are `ink` text, confirmed by a dialog.
- **App icon.** The reversed small mark (cream brackets, `signal` playhead) on an `ink` tile. Android's adaptive icon also has a monochrome layer for themed icons. iOS also uses the mark and `ground` on its launch screen.
- Fonts are OFL-licensed and bundled (`apps/ios/Kairos/Fonts/`, `apps/android/app/src/main/res/font/`). If a font fails to load, the platform falls back to its system font.
