# Development

## Repository layout

| Path | What |
| --- | --- |
| [`trigger/`](https://github.com/nprail/kairos/tree/main/trigger) | The program that runs on the Raspberry Pi (Node.js). |
| [`ios/`](https://github.com/nprail/kairos/blob/main/ios/README.md) | The iPhone app (SwiftUI) and `KairosKit`, its protocol library. |
| [`android/`](https://github.com/nprail/kairos/blob/main/android/README.md) | The Android app (Kotlin, Jetpack Compose, Material 3). |
| [`docs/ble-protocol.md`](ble-protocol.md) | The Bluetooth protocol between the phone and the device. |
| [`docs/test-vectors.json`](https://github.com/nprail/kairos/blob/main/docs/test-vectors.json) | Test vectors that the device and both apps are checked against. |
| [`esp32/`](https://github.com/nprail/kairos/tree/main/esp32) | The device side on an ESP32-S3 (C++, PlatformIO). Design in [esp32-port.md](esp32-port.md). |
| [`docs/hardware.md`](hardware.md) | Parts, GPIO pins and wiring. |
| [`site/`](https://github.com/nprail/kairos/blob/main/site/README.md) | The one-page marketing website (Tailwind). The docs are built into `site/docs/`. |
| [`docmd/`](https://github.com/nprail/kairos/tree/main/docmd) | Config for the [docmd](https://docmd.io) build that turns `docs/` into the documentation site. |
| [`brand/`](https://github.com/nprail/kairos/blob/main/brand/README.md) | The Kairos mark and fonts used by the apps. |
| [`AGENTS.md`](https://github.com/nprail/kairos/blob/main/AGENTS.md) | The app specification and the rules for working in this repository. |

## Tests

| What | Command |
| --- | --- |
| Device (protocol, auth, scheduler, config; no hardware needed) | `npm test` |
| iPhone app's protocol library | `cd ios/KairosKit && swift test` |
| Android app | `cd android && ./gradlew :app:testDebugUnitTest` |
| ESP32 firmware logic (host tests, no hardware) | `cd esp32 && pio test -e native` |
| ESP32 firmware build | `cd esp32 && pio run -e device` |

The test vectors in `docs/test-vectors.json` are generated from the Node implementation (`node test/vectors.js --write`). If the protocol changes, regenerate them, and the tests for both devices (Node and ESP32) and both apps will show what drifted.

Make functional changes to both apps (see [AGENTS.md](https://github.com/nprail/kairos/blob/main/AGENTS.md)).

## Documentation site

The pages in `docs/` are published at `/docs/` on the project site, built with [docmd](https://docmd.io). Preview and build from the `docmd/` folder:

```sh
cd docmd
npm ci
npm run dev       # live preview
npm run build     # writes site/docs/ (not committed)
npm run validate  # checks internal links
```

A GitHub Actions workflow (`.github/workflows/pages.yml`) builds the docs and deploys `site/` to GitHub Pages on every push to `main` that changes the site or the docs. Keep links between pages relative (`setup.md`), and link to files outside `docs/` with full GitHub URLs.
