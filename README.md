# Scheduled Camera Trigger

This program schedules a camera to wake up and start and stop recording video at specific times. It was originally built to start a camera automatically for a rocket launch and is highly optimized for that use. It can trigger any camera with a standard remote trigger port, as well as Z CAMs over their HTTP API.

It is optimized for a low power Raspberry Pi Zero W. You configure and control it from a phone over Bluetooth Low Energy, with no network needed.

## What's in this repository

| Path | What |
| --- | --- |
| `trigger/` | The program that runs on the Raspberry Pi (Node.js). |
| `ios/` | The iPhone app (SwiftUI). See [ios/README.md](ios/README.md). |
| `android/` | The Android app (Kotlin, Jetpack Compose, Material 3). See [android/README.md](android/README.md). |
| `docs/BLE_PROTOCOL.md` | The Bluetooth protocol between the phone and the device. |
| `docs/test-vectors.json` | Test vectors that the device and both apps are checked against. |
| `AGENTS.md` | The app specification and the rules for working in this repository. |

## Running the device

Clone the repository and install the dependencies. Node.js 20 is what the tests run on, and the native modules (`onoff`, `hci-socket`) only build on the Pi.

```sh
git clone git@github.com:nprail/scheduled-camera-trigger.git
cd scheduled-camera-trigger
npm install
```

Copy the example config and edit it:

```sh
cp config.example.json config.json
```

Then start the trigger:

```sh
node trigger/index.js
```

By default it reads `./config.json`. Set `CAMERA_CONFIG_FILE` to use another path.

The device writes to this file: it adds a `deviceId` and `configRev` the first time it runs, saves changes made from the apps, and stores the phone pairing key. Keep the file writable and private.

## Config options

See [config.example.json](config.example.json) for an example. You can edit all of these from the apps.

| Option | Description |
| --- | --- |
| `name` | Friendly device name, up to 32 characters (optional). The Bluetooth name is `{name}-{deviceId}`. |
| `cameraType` | Type of camera being triggered: `generic` or `zcam`. |
| `logFile` | File path to store logs at. |
| `buttonGpioPort` | GPIO pin connected to a button. Pressing it opens a 2-minute Bluetooth pairing window (optional). |
| `generic.releaseGpioPin` | GPIO pin connected to the camera's shutter release pin (required for `generic`). |
| `generic.focusGpioPin` | GPIO pin connected to the camera's focus pin (optional). |
| `zcam.cameraIp` | IP address of the Z CAM (required for `zcam`). |
| `wakeUpTimeout` | Seconds before the recording starts to wake up the camera (0 to 3600). |
| `startBefore` | Seconds before T-0 to start recording (0 to 3600). |
| `endAfter` | Seconds after T-0 to stop recording (0 to 3600). |
| `attempts` | Array of launch attempts, up to 30. |
| `attempts[].name` | Friendly name for the attempt, 1 to 32 characters. |
| `attempts[].time` | Exact T-0 of the attempt, in UTC. Must be in the future and different from the other attempts. |

Older configs (plain date strings for `attempts`, `genericReleaseGpioPin`, `zcamCameraIp`, …) are read as this shape automatically. The file is rewritten in the new shape the next time the config is saved.

## Bluetooth control

The device is configured and controlled over Bluetooth Low Energy: set the clock, edit the schedule, run a test, watch the log, and manually control the camera. The protocol is specified in [docs/BLE_PROTOCOL.md](docs/BLE_PROTOCOL.md).

- **Device:** `trigger/ble/`. It talks HCI directly (via [`ble-host`](https://github.com/Emill/node-ble-host)), so stop `bluetoothd` (`sudo systemctl disable --now bluetooth`) and give Node access to the radio: `sudo setcap cap_net_admin=ep $(readlink -f $(which node))`. Setting the clock and blocking Wi-Fi need root (`date`, `timedatectl`, `rfkill`).
- **Clock:** the Pi has no battery-backed clock, so the phone sets the device's clock every time it connects.
- **Wi-Fi is always off:** the device never uses Wi-Fi. It is controlled over Bluetooth, and a Z CAM is wired over Ethernet, so the service runs `rfkill block wifi` every time it starts.
- **Pairing:** press the button wired to `buttonGpioPort`, then tap *Pair* in the app within 2 minutes. Only the paired phone can control the device afterwards. Pressing the button again opens pairing for a new phone.

## The apps

Both apps do the same things: find and connect to devices, pair with the button, show live status with a countdown to the next event, edit the schedule and camera settings, run a test, manually wake, start, stop and sleep the camera, and show the device log live.

- **iPhone:** [ios/](ios/README.md), a native SwiftUI app for iOS 17 and later.
- **Android:** [android/](android/README.md), a native Jetpack Compose app for Android 8.0 and later that follows the system light and dark theme, and uses Material You colors on Android 12 and up.

Bluetooth doesn't work in the iOS Simulator or an Android emulator, so run the apps on a phone. Debug builds have a demo mode that shows every screen with sample data (see [AGENTS.md](AGENTS.md)).

## Tests

| What | Command |
| --- | --- |
| Device (protocol, auth, scheduler, config; no hardware needed) | `npm test` |
| iPhone app's protocol library | `cd ios/CameraTriggerKit && swift test` |
| Android app | `cd android && ./gradlew :app:testDebugUnitTest` |

The test vectors in `docs/test-vectors.json` are generated from the Node implementation (`node test/vectors.js --write`). If the protocol changes, regenerate them, and the tests for the device and both apps will show what drifted.
