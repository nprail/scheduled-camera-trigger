# Design: firmware updates from the app

**Status:** Built, not yet run on hardware. It extends protocol v2 additively (no version bump). The user guide is [Updating the firmware](updating-firmware.md); the wire format is `fw.*` in the [protocol](ble-protocol.md). This page keeps the reasoning, and the checklist for the first run on hardware (§10).

Where the build differs from the first draft of this plan: signing is in from the start (§8); the transfer events are `0x05`; a refused `fw.begin` shows its own message in the apps (`update_blocked`); the `.sig` file is the 64 raw bytes (the apps also take 128 hex characters); and the version of a file is read from a `KAIROSFW` tag compiled into every image (§6.2), not from the ESP-IDF application descriptor.

## 1. Goal and scope

Update an ESP32 Kairos device from the phone over Bluetooth, with no USB cable. The phone gets the firmware from one of two places:

1. **GitHub Releases.** The app finds the newest release, downloads the right binary for the device's chip, and sends it.
2. **A manual file.** The user picks a `.bin` (a local `pio run` build, a CI artifact, a beta).

In scope: ESP32-S3 and ESP32-C3 firmware, the BLE transfer, both apps, and the release pipeline.

Out of scope: the legacy Raspberry Pi (it reports "not supported" and the apps hide the section), updating the bootloader or partition table (still USB), and Wi-Fi OTA.

What the flash layout already gives us (`firmware/esp32/partitions*.csv`): two app slots (`app0`, `app1`), `otadata`, and a frozen `nvs`. A slot swap leaves NVS alone, so pairing, bonds and the schedule survive.

## 2. Principles and decisions

**Updates are always optional.**

- The device never updates itself, and never fetches anything.
- The app never installs anything without the user choosing to, in that session. There is no auto-install, no scheduled update and no "update before you continue" gate.
- The app **checks** for a new release when it connects (§7.1), but a check only ever produces a badge.
- An out-of-date device is never blocked. The apps keep working with every older firmware that speaks protocol v2, so new protocol features must be additive and the app must handle their absence.
- Nothing nags. The only result of a check is a quiet "Update available" badge on the Firmware row (§7.1). It makes no notification, no dialog and no banner, and a failed check shows nothing.
- A user can always decline or cancel, and stay on the version they have.

| Question | Decision |
| --- | --- |

| Question | Decision |
| --- | --- |
| Where do binaries live? | GitHub Releases, as now. The release workflow already attaches `kairos-<chip>-<ver>-app.bin` and `SHA256SUMS`. Add a `manifest.json` (§5). |
| Which file is flashed over BLE? | Only the `-app.bin`. The `-factory.bin` is never accepted (§6.3). |
| Who downloads? | The phone, over HTTPS, ahead of time. The device never touches the network. The field often has no signal, so the app caches downloads (§7.3). |
| Data path | A new write-without-response characteristic for the image bytes, with control ops and progress events on the existing channel (§3). The 2 KB, one-request-at-a-time messaging is too slow for 1.5 MB. |
| Trust | Pairing, SHA-256, chip and image checks, **and a signature on every release** that the device verifies before it commits (§8). |
| Safety net | Write to the inactive slot, verify, switch, and roll back if the new image doesn't confirm itself (§4.3). **Confirm rollback works on the pinned core before anything else (§9, phase 0).** |

## 3. Protocol additions (`docs/ble-protocol.md` first, then vectors)

All changes are appended fields and new codes, so existing clients keep working.

### 3.1 Device Info: new trailing fields

Appended after `name` (parsers ignore trailing bytes):

| Field | Type | Meaning |
| --- | --- | --- |
| `caps` | u8 | bit0 = supports firmware update. Absent or 0 on the Pi. |
| `chip` | u8 | 1 = ESP32-S3, 2 = ESP32-C3. Selects the right release asset. |
| `slot` | u32 | Largest image the inactive slot can hold (0x1F0000 on the S3, 0x1D0000 on the C3). |

### 3.2 Firmware characteristic

New characteristic `6b610006-6169-4f72-8f73-b3e1c5a97d24`, **Write Without Response**, authenticated session only. Each write is `[offset u32][bytes…]` (at most MTU − 3 bytes in total). The offset lets the device drop a write that arrives out of order or twice and ask the phone to rewind.

### 3.3 Operations

