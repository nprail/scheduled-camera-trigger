# Configuration

The device reads its settings from `config.json` (see [config.example.json](https://github.com/nprail/scheduled-camera-trigger/blob/main/config.example.json) for an example, and [SETUP.md](SETUP.md) for where the file lives). You can edit all of these from the apps.

## Options

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

## Timeline of an attempt

Each attempt has one moment that matters, T-0. The three timings place everything else around it:

```plaintext
 wake camera       start recording          T-0           stop recording
      ●─────────────────●───────────────────●───────────────────●
      │◄─ wakeUpTimeout ─►│◄──  startBefore ──►│◄───  endAfter ───►│
```

## Older configs

Older configs (plain date strings for `attempts`, `genericReleaseGpioPin`, `zcamCameraIp`, …) are read as this shape automatically. The file is rewritten in the new shape the next time the config is saved.
