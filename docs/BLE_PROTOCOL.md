# Scheduled Camera Trigger — BLE Protocol

**Protocol version:** 1 · **Status:** Draft

A Bluetooth Low Energy (GATT) protocol for configuring, monitoring and manually controlling a Scheduled Camera Trigger device (Raspberry Pi Zero W + `generic` GPIO or `zcam` camera) from a phone, with no Wi‑Fi or network needed.

## 1. Goals

- Replace the HTTP API (`GET /`, `GET /logs`, `POST /test`, `POST /save`, `POST /kill-wifi`) with a BLE equivalent.
- Work in the field with no network. The Pi Zero W has no RTC, so **the phone sets the device clock** (a wrong clock means a missed launch).
- Run on BlueZ on a Pi Zero W (Bluetooth 4.1): assume the default 23-byte ATT MTU, take a larger one if negotiated.
- Be hard to misuse: nobody nearby should be able to stop a recording mid-launch.

## 2. GATT layout

One primary service, four characteristics. Multi-byte integers in fixed binary structures are **little-endian**.

| Name | UUID | Properties | Purpose |
| --- | --- | --- | --- |
| Service | `7a3c0001-5e9b-4d2a-8f61-c0a7b2d4e9f3` | — | Camera Trigger service |
| Device Info | `7a3c0002-5e9b-4d2a-8f61-c0a7b2d4e9f3` | Read | Static identity (JSON) |
| Status | `7a3c0003-5e9b-4d2a-8f61-c0a7b2d4e9f3` | Read, Notify | Compact live state (12 bytes) |
| Command | `7a3c0004-5e9b-4d2a-8f61-c0a7b2d4e9f3` | Write, Write Without Response | Client → device messages |
| Response | `7a3c0005-5e9b-4d2a-8f61-c0a7b2d4e9f3` | Notify | Device → client responses and events |

### Advertising

- Advertise the Service UUID in the advertisement packet.
- Put the local name in the scan response: `deviceName` (`{name}-{deviceId}`, e.g. `Device Name-A1B2C3`), same as the Bonjour name today.
- Interval ~1 s. One central at a time; stop advertising while connected, resume on disconnect.

### Device Info (read)

UTF‑8 JSON, ≤ 256 bytes. Clients must use Read Long.

```json
{ "v": 1, "id": "A1B2C3", "name": "Device Name", "cam": "generic", "fw": "1.0.0" }
```

| Field | Meaning |
| --- | --- |
| `v` | Protocol major version. Client must refuse a version it doesn't know. |
| `id` | `deviceId` (6 hex chars, uppercase) |
| `name` | Friendly name from config |
| `cam` | `cameraType`: `generic` or `zcam` |
| `fw` | Software version |

### Status (read / notify)

Fixed 12 bytes, cheap enough to poll or subscribe to at any MTU. Notified on every change, and at most once per second while a recording is active.

| Offset | Type | Field | Values |
| --- | --- | --- | --- |
| 0 | u8 | `flags` | bit0 recording · bit1 camera ready · bit2 Wi‑Fi enabled · bit3 pairing window open · bit4 clock set since boot |
| 1 | u8 | `phase` | 0 idle · 1 awake (woken, waiting to record) · 2 recording · 3 test running · 4 error |
| 2 | u32 | `configRev` | Increments on every successful config save |
| 6 | u32 | `nextEventAt` | Unix seconds of the next wake/start/stop; 0 if none |
| 10 | u8 | `nextEventType` | 0 none · 1 wake · 2 start · 3 stop |
| 11 | u8 | `attemptsRemaining` | Attempts not yet completed |

Clients cache the config and re-fetch only when `configRev` changes.

## 3. Message framing

Messages are UTF‑8 JSON (§4), up to **4096 bytes**, split into fragments that each fit one ATT write or notification (`MTU − 3` bytes). The same framing is used on Command and Response.

```
First fragment:        [ 1 b: 0x80 | idx ] [ u16 BE: total length ] [ payload… ]
Continuation fragment: [ 1 b: idx ]                                  [ payload… ]
```

- `idx` is the 7‑bit fragment counter: 0 for the first fragment, +1 each fragment, wrapping at 128.
- The message is complete when `total length` payload bytes have arrived.
- A fragment with the wrong `idx`, or a stalled message (> 5 s between fragments), discards the partial message. The device then sends `bad_request`; the client just retries.
- Fragments of one message are never interleaved with another message. The device queues events until a response finishes.
- Use Write with response for Command, so BlueZ applies back-pressure. Response is Notify only; the device paces fragments (≥ 1 connection interval apart).

