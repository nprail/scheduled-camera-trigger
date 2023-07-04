# Scheduled Camera Trigger

This tool is built to schedule a camera to wake up and start/stop recording video at a specific time. It was originally built to automatically start a camera for a rocket launch and is highly optimized for that use.

## Usage

First, create a `config.json` file with the following contents.

```json
{
  "buttonGpioPort": 4,
  "wakeUpTimeout": "5s",
  "startBefore": "20s",
  "endAfter": "30s",
  "cameraType": "generic",
  "generic": {
    "releaseGpioPort": 25,
    "focusGpioPort": 23
  },
  "logFile": "log.txt",
  "attempts": [
    {
      "name": "First attempt",
      "time": "2022-02-19T17:39:00.000Z"
    },
    {
      "name": "Second attempt",
      "time": "2022-02-20T17:39:00.000Z"
    }
  ]
}
```

| Option                    | Description                                                                                                                       |
| ------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `cameraType`              | Type of camera being triggered. Can be either `generic` or `zcam`                                                                 |
| `logFile`                 | File path to store logs at                                                                                                        |
| `buttonGpioPort`          | GPIO port on the Raspberry Pi that is connected to a button which when pressed will re-enable WiFi on the Pi (optional)           |
| `generic.releaseGpioPort` | GPIO port on the Raspberry Pi that is connected to the camera's shutter release pin (required when the `cameraType` is `generic`) |
| `generic.focusGpioPort`   | GPIO port on the Raspberry Pi that is connected to the camera focus pin (optional)                                                |
| `wakeUpTimeout`           | The amount of time before the recording is started to wake up the camera. Parsed by [ms](https://www.npmjs.com/package/ms).       |
| `startBefore`             | The amount of time before ignition time to start the recording. Parsed by [ms](https://www.npmjs.com/package/ms).                 |
| `endAfter`                | The amount of time after ignition time to end the recording. Parsed by [ms](https://www.npmjs.com/package/ms).                    |
| `attempts`                | Array of launch attempts                                                                                                          |
| `attempts.name`           | Friendly name for the launch attempt                                                                                              |
| `attempts.time`           | Exact T-0 of the launch attempt in UTC                                                                                            |
