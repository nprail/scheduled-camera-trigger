# Development

## Repository layout

| Path | What |
| --- | --- |
| [`apps/ios/`](https://github.com/nprail/kairos/blob/main/apps/ios/README.md) | The iPhone app (SwiftUI) and `KairosKit`, its protocol library. |
| [`apps/android/`](https://github.com/nprail/kairos/blob/main/apps/android/README.md) | The Android app (Kotlin, Jetpack Compose, Material 3). |
| [`docs/ble-protocol.md`](ble-protocol.md) | The Bluetooth protocol between the phone and the device. |
| [`protocol/test-vectors.json`](https://github.com/nprail/kairos/blob/main/protocol/test-vectors.json) | Test vectors that the device and both apps are checked against. |
| [`firmware/esp32/`](https://github.com/nprail/kairos/tree/main/firmware/esp32) | The device firmware, for an ESP32-S3 or ESP32-C3 (C++, PlatformIO). This is the recommended device. Design in [esp32-port.md](esp32-port.md). |
| [`firmware/legacy-pi/`](https://github.com/nprail/kairos/tree/main/firmware/legacy-pi) | The legacy device program for a Raspberry Pi Zero W (Node.js). It speaks the same protocol, and is the reference implementation the test vectors are generated from. |
| [`docs/hardware.md`](hardware.md) | Parts, GPIO pins and wiring. |
| [`website/`](https://github.com/nprail/kairos/blob/main/website/README.md) | The one-page marketing website (Tailwind). The docs are built into `website/docs/`. |
| [`docmd.config.json`](https://github.com/nprail/kairos/blob/main/docmd.config.json), `package.json` | Config for the [docmd](https://docmd.io) build that turns `docs/` into the documentation site (run from the repository root). |
| [`brand/`](https://github.com/nprail/kairos/blob/main/brand/README.md) | The Kairos mark and fonts used by the apps. |
| [`AGENTS.md`](https://github.com/nprail/kairos/blob/main/AGENTS.md) | The app specification and the rules for working in this repository. |

## Tests

| What | Command |
| --- | --- |
| ESP32 firmware logic (host tests, no hardware) | `cd firmware/esp32 && pio test -e native` |
| ESP32 firmware build | `cd firmware/esp32 && pio run -e device` (S3) and `pio run -e device-c3` (C3); `-e device-dev` is the developer build that accepts unsigned updates |
| Firmware signing tool | `pip install cryptography && python3 -m unittest discover -s firmware/esp32/tools` |
| iPhone app's protocol library | `cd apps/ios/KairosKit && swift test` |
| Android app | `cd apps/android && ./gradlew :app:testDebugUnitTest` |
| Legacy Raspberry Pi program (protocol, auth, scheduler, config; no hardware needed) | `cd firmware/legacy-pi && npm test` |

The test vectors in `protocol/test-vectors.json` are generated from the Node implementation (`cd firmware/legacy-pi && node test/vectors.js --write`). If the protocol changes, regenerate them, and the tests for both devices (ESP32 and the legacy Node program) and both apps will show what drifted.

Make functional changes to both apps (see [AGENTS.md](https://github.com/nprail/kairos/blob/main/AGENTS.md)).

## Documentation site

The pages in `docs/` are published at `/docs/` on the project site, built with [docmd](https://docmd.io). Preview and build from the repository root:

```sh
npm ci
npm run dev       # live preview
npm run build     # writes website/docs/ (not committed)
npm run validate  # checks internal links
```

A GitHub Actions workflow (`.github/workflows/pages.yml`) builds the docs and deploys `website/` to GitHub Pages on every push to `main` that changes the site or the docs. Keep links between pages relative (`setup.md`), and link to files outside `docs/` with full GitHub URLs.