Example: a 60‑byte message at MTU 23 (20 payload bytes per fragment) is sent as `80 00 3C <17 bytes>`, `01 <19 bytes>`, `02 <19 bytes>`, `03 <5 bytes>`.

## 4. Messages

### Envelope

```jsonc
// Request (client → device)
{ "id": 17, "op": "config.get", "d": { } }

// Success response (device → client)
{ "id": 17, "ok": true, "d": { } }

// Error response
{ "id": 17, "ok": false, "err": { "code": "invalid_config", "msg": "attempts[1].time is not a valid date", "field": "attempts[1].time" } }

// Event (device → client, unsolicited, no id)
{ "ev": "job", "d": { } }
```

- `id`: client-chosen unsigned integer, echoed back. Only **one request may be outstanding** at a time. A second one gets `busy`.
- Times are ISO 8601 UTC with milliseconds, e.g. `2026-11-02T17:39:00.000Z`. Durations are **seconds**.
- Unknown fields are ignored. Unknown `op` returns `not_supported`.
- Request timeout: 10 s. Long jobs (`test.run`) respond immediately and report progress as events.

### Operations

| Op | Auth | Replaces | Description |
| --- | --- | --- | --- |
| `hello` | no | — | Start a session, get nonce, time and auth state |
| `auth` | no | — | Prove knowledge of the shared secret |
| `auth.pair` | pairing window | — | Set the shared secret (§5) |
| `time.get` | no | `timestamp` in `GET /` | Device clock |
| `time.set` | yes | — | Set device clock |
| `status.get` | yes | `GET /` | Phase, recording, jobs, `configRev` |
| `config.get` | yes | `GET /` (`config`) | Read config |
| `config.set` | yes | `POST /save` | Validate, save and reschedule |
| `jobs.get` | yes | `GET /` (`jobs`) | Computed schedule, paged |
| `camera.wake` / `start` / `stop` / `sleep` | yes | — | Manual camera control |
| `test.run` | yes | `POST /test` | Wake → record 2 s → stop → sleep |
| `logs.get` | yes | `GET /logs` | Page through the log buffer |
| `logs.subscribe` | yes | polling `/logs` | Stream new log lines as events |
| `wifi.set` | yes | `POST /kill-wifi` | Enable or disable Wi‑Fi |

#### `hello`

```jsonc
// request
{ "id": 1, "op": "hello", "d": { "client": "camscheduler-app/1.2.0" } }
// response
{ "id": 1, "ok": true, "d": {
    "v": 1,
    "nonce": "q83vEjRWeJq83vEjRWeJqg==",   // 16 random bytes, base64, new every connection
    "authRequired": true,
    "paired": true,                          // a shared secret exists
    "now": "2026-10-25T14:03:11.482Z",
    "maxMessage": 4096
} }
```

#### `time.get` / `time.set`

```jsonc
{ "id": 2, "op": "time.set", "d": { "now": "2026-10-25T14:03:11.900Z" } }
→ { "id": 2, "ok": true, "d": { "now": "2026-10-25T14:03:11.901Z", "offsetMs": -418 } }
```

- `offsetMs` is the device's clock minus the value just set, i.e. the drift that was corrected.
- Applying the time needs root (`timedatectl set-time`, with NTP off) and **must re-initialize the scheduler**, because `node-schedule` jobs are tied to the old clock. Refused with `busy` if `phase ≠ 0`.
- The app should call `time.set` on every connect when the offset exceeds ~250 ms. Expected accuracy is tens of ms, which is fine for a ±20 s recording window.

#### `status.get`

```jsonc
{ "ok": true, "d": {
    "phase": "idle",            // idle | awake | recording | test | error
    "recording": false,
    "cameraReady": true,
    "wifi": true,
    "configRev": 12,
    "next": { "type": "wake", "at": "2026-11-02T17:38:35.000Z", "attempt": "Attempt 2" }
} }
```

#### `config.get` / `config.set`

`config.get` returns `{ "config": { … } }` in the shape below, minus `bleSecret`.

`config.set` is a full replacement. It is atomic: validate → write file → teardown → initialize. On any error the old config and schedule are untouched.