| Op | Code | Request | Response |
| --- | --- | --- | --- |
| `fw.begin` | 0x30 | `size u32`, `sha256[32]`, `sig[64]` (all zeros if unsigned, §8) | `offset u32` (0, or the resume point), `window u16` (bytes the phone may have unacknowledged) |
| `fw.end` | 0x31 | — | `rebootInMs u16`. The device checks the hash, sets the boot slot, then reboots. |
| `fw.abort` | 0x32 | — | — |

- `fw.begin` is refused with `busy` unless the phase is idle **and the next scheduled event is at least 15 minutes away** (so an update never races a launch). The app checks the same rule first and explains it.
- Resume: if a transfer with the same `sha256` is already open on this device (it survives a disconnect for 2 minutes, in RAM only), `fw.begin` returns its current `offset`. After a device reset, it starts from 0.
- `fw.begin` also allocates the slot and starts a running SHA-256. The device erases and writes as bytes arrive.

### 3.4 Event and errors

- `bad_image` reasons also include "signature missing" and "signature invalid".
- Event `fw` (0x05): `state u8` (0 receiving, 1 verifying, 2 ready to reboot, 3 failed), `offset u32` (next byte expected), `reason u8` (when failed). Sent every `window / 4` bytes, and immediately on any gap (the phone rewinds to `offset`) or failure.
- New error `bad_image` with a reason byte: size, header (not an app image), chip mismatch, hash mismatch, write failure.
- Reused errors: `busy`, `too_large` (bigger than `slot`), `unauthorized`, `not_supported`.
- If the next event comes within 2 minutes during a transfer, the device aborts, discards the partial image and keeps running the old firmware.

### 3.5 Throughput

With an MTU of 247 or more and a short connection interval (the device asks for about 15 ms, and 2M PHY where available), expect roughly 10 to 30 KB/s. A 1.2 MB image takes between one and two minutes, and may reach a few minutes. Measure in phase 0.

## 4. Firmware (`firmware/esp32/`)

### 4.1 Receive path

- A new `lib/kairos/` module (pure, host-testable): the offset and window state machine, the running SHA-256 and the `fw.*` message codecs. The radio and flash code stay in `src/platform/`, as with the existing protocol code.
- Flash writes go through `esp_ota_begin`/`esp_ota_write`/`esp_ota_end` (or the Arduino `Update` class) from a task, fed by a ring buffer, so the BLE callback never blocks on a flash erase.
- Check the image header on the first chunk: magic `0xE9`, the chip ID in the header equals this chip, and the app-descriptor magic (`0xABCD5432`) sits at offset 32. Reject bad images early with `bad_image`.

### 4.2 Commit

At `fw.end`: finish the hash and compare it to `begin`, then `esp_ota_end` (validates the image), `esp_ota_set_boot_partition`, log it, notify, wait about 500 ms so the last notification gets out, then restart.

### 4.3 Confirm and roll back

The new image starts "pending verify" and confirms itself (`esp_ota_mark_app_valid_cancel_rollback()`) only when it has run for 30 seconds **and a phone has authenticated over Bluetooth with it**. Advertising alone is not enough: hardware testing showed that an image whose connections fail would otherwise have kept its slot. If it resets before confirming (a power cycle included), the bootloader goes back to the old slot.

**Risk:** this needs `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE` in the prebuilt Arduino bootloader (`docs/esp32-port.md` §4 already flags this). Check the pinned `pioarduino` build in phase 0. If it's missing there are two options:

- A software fallback: a boot counter in NVS plus manual `otadata` switching. It only covers failures after the app starts, not a bootloader-level crash.
- Rebuild with Arduino as an IDF component (the same change secure boot would need).

**Do not ship OTA to the field until one of these is proven with a deliberately crashing image.**

### 4.4 Reporting

Log "updating from X to Y", "verified", and after boot "running Y, confirmed" or "rolled back from Y". `fw` in Device Info is already the injected `FW_VERSION`.

## 5. Release pipeline (GitHub as the binary store)

Changes to `.github/workflows/release.yml`:

1. Keep `kairos-<chip>-<ver>-app.bin`, `-factory.bin` and `SHA256SUMS`.
2. Generate **`manifest.json`** and attach it:

