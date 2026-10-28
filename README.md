<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="brand/kairos-mark-reversed.svg">
    <img src="brand/kairos-mark.svg" alt="Kairos mark: a camera viewfinder holding a timeline with one red playhead" width="112" height="112">
  </picture>
</p>

<h1 align="center">kairos</h1>

<p align="center">
  <strong>An open source hardware, time-based camera trigger.</strong><br>
  It wakes a camera and starts and stops recording at the exact moment you choose, set from your phone, with no network.
</p>

<p align="center">
  <a href="https://github.com/nprail/kairos/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/nprail/kairos/actions/workflows/ci.yml/badge.svg"></a>
  <img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-14130f">
  <img alt="Device: ESP32" src="https://img.shields.io/badge/device-ESP32-14130f">
  <img alt="Apps: iOS 17+ and Android 8+" src="https://img.shields.io/badge/apps-iOS%2017%2B%20%C2%B7%20Android%208%2B-14130f">
</p>

<p align="center">
  <a href="#why-kairos">Why Kairos</a> ·
  <a href="#how-it-works">How it works</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="#documentation">Documentation</a>
</p>

---

Kairos is an open source hardware trigger that starts a camera at a set time. It was built to record a rocket launch automatically, and it does one thing well: be ready, and press record at the right second, without anyone standing next to the camera.

## Why Kairos

- **Exact timing.** Plan each attempt around one moment, T-0. Kairos wakes the camera, starts recording before T-0 and stops after it, on schedule. Up to 30 attempts per device.
- **Works with your camera.** Trigger any camera with a standard remote trigger port, or a Z CAM over its HTTP API on a wired Ethernet link.
- **Controlled from your phone.** Native iPhone and Android apps connect over Bluetooth Low Energy. You set the schedule, check the countdown, run a test and watch the log, in the field with no network.
- **Hard to misuse.** Pair once with a physical button press, so only your phone can control the device.
- **Small and low power.** It runs on an ESP32-S3 or ESP32-C3 with a real-time clock, starts in under two seconds and draws a fraction of a watt, with Wi-Fi switched off. (The original Raspberry Pi Zero W version still works and is now legacy.)
- **Open.** The device software, both apps and the Bluetooth protocol are MIT licensed and documented in this repository.

## How it works

Each launch attempt has one moment that matters, **T-0**. Kairos works backwards and forwards from it:

```text
 wake camera       start recording          T-0           stop recording
      ●─────────────────●───────────────────●───────────────────●
      │◄─ wakeUpTimeout ─►│◄──  startBefore ──►│◄───  endAfter ───►│
```

You set the three timings once and list your attempts. The device keeps the schedule, and the phone sets its clock every time it connects.

## Quick start

You need an ESP32-S3 or ESP32-C3 board, a camera with a remote trigger port (or a Z CAM with a W5500 Ethernet module), and ideally a DS3231 real-time clock module. Pairing uses the board's BOOT button, so there is no extra button to wire. Then, with [PlatformIO](https://platformio.org) installed:

```sh
git clone git@github.com:nprail/kairos.git
cd kairos/firmware/esp32
pio run -e device -t upload      # ESP32-S3 (use -e device-c3 for an ESP32-C3)
```

Install the app on your phone, press the BOOT button on the board, then tap **Pair** within two minutes. Wiring is in [Hardware](docs/hardware.md), and the full steps are in [Setting up a device](docs/setup.md).

> [!NOTE]
> The original Raspberry Pi Zero W version is **legacy**. It still works with the same apps and protocol, and its setup is at the end of [Setting up a device](docs/setup.md#legacy-raspberry-pi).

## The apps

With the apps you can find and connect to devices, pair with the button, see live status with a countdown to the next event, edit the schedule and camera settings, run a test, manually wake, start, stop and sleep the camera, and watch the device log live.

- **iPhone:** [apps/ios/](apps/ios/README.md), a native SwiftUI app for iOS 17 and later.
- **Android:** [apps/android/](apps/android/README.md), a native Jetpack Compose app for Android 8.0 and later.

### Get the apps

- **iPhone:** the app isn't on the App Store, so you build it yourself with Xcode 16 or later and install it on your iPhone (iOS 17 or later):
  1. `git clone https://github.com/nprail/kairos.git`, then `open kairos/apps/ios/Kairos.xcodeproj`.
  2. In *Signing & Capabilities*, choose your team (a free Apple ID works) and change the bundle identifier to one that is unique to you.
  3. Connect your iPhone, turn on Developer Mode (*Settings › Privacy & Security*), choose it in Xcode and press Run.

  Bluetooth doesn't work in the Simulator. An app signed with a free Apple ID stops opening after 7 days: run it from Xcode again to renew it.
- **Android:** the app is in a closed test on Google Play and is by invitation only. Email [hello@noahprail.com](mailto:hello@noahprail.com) with the email address of your Google account to be added. You can also build it from the source ([apps/android/](apps/android/README.md)).

## Documentation

Read it online at [nprail.github.io/kairos/docs](https://nprail.github.io/kairos/docs/), or browse the files here.

| Read | For |
| --- | --- |
| [Setting up a device](docs/setup.md) | Flashing an ESP32, Bluetooth setup, pairing (and the legacy Raspberry Pi setup) |
| [Hardware](docs/hardware.md) | ESP32 parts, pins, wiring for generic cameras and Z CAMs (and the legacy Pi) |
| [Configuration](docs/configuration.md) | Every setting, and the timeline of an attempt (and the legacy `config.json`) |
| [Bluetooth protocol](docs/ble-protocol.md) | The protocol between the phone and the device |
| [Development](docs/development.md) | Repository layout, running the tests, test vectors |
| [App specification](AGENTS.md) | What the apps do, and the rules for working in this repository |
| [ESP32 firmware](docs/esp32-port.md) | The recommended device: design, status, and what it changes from the legacy Pi version |
| [Brand assets](brand/README.md) | The Kairos mark, colors and fonts |

## License

MIT. See [LICENSE](LICENSE).