```jsonc
{ "id": 4, "op": "config.set", "d": { "config": {
    "name": "Pad Cam",
    "cameraType": "generic",
    "wakeUpTimeout": 5,
    "startBefore": 20,
    "endAfter": 30,
    "logFile": "log.txt",
    "buttonGpioPort": 17,
    "generic": { "releaseGpioPin": 25, "focusGpioPin": 23 },
    "zcam": { "cameraIp": "192.168.1.50" },
    "attempts": [
      { "name": "Attempt 1", "time": "2026-11-02T17:39:00.000Z" },
      { "name": "Attempt 2", "time": "2026-11-03T17:39:00.000Z" }
    ]
} } }
→ { "id": 4, "ok": true, "d": { "configRev": 13 } }
```

Rules:

- `cameraType` and `logFile` are required. `generic` requires `generic.releaseGpioPin`. `zcam` requires `zcam.cameraIp`.
- `attempts[].time` must be valid, unique and in the future, and so must its camera wake-up time (`time − startBefore − wakeUpTimeout`). `name` ≤ 32 chars. At most **30 attempts**, which guarantees a full config always fits in one 4096-byte message.
- `deviceId`, `deviceName` and `bleSecret` are device-owned. They are ignored on write, and `bleSecret` is never returned.
- **`busy` while `phase ≠ idle`**, because `Scheduler.teardown()` cancels the pending *stop* job and would leave the camera recording.
- Errors: `invalid_config` (with `field`), `busy`, `too_large`, `internal`.

#### `jobs.get`

Paged, because a full schedule is larger than one message. Request `{ "offset": 0, "limit": 10 }` (both optional; `limit` ≤ 50). The device also caps each page to fit `maxMessage`, so always continue from `offset + jobs.length` while `more` is true.

```jsonc
{ "ok": true, "d": { "jobs": [
  { "id": "5b6c…", "name": "Attempt 1", "camera": "generic",
    "wakeUpTime": "2026-11-02T17:38:35.000Z",
    "recordTime": "2026-11-02T17:38:40.000Z",
    "launchTime": "2026-11-02T17:39:00.000Z",
    "stopTime":   "2026-11-02T17:39:30.000Z",
    "state": "pending" }       // pending | active | complete | missed
], "total": 2, "more": false } }
```

#### `camera.*` and `test.run`

Params: none. Manual commands call the matching camera method and respond when it returns.

- `camera.start` / `camera.stop` are idempotent (the `generic` driver already guards on `recording`).
- Rejected with `busy` while a scheduled attempt is `active` or a test is running.
- `test.run` responds `{ "started": true }` immediately, then emits `test` events:

```jsonc
{ "ev": "test", "d": { "step": "wake" } }      // wake → start → stop → sleep → done
{ "ev": "test", "d": { "step": "done", "ok": true } }
{ "ev": "test", "d": { "step": "done", "ok": false, "error": "ZCam failed to connect" } }
```

#### `logs.get` / `logs.subscribe`

