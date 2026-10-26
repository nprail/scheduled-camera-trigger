# Configuration

A Kairos device has a handful of settings. You set them all from the apps, and the device keeps them across power loss (in flash storage on the ESP32). A new device starts with no attempts and the default timings, and can be configured right after it is paired. The Raspberry Pi version is legacy and also reads some settings from a file, described [at the end](#legacy-configjson-raspberry-pi).

## Settings

The names below are the ones used in the Bluetooth protocol and in the legacy `config.json`.

| Setting | Description |
| --- | --- |
| `name` | Friendly device name, up to 32 bytes (optional). The Bluetooth name is `{name}-{deviceId}`. |
| `cameraType` | Type of camera being triggered: `generic` (a remote trigger port) or `zcam` (a Z CAM over Ethernet). A device without the Ethernet module only offers `generic`. |
| `zcam.cameraIp` | IP address of the Z CAM (required for `zcam`). |
| `wakeUpTimeout` | Seconds before the recording starts to wake up the camera (0 to 3600). |
| `startBefore` | Seconds before T-0 to start recording (0 to 3600). |
| `endAfter` | Seconds after T-0 to stop recording (0 to 3600). |
| `attempts` | Array of launch attempts, up to 30. |
| `attempts[].name` | Friendly name for the attempt, 1 to 32 bytes. |
| `attempts[].time` | Exact T-0 of the attempt, in UTC. Must be in the future and different from the other attempts. |

GPIO pins are not settings. They describe how the device is wired, so each device keeps them itself: they are constants in the firmware on the ESP32 (see [Hardware](hardware.md#pins)), and entries in `config.json` on the Pi. The apps and the Bluetooth protocol do not carry them, and saving a schedule from a phone leaves them as they are.

## Timeline of an attempt

Each attempt has one moment that matters, T-0. The three timings place everything else around it:

```plaintext
 wake camera       start recording          T-0           stop recording
      ●─────────────────●───────────────────●───────────────────●
      │◄─ wakeUpTimeout ─►│◄──  startBefore ──►│◄───  endAfter ───►│
```

## Legacy: config.json (Raspberry Pi)

The legacy Pi program reads its settings from `config.json` (see [config.example.json](https://github.com/nprail/kairos/blob/main/firmware/legacy-pi/config.example.json) for an example, and [setup.md](setup.md#legacy-raspberry-pi) for where the file lives). It holds the settings above, which you can still edit from the apps, plus these, which are only in the file:

| Option | Description |
| --- | --- |
| `logFile` | File path to store logs at. |
| `buttonGpioPort` | GPIO pin connected to a button. Pressing it opens a 2-minute Bluetooth pairing window (optional). |
| `generic.releaseGpioPin` | GPIO pin connected to the camera's shutter release pin (required for `generic`). |
| `generic.focusGpioPin` | GPIO pin connected to the camera's focus pin (optional). |

Older configs (plain date strings for `attempts`, `genericReleaseGpioPin`, `zcamCameraIp`, …) are read as the current shape automatically. The file is rewritten in the new shape the next time the config is saved.
