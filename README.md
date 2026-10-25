# Scheduled Camera Trigger

This program is built to schedule a camera to wake up and start/stop recording video at specific times. It was originally built to automatically start a camera for a rocket launch and is highly optimized for that use. It can trigger any camera with a standard remote trigger port as well as ZCams over their HTTP API. 

It is optimized for use on a low power Raspberry Pi Zero W. 

## Usage

First, clone the repository and switch to that directory.

```sh
git clone git@github.com:nprail/scheduled-camera-trigger.git

cd scheduled-camera-trigger
```

Install the dependencies. Node.js is required for this.

```sh
npm install
```

Then copy the config file.

```
cp config.example.json config.json
```

And finally start up the server!

```sh
node trigger/index
```

## Config Options

See [config.example.json](config.example.json) for an example config file.

| Option                    | Description                                                                                                                       |
| ------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `cameraType`              | Type of camera being triggered. Can be either `generic` or `zcam`                                                                 |
| `logFile`                 | File path to store logs at                                                                                                        |
| `buttonGpioPort`          | GPIO pin connected to a button. Pressing it re-enables Wi-Fi and opens a 2-minute Bluetooth pairing window (optional)             |
| `generic.releaseGpioPin`  | GPIO pin on the Raspberry Pi that is connected to the camera's shutter release pin (required when the `cameraType` is `generic`)  |
| `generic.focusGpioPin`    | GPIO pin on the Raspberry Pi that is connected to the camera focus pin (optional)                                                 |
| `zcam.cameraIp`           | IP address of the Z CAM (required when the `cameraType` is `zcam`)                                                                |
| `wakeUpTimeout`           | Seconds before the recording is started to wake up the camera.                                                                    |
| `startBefore`             | Seconds before ignition time to start the recording.                                                                              |
| `endAfter`                | Seconds after ignition time to end the recording.                                                                                 |
| `attempts`                | Array of launch attempts                                                                                                          |
| `attempts.name`           | Friendly name for the launch attempt                                                                                              |
| `attempts.time`           | Exact T-0 of the launch attempt in UTC                                                                                            |

Older configs (plain date strings for `attempts`, `genericReleaseGpioPin`, `zcamCameraIp`, …) are migrated to this shape automatically on startup.

## Bluetooth control

The device is configured and controlled over Bluetooth Low Energy: set the clock, edit the schedule, run a test, watch the log, and manually control the camera, all with no network. The protocol is specified in [docs/BLE_PROTOCOL.md](docs/BLE_PROTOCOL.md).

- **Device:** `trigger/ble/`. It talks HCI directly (via [`ble-host`](https://github.com/Emill/node-ble-host)), so stop `bluetoothd` (`sudo systemctl disable --now bluetooth`) and give Node access to the radio: `sudo setcap cap_net_admin=ep $(readlink -f $(which node))`. Date/Wi-Fi control additionally needs root (`date`, `timedatectl`, `rfkill`).
- **Pairing:** press the button wired to `buttonGpioPort`, then tap *Pair* in the app within 2 minutes. Only the paired phone can control the device afterwards.
- **iPhone app:** [ios/](ios/README.md), a native SwiftUI app.
- **Tests:** `npm test` (protocol, auth, scheduler and config behaviour, no hardware needed).
