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
  <img alt="Device: Raspberry Pi Zero W" src="https://img.shields.io/badge/device-Raspberry%20Pi%20Zero%20W-14130f">
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
- **Small and low power.** It runs on a Raspberry Pi Zero W, with Wi-Fi switched off.
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

You need a Raspberry Pi Zero W, a camera with a remote trigger port (or a Z CAM), and a button for pairing. Then, on the Pi:

```sh
git clone git@github.com:nprail/kairos.git
cd kairos
npm install
cp config.example.json config.json   # edit this for your camera and pins
node trigger/index.js
```

Install the app on your phone, press the button on the device, then tap **Pair** within two minutes. Full steps, including the Bluetooth and root setup the Pi needs, are in [Setting up a device](docs/SETUP.md).

## The apps

With the apps you can find and connect to devices, pair with the button, see live status with a countdown to the next event, edit the schedule and camera settings, run a test, manually wake, start, stop and sleep the camera, and watch the device log live.

- **iPhone:** [ios/](ios/README.md), a native SwiftUI app for iOS 17 and later.
- **Android:** [android/](android/README.md), a native Jetpack Compose app for Android 8.0 and later.

## Documentation

Read it online at [nprail.github.io/kairos/docs](https://nprail.github.io/kairos/docs/), or browse the files here.

| Read | For |
| --- | --- |
| [Setting up a device](docs/SETUP.md) | Running Kairos on a Raspberry Pi, Bluetooth setup, pairing |
| [Hardware](docs/HARDWARE.md) | Parts, GPIO pins, wiring for generic cameras and Z CAMs |
| [Configuration](docs/CONFIGURATION.md) | Every `config.json` option, and the timeline of an attempt |
| [Bluetooth protocol](docs/ble-protocol.md) | The protocol between the phone and the device |
| [Development](docs/DEVELOPMENT.md) | Repository layout, running the tests, test vectors |
| [App specification](AGENTS.md) | What the apps do, and the rules for working in this repository |
| [ESP32 port](docs/esp32-port.md) | A proposal for an ESP32 version of the device |
| [Brand assets](brand/README.md) | The Kairos mark, colors and fonts |

## License

MIT. See [LICENSE](LICENSE).