Each log line gets a monotonically increasing `seq` (the in-memory logger doesn't have one today).

```jsonc
{ "id": 9, "op": "logs.get", "d": { "after": 120, "limit": 20 } }
→ { "ok": true, "d": {
    "logs": [ { "seq": 121, "ts": "2026-10-25T14:01:02.000Z", "ns": "INDEX", "msg": "Start 'Attempt 1'" } ],
    "last": 140, "more": true } }
```

`after` defaults to 0 (oldest). Pages are capped so the response fits `maxMessage`. `logs.subscribe` `{ "enabled": true, "after": 140 }` replays missed lines, then pushes `{ "ev": "log", "d": { … } }` for each new line. The log `data` blob is omitted from BLE; read the log file over SSH if needed.

#### `wifi.set`

`{ "enabled": false }` disables Wi‑Fi (the old "kill Wi‑Fi" button); `true` re-enables it. This is reliable over BLE, unlike the HTTP version where the response never arrives. The GPIO button (`buttonGpioPort`) also re-enables Wi‑Fi.

### Events

Pushed to an authenticated, subscribed client on the Response characteristic. Clients should be able to rebuild everything from `status.get` after a reconnect, so events are best-effort.

| `ev` | `d` | When |
| --- | --- | --- |
| `job` | `{ "name", "step": "wake"\|"start"\|"stop"\|"complete"\|"missed", "at" }` | Each scheduled step runs |
| `config` | `{ "configRev" }` | Config changed (e.g. by another client) |
| `test` | see above | Test progress |
| `log` | log line | After `logs.subscribe` |

### Error codes

| Code | Meaning |
| --- | --- |
| `bad_request` | Malformed frame/JSON or missing field |
| `unauthorized` | Not authenticated, or bad proof |
| `locked_out` | Too many failed `auth` attempts; `retryAfter` seconds in `err` |
| `not_supported` | Unknown `op`, or op unsupported for this camera type |
| `busy` | Request already in flight, or device mid-attempt/test |
| `invalid_config` | Config failed validation; `field` names the culprit |
| `too_large` | Message over `maxMessage` |
| `pairing_closed` | `auth.pair` outside the pairing window |
| `internal` | Unexpected failure; `msg` has detail |

## 5. Security

A rogue central could otherwise stop a recording seconds before ignition, so everything except `hello`, `auth`, `auth.pair`, `time.get`, Device Info and Status requires authentication.

**Shared secret.** The device stores a 16‑byte random `bleSecret` (hex in config, never returned). The phone stores the same secret.

**Pairing (physical access required).** Pressing the GPIO button (`buttonGpioPort`) opens a **120 s pairing window** (Status flag bit3). During it, the client sends:

```jsonc
{ "id": 3, "op": "auth.pair", "d": { "secret": "<16 random bytes, base64>" } }
```

The secret is stored (replacing any existing one, so a lost phone is recoverable with physical access) and the session becomes authenticated. The window closes after one success or on timeout. The characteristics SHOULD require an encrypted link (LE Secure Connections; Just Works bonding is acceptable because the secret is only ever accepted inside the physical window) so the secret isn't sent in clear.

**Authenticating.** On later connections:

```jsonc
{ "id": 2, "op": "auth", "d": { "clientNonce": "<16 random bytes, base64>",
                                "proof": "<base64 HMAC-SHA256(secret, 'scs-auth-v1' || nonce || clientNonce)>" } }
→ { "ok": true, "d": { "proof": "<HMAC-SHA256(secret, 'scs-auth-v1-srv' || nonce || clientNonce)>" } }
```

- `nonce` comes from `hello`. The device compares in constant time.
- The device's returned `proof` lets the client verify it's talking to the real device.
- Auth lasts for the connection only.
- 5 consecutive failures → `locked_out` for 30 s (doubling, max 5 min).

If the device has no secret yet (`paired: false`), it stays open to `auth.pair` only inside a pairing window; everything else is `unauthorized`.

## 6. Typical session

```
Client                                         Device
  │── connect, discover service ───────────────▶│
  │── (optional) negotiate MTU ────────────────▶│
  │── read Device Info (check v, cam) ─────────▶│
  │── subscribe Response + Status notify ──────▶│
  │── hello ───────────────────────────────────▶│
  │◀──────────────────── nonce, now, paired ────│
  │── auth ────────────────────────────────────▶│
  │── time.set (if drift > 250 ms) ────────────▶│
  │── status.get / config.get (if rev changed) ▶│
  │── logs.subscribe {after: lastSeq} ─────────▶│
  │◀──────── Status + job/log events (push) ────│
```

## 7. Implementation notes

- **Port mapping.** `GET /` splits into `status.get` / `config.get` / `jobs.get`; the connection state the app shows (`cameraConnected`, `timeDiff`) comes from Response liveness and `time.get`.
- **Config schema.** The repo used to be inconsistent (README: `{name,time}` attempts and `generic.releaseGpioPort`; `config.example.json`: plain date strings and `genericReleaseGpioPin`). The loader now migrates old shapes to the canonical one on read (`trigger/lib/config.js`) and rewrites the file on the next save. Durations are seconds.
- **Reference implementation.** `trigger/ble/` (Node, [`ble-host`](https://github.com/Emill/node-ble-host) over a raw HCI socket, so `bluetoothd` must be stopped and `node` needs `cap_net_admin`: `sudo setcap cap_net_admin=ep $(readlink -f $(which node))`). Protocol logic (`framing`, `auth`, `dispatcher`, `link`) is transport-free and covered by `npm test`.
- **Encryption.** The Command characteristic requires an encrypted link by default (iOS pairs automatically on first write). Set `BLE_REQUIRE_ENCRYPTION=0` to disable it for debugging with a generic BLE tool.
- **Job state after a restart.** State lives in memory. After a reboot, any attempt whose wake-up time has already passed is `missed`, even if it ran before the reboot.
- **Log buffer.** The last 500 lines are kept in memory, each with a `seq`.
- **Compatibility.** Additive changes (new ops, new fields) don't bump `v`. Anything that changes framing or existing semantics does.

## 8. Decisions

- **Pairing:** button-gated shared secret (§5). No static PIN.
- **Manual control:** `camera.start` / `camera.stop` (and wake/sleep) are allowed in the field, subject to the `busy` guard during a scheduled attempt or test.
- **Time authority:** the phone. Clients should `time.set` on connect whenever drift exceeds ~250 ms; no NTP/GPS source is assumed.