```json
{
  "version": "1.3.0",
  "protocol": 2,
  "minFrom": "1.2.0",
  "notes": "Short release note, or a URL to the release page",
  "images": {
    "esp32s3": { "file": "kairos-esp32s3-1.3.0-app.bin", "size": 1187344, "sha256": "…", "sig": "…" },
    "esp32c3": { "file": "kairos-esp32c3-1.3.0-app.bin", "size": 1160192, "sha256": "…", "sig": "…" }
  }
}
```

   - `protocol` is the protocol version the image speaks. The app warns before installing an image newer than the app understands, because the app could not talk to the device afterwards.
   - `minFrom` is the oldest firmware that may update directly to this one. Raise it when a release needs a USB flash (a partition or NVS change).
3. Mark tags with a hyphen (`v1.3.0-beta.1`) as **pre-releases** (`gh release create --prerelease`). Today every tag becomes a normal release.
4. Fail the build if the `-app.bin` is larger than the slot or the manifest is invalid, so a broken release cannot be published.
5. Sign each image (§8) and attach `kairos-<chip>-<ver>-app.bin.sig`, and put the same signature in the manifest as `sig`. The private key is a GitHub Actions secret and never leaves CI.

**How the app finds releases.** Newest stable: `https://github.com/nprail/kairos/releases/latest/download/manifest.json`. It has no API rate limit. The list of all versions (older ones, pre-releases, downgrades) uses `GET https://api.github.com/repos/nprail/kairos/releases` with caching. The repository is fixed in the app, not user-supplied. The asset is then `…/releases/download/<tag>/<file>`.

## 6. The two sources in the apps

### 6.1 GitHub

1. **Check** (a button on the Firmware screen, only while the phone has internet): fetch the manifest and compare it with the device's `fw` using semantic versions. Show "Up to date", "Update available (X → Y)" with the notes, or "Newer than any release" for a dev build.
2. **Gate on the manifest:** `images[chip]` must exist, `minFrom` must be ≤ the device's version (else "This update needs a USB flash", with a link to the docs), and `protocol` must be one the app supports.
3. **Download** to the cache (§7.3), verify `size` and `sha256` against the manifest, then offer **Install**.
4. **Advanced** (collapsed by default): a toggle for pre-releases, and "Choose a version" for older releases, which needs an extra confirmation to downgrade.

### 6.2 Manual file

A **Choose file…** action opens the system picker (`.bin`). The app preflights locally before touching the device:

- The size is ≤ `slot` and above a sanity minimum.
- The image header magic is `0xE9`, and the chip ID matches `chip`.
- The app descriptor magic is at offset 32. This is what tells an app image from the factory image (both start with `0xE9`).
- An optional matching `.sig` file (the release attaches one per image). The app sends it with `fw.begin`.
- Show the filename, size, SHA-256 and whether it is signed, plus the version string if it can be read (see the open question on versions), then a confirmation. A file with no signature says: "This file isn't signed. This device only accepts firmware signed by its maker, so it will probably be refused." (Shown for manual files only.)
- Unsigned builds are for developers: a device built with the `device-dev` environment (`KAIROS_ALLOW_UNSIGNED`) accepts them, as does a device built with the developer's own public key (§8).

The device performs the same checks, so a modified app cannot bypass them.

### 6.3 Never the factory image

The factory binary erases pairing and the schedule when written at `0x0`. OTA writes only the app slot and so never needs it. Both the app and the device reject it. Docs should say plainly: use `-app.bin` for OTA and updates, `-factory.bin` only for a blank board over USB.

## 7. App behaviour (both apps, same behavior)

### 7.1 Screens

A **Firmware** row in the Device section (visible only when `caps` bit0 is set) opens a screen.

**Check on connect.** Once the device is ready (§4 of the spec), the app checks GitHub in the background for a newer release than the device's `fw`:

