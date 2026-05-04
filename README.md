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

## BLE Setup (Raspberry Pi)

Control is exposed exclusively over Bluetooth Low Energy via `@abandonware/bleno`.
On a Pi running Raspbian / Raspberry Pi OS:

1. Make sure BlueZ is installed (it is by default).
2. Stop the system Bluetooth daemon so bleno can take over the HCI socket:
   ```sh
   sudo systemctl disable --now bluetooth
   sudo hciconfig hci0 up
   ```
3. Allow Node to access raw HCI without root:
   ```sh
   sudo setcap cap_net_raw+eip $(eval readlink -f $(which node))
   ```
4. Run the trigger normally (`node trigger/index`).

The device advertises a primary GATT service whose `localName` is the
`deviceName` from `config.json`.

## BLE API

### Service & characteristics

| Role        | UUID                                       | Properties                |
| ----------- | ------------------------------------------ | ------------------------- |
| Service     | `b87f0001-9c4b-4e6f-9a8e-1a1b2c3d4e5f`     | primary                   |
| RX (client → device) | `b87f0002-9c4b-4e6f-9a8e-1a1b2c3d4e5f` | write, writeWithoutResponse |
| TX (device → client) | `b87f0003-9c4b-4e6f-9a8e-1a1b2c3d4e5f` | notify                    |

### Framing

Every BLE write/notification carries a single chunk of one logical message.
Header is 4 bytes:

```
byte 0..1   u16 BE  msgId   sender-assigned reassembly key
byte 2      u8      seq     chunk index (0..255)
byte 3      u8      flags   bit 0 = LAST chunk
byte 4..N           payload chunk bytes (UTF-8 JSON fragment)
```

Receivers buffer chunks by `msgId` until they see a frame with `LAST` set
and all preceding seq numbers have arrived, then parse the concatenated
payload as JSON.

The default safe chunk payload is **19 bytes** (fits unnegotiated MTU 27).
After MTU negotiation, peers may use larger chunks: the device picks
`maxNotifyValueSize − 4`, the reference client picks `requestedMtu − 3 − 4`.

### Application messages

Once reassembled, every payload is one JSON object of one of three types:

```jsonc
// request (client → device)
{ "type": "req", "id": 1, "method": "getStatus", "params": {} }

// response (device → client; correlated by `id`)
{ "type": "res", "id": 1, "ok": true,  "data": { /* ... */ } }
{ "type": "res", "id": 1, "ok": false, "error": "Unknown method: foo" }

// event (device → client; unsolicited)
{ "type": "evt", "name": "log",    "data": { /* log entry */ } }
{ "type": "evt", "name": "status", "data": { /* status */ } }
```

### Methods

| Method      | Params                                  | Result                                                                 |
| ----------- | --------------------------------------- | ---------------------------------------------------------------------- |
| `getStatus` | —                                       | `{ timestamp, deviceId, deviceName, cameraType, recording, jobs }`     |
| `getConfig` | —                                       | full config object                                                     |
| `setConfig` | full config object                      | `{ ok: true }` (persists `config.json` and reschedules)                |
| `getLogs`   | `{ since?: ISO8601, limit?: number }`   | array of log entries                                                   |
| `test`      | —                                       | `{ ok: true }` (fires the camera once)                                 |

### Events

| Name     | Emitted when                              | Payload                              |
| -------- | ----------------------------------------- | ------------------------------------ |
| `status` | A client connects (snapshot)              | same shape as `getStatus`            |
| `log`    | Any new log line is written by the device | one log entry                        |

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
