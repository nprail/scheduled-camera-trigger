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
| `generic.releaseGpioPort` | GPIO port on the Raspberry Pi that is connected to the camera's shutter release pin (required when the `cameraType` is `generic`) |
| `generic.focusGpioPort`   | GPIO port on the Raspberry Pi that is connected to the camera focus pin (optional)                                                |
| `wakeUpTimeout`           | The amount of time in seconds before the recording is started to wake up the camera.                                              |
| `startBefore`             | The amount of time in seconds before ignition time to start the recording.                                                        |
| `endAfter`                | The amount of time in seconds after ignition time to end the recording.                                                           |
| `attempts`                | Array of launch attempts times                                                                                                    |
| `attempts[number]`        | Exact T-0 of the launch attempt in UTC                                                                                            |