- It uses `releases/latest/download/manifest.json` (stable only, because GitHub's `latest` skips pre-releases; with Include pre-releases on, the newest release from the releases API is used instead). The request carries no device ID, version or other identifying data, so GitHub only sees the phone's IP address.
- At most once every 10 minutes per phone, across devices. The manifest is cached with its ETag (a repeat check is a cheap conditional request), and a connect inside the window reuses the cached result (so it works offline). Only a successful check starts the window, so a failure doesn't block the next connect.
- No internet, a timeout (5 s), a bad manifest or a rate limit: it fails silently and the row shows no badge. The check never delays or blocks the connect sequence.
- If a newer release exists for this chip and passes the gates in §6.1 (`minFrom`, `protocol`), the Firmware row shows an "Update available (Y)" badge. That is all it does.
- **Check for updates** on the screen always checks, ignoring the 10-minute window, and shows the result, including errors and "Up to date".
- A user setting, "Check for updates when connecting" (on by default), turns the check off. Prefer a plain toggle in the Firmware screen.

- Current version, chip, and the two sources (Check for updates, Choose file).
- A progress view: Downloading, Sending (a determinate bar with KB/s and time remaining), Verifying, Restarting, Reconnecting, Done or Failed.
- Nothing starts without an explicit **Install** tap, and Cancel is available until the device reaches "ready to reboot".

### 7.2 State and flow

1. Preflight (§6), then `fw.begin` (a refusal shows the `busy` explanation, including the "next event in N min" reason).
2. Stream with a window: keep at most `window` bytes unacknowledged. On each `fw` event, advance. On a gap, rewind to the reported `offset`.
   - iOS: honour `canSendWriteWithoutResponse` and `peripheralIsReady(toSendWriteWithoutResponse:)`.
   - Android: wait for `onCharacteristicWrite` per write, and keep the GATT queue to one operation.
3. `fw.end`, then expect the link to drop. This **is not** the "connection lost" case: the model enters an `updating` state, shows "Restarting the device…", scans for the same device ID and reconnects (up to 60 s), then runs the normal connect sequence.
4. Check Device Info `fw` after the reconnect. If it equals the expected version: "Updated to Y". If it is still the old one: "The update was rolled back" with the log lines attached to the error.
5. Keep the screen awake during the transfer (iOS idle timer, Android `FLAG_KEEP_SCREEN_ON`). If the app is backgrounded or the link drops mid-transfer, the device keeps the partial transfer for 2 minutes. On return, reconnect, re-auth and resume from `offset`.

### 7.3 Cache

Downloaded images are kept in the app's cache directory (not backed up), named by SHA-256. Re-checking reuses a file with a matching hash. A "Downloaded" state is shown so the user can install in the field with no signal. Old versions are trimmed to the most recent two.

### 7.4 Spec and parity

- The background check on connect is a deliberate exception to "no background" in §13 of the app spec: it is foreground only, it runs while connected, and it makes one small request. Update §13 accordingly.
- Add the Firmware screen, the new errors (`bad_image` reasons, "needs a USB flash", "protocol newer than this app") and the connection `updating` state to the `AGENTS.md` app spec (§3, §4, §6, §9), and to both apps in the same change.
- Demo mode scenes (both): `firmware` (update available), `firmware-progress` (mid-transfer), `firmware-failed`.
- The protocol code lives in `KairosKit` and `app/…/protocol/`. Platform-specific parts follow each platform's conventions (iOS `URLSession` and `fileImporter`, Android OkHttp or `HttpURLConnection` and the Storage Access Framework).

## 8. Trust and signing

| Threat | Coverage |
| --- | --- |
| A stranger flashes a unit | Pairing. Only the paired phone can call `fw.*`, and updates are refused outside idle. |
| Corrupt download or transfer | SHA-256 (manifest, phone and device) and BLE's link-layer reliability. |
| Wrong chip or wrong file type | Header, chip and app-descriptor checks on both sides. |
| A compromised release, a hostile download or a malicious paired phone | **Signature verified by the device.** |

**Design.**

- **Algorithm:** ECDSA P-256 over `SHA-256(image)`, using mbedTLS, which the Arduino core already includes. The signature is 64 bytes (r and s), sent in `fw.begin`. The device checks it against its embedded public key when the hash is final, before `esp_ota_set_boot_partition`. A bad signature leaves the running slot untouched.
- **Keys:** the private key is a GitHub Actions secret (and an offline backup). The public key is committed in the firmware source (`lib/kairos/`).
- **CI:** the release job signs each `-app.bin` (after the build and tests), writes the `.sig` files, puts the signatures in `manifest.json`, and fails if a signature doesn't verify against the committed public key.
- **Key rotation:** the firmware may embed more than one public key (the current and the next). To retire a key, ship a release signed by the old key that adds the new one, then switch signing. The loss of the private key then means a USB flash for existing units, so keep the backup.
- **Forks and developers:** a fork replaces the public key with its own (a build flag). A local build uses the `device-dev` environment, which accepts unsigned images, and **prints that it is unsafe at boot and in the device log**. The release workflow never builds `device-dev`.
- **What it does not give us:** this is not secure boot. Someone with USB access can still flash anything. Hardware secure boot and flash encryption stay unavailable until the firmware moves to Arduino-as-an-IDF-component.
- **Downgrades:** an old release is still validly signed, so it installs. That is deliberate (updates are optional and so is staying current), and it needs the extra confirmation in §6.1.

## 9. Phases

| # | Phase | Done when |
| --- | --- | --- |
| 0 | **Spike** on a real S3 and C3: is bootloader rollback enabled in the pinned core? A fake receiver writing to the slot over BLE: measure throughput at the real MTU, flash-write stalls, and phone background behavior on both platforms. | Rollback proven with a crashing image, and a measured transfer time for a full image. |
| 1 | **Protocol:** generate the key pair and commit the public key. Write §3 into `docs/ble-protocol.md`, add the ops, event and errors to the Node reference codec, and regenerate `protocol/test-vectors.json`. | Vectors generated and reviewed. |
| 2 | **Firmware:** `device-dev` environment, `PROJECT_VER` from `version.py`, receive module, checks, commit, confirm and roll back, logs. Host tests for the state machine, the rewind and resume, and hash handling. | `pio test -e native` and both `pio run` builds pass. A USB-flashed N upgrades to N+1 over BLE. |
| 3 | **Protocol libraries** in KairosKit and Android, passing the same vectors. | `swift test` and `./gradlew :app:testDebugUnitTest` pass. |
| 4 | **Apps:** Firmware screen, manual file flow, update flow, `updating` connection state, demo scenes, `AGENTS.md` spec. Manual source first, since it needs no network. | Both apps update a device from a picked file. |
| 5 | **GitHub source:** release workflow (manifest, signing, pre-releases, size check), manifest fetch, download and cache, gating. | A tagged release is found and installed by both apps. |
| 6 | **Docs:** user guide ("Updating the firmware"), `docs/esp32-port.md` §14 (replace "BLE OTA (future)"), `docs/setup.md`, troubleshooting. Document the signing model and the `device-dev` environment for developers. | Pages linked from `docs/index.md` and the docmd nav. |

The first OTA-capable firmware still has to go on over USB. Everything before it can only be updated over USB, so say so in the release notes for that version.

## 10. Test checklist (hardware, both chips, both phones)

- Update N → N+1 from GitHub and from a manual file. After it: pairing, bonds, schedule and clock all survive, and no re-pair is needed.
- Interrupt: kill the app, turn off the phone Bluetooth, walk out of range, and cut device power at 50% and at "verifying". The old firmware must still boot. Resume works within 2 minutes.
- Reject: an unsigned image, a signature from another key, a valid signature with a modified image, wrong chip, the factory image, truncated file, one flipped byte, an oversized file, an old `minFrom`. Each shows a clear message and leaves the device untouched.
- Rollback: an image that panics at boot, and one that boots but never starts BLE. The device returns to the old slot.
- Refusals: during a recording, a test, an awake phase, and with an event less than 15 minutes away. An event arriving mid-transfer aborts it.
- Update with the phone offline (cached download), and with poor signal.
- Downgrade confirmation, pre-release toggle, and a newer protocol than the app.

## 11. Decided, and what is left

Decided:

- Signing is in v1 (§8), with ECDSA P-256 and a release key held as the `KAIROS_SIGNING_KEY` repository secret.
- Refuse an update unless the next event is at least 15 minutes away.
- Pre-releases and older versions are behind an Advanced toggle.
- Updates are always optional (§2). The app checks on connect (at most every 10 minutes, a "Check for updates" tap always checks), but a check only ever shows a badge.
- The `device-dev` and `device-c3-dev` PlatformIO environments exist, accept unsigned images, and are never built by the release workflow.
- A manual file's version is read from the `KAIROSFW` tag in the image.
- Rollback: the pinned core's bootloader and app are built with rollback enabled (`CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE`, `CONFIG_APP_ROLLBACK_ENABLE`), so no software fallback was needed.

Left to do on hardware (§10): the rollback from a deliberately crashing image, the real transfer speed, and the phone-side behavior (background, reconnecting after the restart) on real phones.

Left to decide: the offline backup of the signing key and who can rotate it. Losing it means a USB flash for every unit (§8).
