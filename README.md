# Scheduled Camera Trigger

This tool is built to schedule a camera to wake up and start/stop recording video at a specific time. It was originally built to automatically start a camera for a rocket launch.

## Usage

First, create a `config.json` file with the following contents.

```json
{
  "wakeUpTimeout": "5s",
  "startBefore": "20s",
  "endAfter": "30s",
  "attempts": [
    {
      "name": "First attempt",
      "time": "2022-01-24T22:13:00.000Z"
    },
    {
      "name": "Second attempt",
      "time": "2022-01-24T22:15:00.000Z"
    }
  ]
}
```

| Option          | Description                                                                                                                 |
| --------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `wakeUpTimeout` | The amount of time before the recording is started to wake up the camera. Parsed by [ms](https://www.npmjs.com/package/ms). |
| `startBefore`   | The amount of time before ignition time to start the recording. Parsed by [ms](https://www.npmjs.com/package/ms).           |
| `endAfter`      | The amount of time after ignition time to end the recording. Parsed by [ms](https://www.npmjs.com/package/ms).              |
| `attempts`      | Array of launch attempts                                                                                                    |
| `attempts.name` | Friendly name for the launch attempt                                                                                        |
| `attempts.time` | Exact T-0 of the launch attempt in UTC                                                                                      |
