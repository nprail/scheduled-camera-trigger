# kairos — BLE Protocol

**Protocol version:** 2 · **Status:** Draft

A Bluetooth Low Energy (GATT) protocol for configuring, monitoring and manually controlling a Kairos device from a phone, with no Wi‑Fi or network needed. Version 2 is a compact binary protocol. It replaces the JSON protocol of version 1, and nothing is compatible with it.

## 1. Goals

- Work in the field with no network. The device has no reliable clock source of its own, so **the phone sets the device clock** (a wrong clock means a missed launch).
- Run on small hardware (an ESP32, or the legacy Raspberry Pi Zero W): assume the default 23-byte ATT MTU, and take a larger one if negotiated.
- Cheap to encode, decode and store. Every message is a fixed binary layout, and the stored form of the config is the wire form.
- Be hard to misuse: nobody nearby should be able to stop a recording mid-launch.

## 2. GATT layout

One primary service, four characteristics, plus an optional fifth (Firmware) on devices that can be updated over Bluetooth. **All multi-byte integers are little-endian**, except the 2-byte message length in the fragment header (§3).

| Name | UUID | Properties | Purpose |
| --- | --- | --- | --- |
| Service | `6b610001-6169-4f72-8f73-b3e1c5a97d24` | — | Kairos service |
| Device Info | `6b610002-6169-4f72-8f73-b3e1c5a97d24` | Read | Static identity |
| Status | `6b610003-6169-4f72-8f73-b3e1c5a97d24` | Read, Notify | Compact live state (13 bytes) |
| Command | `6b610004-6169-4f72-8f73-b3e1c5a97d24` | Write, Write Without Response | Client → device messages |
| Response | `6b610005-6169-4f72-8f73-b3e1c5a97d24` | Notify | Device → client responses and events |
| Firmware | `6b610006-6169-4f72-8f73-b3e1c5a97d24` | Write Without Response | Firmware image bytes (§4.3). Present only when Device Info `caps` bit0 is set. |

### Advertising

- Advertise the Service UUID in the advertisement packet.
- Put the local name in the scan response: `{name}-{deviceId}` (for example `Pad Cam-A1B2C3`), or just `{deviceId}` when the name is empty.
- The scan response allows 29 bytes of name. If `{name}-{deviceId}` is longer, **truncate the name part, never the id**: keep at most 22 bytes of name (cut on a UTF‑8 character boundary), then `-`, then the 6-character id. Phones recognise a device by the id after the last `-`.
- Interval about 1 s. One central at a time: stop advertising while connected, resume on disconnect.

### Device Info (read)

| Offset | Type | Field |
| --- | --- | --- |
| 0 | u8 | `v`: protocol version, **2**. A client must refuse any version it does not know. |
| 1 | 3 bytes | `id`: the 6 hex digits of the device id as 3 raw bytes (`A1B2C3` is `A1 B2 C3`) |
| 4 | u8 | `cam`: the configured camera type, 0 `generic` or 1 `zcam` |
| 5 | u8 | `cams`: the camera types this device can drive, a bit set (bit 0 `generic`, bit 1 `zcam`). A device without the Ethernet hardware reports only `generic`, so a client offers Z CAM only when bit 1 is set. |
| 6 | u8 + bytes | `fw`: software version, a length-prefixed ASCII string |
| … | u8 + bytes | `name`: friendly name, a length-prefixed UTF‑8 string (may be empty) |

| … | u8 | `caps` (optional): bit0 = the device can be updated over Bluetooth (§4.3). Missing means 0. |
| … | u8 | `chip` (optional, after `caps`): 1 ESP32-S3, 2 ESP32-C3, 0 or missing for other hardware |
| … | u32 | `slot` (optional, after `chip`): the largest firmware image the device accepts, in bytes. 0 or missing when not updatable. |

Strings are a `u8` length followed by that many bytes. Clients must use Read Long. The last three fields were added after the first release of v2 and follow `name`. A client reads them only if bytes remain, and treats a device that lacks them as not updatable.

### Status (read / notify)

Fixed 13 bytes, cheap enough to poll or subscribe to at any MTU. Notified on every change, and at most once per second while a recording is active.

| Offset | Type | Field | Values |
| --- | --- | --- | --- |
| 0 | u8 | `flags` | bit0 recording · bit1 camera ready · bit2 pairing window open · bit3 clock valid |
| 1 | u8 | `phase` | 0 idle · 1 awake (woken, waiting to record) · 2 recording · 3 test running · 4 error |
| 2 | u32 | `configRev` | Increments on every successful config save |
| 6 | u32 | `nextEventAt` | Unix seconds of the next wake/start/stop; 0 if none |
| 10 | u8 | `nextEventType` | 0 none · 1 wake · 2 start · 3 stop |
| 11 | u8 | `attemptsRemaining` | Attempts not yet completed |
| 12 | u8 | `nextAttempt` | Index (in the config's attempt list) of the attempt that owns the next event, `0xFF` for none |

Bit 3 (`clock valid`) is set once the device clock has been set by a phone or restored from a trustworthy clock source. Clients cache the config and re-fetch it only when `configRev` changes.

## 3. Message framing

A message is up to **2048 bytes**, split into fragments that each fit one ATT write or notification (`MTU − 3` bytes). The same framing is used on Command and Response.

```
First fragment:        [ 1 b: 0x80 | idx ] [ u16 BE: total length ] [ payload… ]
Continuation fragment: [ 1 b: idx ]                                  [ payload… ]
```

- `idx` is the 7‑bit fragment counter: 0 for the first fragment, +1 each fragment, wrapping at 128.
- The message is complete when `total length` payload bytes have arrived.
- A fragment with the wrong `idx`, or a stalled message (> 5 s between fragments), discards the partial message. The device then sends a `bad_request` error and the client just retries.
- Fragments of one message are never interleaved with another message. The device queues events until a response finishes.
- Use Write with response for Command so the stack applies back-pressure. Response is Notify only; the device paces fragments (at least one connection interval apart).

Example: a 60‑byte message at MTU 23 (20 payload bytes per fragment) is sent as `80 00 3C <17 bytes>`, `01 <19 bytes>`, `02 <19 bytes>`, `03 <5 bytes>`.

## 4. Messages

### Envelope

```
Request : [ op u8 ][ id u8 ][ body … ]
Response: [ 0x80  ][ id u8 ][ status u8 ][ body … ]      status 0 = ok, otherwise an error code and its detail
Event   : [ 0x81  ][ ev u8 ][ body … ]
```

- `id` is chosen by the client and echoed back. Only **one request may be outstanding** at a time. A second one gets `busy`.
- A parser checks the **minimum** length of a body and ignores trailing bytes. New fields are appended, and new ops use new codes.
- Request timeout: 10 s. Long jobs (`test.run`) respond immediately and report progress as events.
- An unknown `op` returns `not_supported`. An error that cannot be tied to a request (a bad frame, or a request too short to carry an `id`) uses `id` 0.

### Types

| Type | Encoding |
| --- | --- |
| integers | little-endian, `u8`/`u16`/`u32`/`u64`/`i32` |
| time | `u64` Unix **milliseconds** for the clock ops, `u32` Unix **seconds** everywhere else |
| string | `u8` length + bytes (UTF‑8 for names and messages, ASCII for the rest). The limits below are in **bytes** |
| bool | `u8`, 0 or 1 |
| pin | `u8` GPIO number, `0xFF` for none |
| IPv4 address | 4 bytes, `192.168.1.50` is `C0 A8 01 32`. All zeros means none |

### Operations

| Op | Code | Auth | Request body | Response body |
| --- | --- | --- | --- | --- |
| `hello` | 0x01 | no | — | `v u8`, `flags u8` (bit0 paired), `nonce[16]`, `now u64`, `maxMessage u16` |
| `auth` | 0x02 | no | `clientNonce[16]`, `proof[32]` | `proof[32]` |
| `auth.pair` | 0x03 | pairing window | `secret[16]` | — |
| `time.get` | 0x04 | no | — | `now u64` |
| `time.set` | 0x05 | yes | `now u64` | `now u64`, `offsetMs i32` |
| `config.get` | 0x07 | yes | — | Config (§4.2) |
| `config.set` | 0x08 | yes | Config | `configRev u32` |
| `jobs.get` | 0x09 | yes | — | `count u8`, then `count × state u8` |
| `camera.wake` | 0x10 | yes | — | `recording u8` |
| `camera.start` | 0x11 | yes | — | `recording u8` |
| `camera.stop` | 0x12 | yes | — | `recording u8` |
| `camera.sleep` | 0x13 | yes | — | `recording u8` |
| `test.run` | 0x14 | yes | — | — |
| `logs.get` | 0x20 | yes | `after u32`, `limit u8` | `last u32`, `more u8`, `count u8`, then `count` log lines |
| `logs.subscribe` | 0x21 | yes | `enabled u8`, `after u32` | — |
| `fw.begin` | 0x30 | yes | `size u32`, `sha256[32]`, `sig[64]` | `offset u32`, `window u16` |
| `fw.end` | 0x31 | yes | — | `rebootInMs u16` |
| `fw.abort` | 0x32 | yes | — | — |

Code 0x06 (the v1 `status.get`) is unused: the Status characteristic carries everything it did.

#### `hello`

Starts a session. `nonce` is 16 random bytes, new for every call. `flags` bit0 is set when a shared secret exists. `maxMessage` is 2048.

#### `time.get` / `time.set`

`time.set` sets the device clock to `now` and returns the device's clock afterwards plus `offsetMs`, the device's clock **minus** the value just set (the drift that was corrected), clamped to `i32`.

- Setting the time must **re-plan every scheduled timer**. It is refused with `busy` unless the device is idle (`phase` 0).
- The app calls `time.set` on every connect when the measured offset exceeds about 250 ms. Expected accuracy is tens of milliseconds, which is fine for a ±20 s recording window.

#### `config.set`

A full replacement, atomic: validate, store, then re-plan. On any error the old config and schedule are untouched. It is refused with `busy` while `phase` is not idle, because re-planning cancels the pending *stop* and would leave the camera recording. Errors: `invalid_config`, `busy`, `too_large`, `internal`.

**Config layout** (the `config.get` response, the `config.set` request, and the form stored on the device):

| Field | Type | Notes |
| --- | --- | --- |
| `layout` | u8 | Config layout version, currently 1 |
| `cameraType` | u8 | 0 `generic`, 1 `zcam` |
| `wakeUpTimeout` | u16 | seconds, 0–3600 |
| `startBefore` | u16 | seconds, 0–3600 |
| `endAfter` | u16 | seconds, 0–3600 |
| `cameraIp` | IPv4 | required for `zcam` |
| `name` | string | ≤ 32 bytes, may be empty |
| `count` | u8 | number of attempts, ≤ 30 |
| `attempts[count]` | `time u32` + `name` string | `time` is the T‑0 in Unix seconds; `name` is 1–32 bytes |

GPIO pins are not part of the protocol: they describe how a device is wired, so each device keeps them itself (`config.json` on the Pi, constants in the firmware on the ESP32) and a phone neither reads nor sets them. A config is at most about 1.2 KB. `deviceId` and `configRev` belong to the device and are not part of the layout: the id is in Device Info and the revision is in Status.

Validation rules: `cameraType` must be 0 or 1; `zcam` needs a non-zero `cameraIp`; every attempt `time` must be unique and in the future, and so must its camera wake-up time (`time − startBefore − wakeUpTimeout`).

#### `jobs.get`

Returns one state per attempt, **in the config's attempt order**: 0 pending, 1 active, 2 complete, 3 missed. The phone computes each attempt's wake, record and stop times itself from `time`, `startBefore`, `wakeUpTimeout` and `endAfter`.

#### `camera.*` and `test.run`

No body. Manual commands call the matching camera action and respond when it returns. `camera.start` and `camera.stop` are idempotent. They are rejected with `busy` while a scheduled attempt is active or a test is running. `test.run` responds immediately, then emits `test` events: wake → start → stop → sleep → done.

#### `logs.get` / `logs.subscribe`

Each log line has a monotonically increasing `seq`. A **log line** is `seq u32`, `ts u32` (Unix seconds), `ns` (string, ASCII, ≤ 8 bytes), `msg` (string, UTF‑8, ≤ 120 bytes). `logs.get` returns up to `limit` lines with `seq > after` (limit ≤ 100, capped so the response fits `maxMessage`); `last` is the highest `seq` returned and `more` is 1 if lines remain. `logs.subscribe` with `enabled = 1` replays lines after `after`, then pushes a `log` event for each new line. The log's free-form data blob is never sent over BLE.

#### `fw.*` (firmware update)

Updates are always the user's choice: the device never starts one itself. Only devices with Device Info `caps` bit0 accept these ops. Any other device answers `not_supported`.

**Flow.** `fw.begin`, then the image bytes on the Firmware characteristic, then `fw.end`. The device reboots into the new image and the connection drops. The phone reconnects and reads `fw` from Device Info to see which version is running.

- `fw.begin` declares the image: its `size` in bytes, the `sha256` of the whole image, and `sig`, a 64-byte ECDSA P-256 signature (`r` then `s`, 32 bytes each, big-endian) over that SHA-256 value. All zeros means no signature. It is refused with:
  - `busy` unless the phase is idle and the next scheduled event is at least 15 minutes away (a clock that is not valid counts as no event);
  - `too_large` if `size` exceeds Device Info `slot`;
  - `bad_image` (size) if `size` is below the smallest valid image;
  - `bad_image` (signature missing) for an all-zero `sig`, unless the device accepts unsigned images (developer builds only);
  - `bad_image` (signature invalid) if `sig` does not verify against any of the device's embedded public keys.
- The response gives the `offset` to start from (0, or the resume point), and `window`, the number of bytes the phone may have sent but not yet seen acknowledged. A `fw.begin` with the same `size` and `sha256` as a transfer already in progress resumes it. The device keeps an unfinished transfer in RAM for 2 minutes after a disconnect, and loses it on a reset. A different image replaces it.
- **Firmware characteristic.** Each write is `[offset u32][bytes]` and at most MTU − 3 bytes in total. The device accepts a write only when `offset` is the next byte it expects. A write at a lower offset is ignored, and one at a higher offset (a gap) is not stored and makes the device send an `fw` event with its `offset`, so the phone rewinds to it. The first 64 bytes are checked as soon as they arrive: image magic `0xE9`, the chip ID (a little-endian `u16` at offset 12) matching `chip`, and the application descriptor magic `0xABCD5432` (a little-endian `u32` at offset 32). The last check is what tells an application image from a factory image, which must never be sent.
- **Progress.** The `fw` event (below) reports the next expected offset every `window / 4` bytes and on any state change.
- `fw.end` is valid when every byte has arrived. The device compares the SHA-256 it computed with the declared one, validates the image, selects it for the next boot, answers with `rebootInMs`, and restarts after that delay. Errors are `bad_image` (incomplete, hash, write failed) and `busy` if no transfer is open.
- `fw.abort` discards an unfinished transfer. It is always accepted.
- The device aborts a transfer, keeps running the current firmware, and sends an `fw` event with `failed` and reason `interrupted` when no data arrives for 2 minutes or a scheduled event comes within 2 minutes.
- The new image must confirm itself after booting: the device marks it valid once it has run for 30 seconds **and a client has authenticated over Bluetooth with it**. If it resets before that, the bootloader returns to the previous image. A client therefore reconnects and authenticates after the restart (the apps do this to read the new version).

**Signing.** Release images are signed with the project's key. The device holds one or more public keys and verifies the signature in `fw.begin`, before accepting any data. Developer builds may accept unsigned images. Clients treat the signature as opaque bytes.

### Events

Pushed to an authenticated client on the Response characteristic: `[0x81][ev][body]`. Events are best-effort: after a reconnect a client rebuilds everything from Status, `config.get` and `jobs.get`.

| Event | Code | Body |
| --- | --- | --- |
| `job` | 0x01 | `attempt u8` (config index), `step u8` (0 wake, 1 start, 2 stop, 3 complete, 4 missed), `at u32` Unix seconds |
| `config` | 0x02 | `configRev u32` |
| `test` | 0x03 | `step u8` (0 wake, 1 start, 2 stop, 3 sleep, 4 done), `ok u8`, `error` string (empty unless a failed `done`) |
| `log` | 0x04 | a log line |
| `fw` | 0x05 | `state u8` (0 receiving, 1 verifying, 2 ready to reboot, 3 failed), `offset u32` (the next byte expected), `reason u8` (a `bad_image` reason when failed, else 0) |

### Errors

A response with `status ≠ 0` carries an error code and its detail in the body.

| Code | Name | Detail |
| --- | --- | --- |
| 1 | `bad_request` | — |
| 2 | `unauthorized` | — |
| 3 | `locked_out` | `retryAfter u16` seconds |
| 4 | `not_supported` | — |
| 5 | `busy` | — |
| 6 | `invalid_config` | `field u8`, `index u8` (attempt index, else 0), `reason u8` |
| 7 | `too_large` | — |
| 8 | `pairing_closed` | — |
| 9 | `internal` | an optional string with detail |
| 10 | `bad_image` | `reason u8` |

`invalid_config` **fields**: 1 `name`, 2 `cameraType`, 3 `wakeUpTimeout`, 4 `startBefore`, 5 `endAfter`, 9 `cameraIp` (6 to 8 are unused), 10 `attempts` (the list), 11 `attempts[i].name`, 12 `attempts[i].time`.
**Reasons**: 1 required, 2 out of range, 3 bad value, 4 in the past, 5 duplicate, 6 too soon (the wake-up time has already passed), 7 too many.

`bad_image` **reasons**: 1 size (below the smallest valid image), 2 header (not an application image), 3 chip (built for different hardware), 4 hash, 5 write (flash failed), 6 signature missing, 7 signature invalid, 8 incomplete (`fw.end` before all bytes arrived), 9 interrupted (no data for 2 minutes, or a scheduled event came close).

The apps build their error text from these codes, so the device carries no message strings.

## 5. Security

A rogue central could otherwise stop a recording seconds before ignition, so everything except `hello`, `auth`, `auth.pair`, `time.get`, Device Info and Status requires authentication.

**Shared secret.** The device stores a 16‑byte random secret, never returned to a client. The phone stores the same secret.

**Pairing (physical access required).** Pressing the device's button opens a **120 s pairing window** (Status flag bit 2). During it, the client sends `auth.pair` with a fresh random 16-byte `secret`. The secret replaces any existing one (so a lost phone is recoverable with physical access) and the session becomes authenticated. The window closes after one success or on timeout. The Command characteristic SHOULD require an encrypted link (LE Secure Connections, Just Works bonding is acceptable because a secret is only ever accepted inside the physical window), so the secret is not sent in clear.

**Authenticating.** On later connections the client sends `auth` with `clientNonce` (16 random bytes) and

```
proof = HMAC-SHA256(secret, "kairos-auth-v2" || nonce || clientNonce)
```

and the device answers with

```
proof = HMAC-SHA256(secret, "kairos-auth-v2-srv" || nonce || clientNonce)
```

- `nonce` comes from `hello`. The labels are ASCII and the concatenation has no separators. The device compares in constant time.
- The device's returned proof lets the client verify that it is talking to the real device. A client must treat a mismatch as `unauthorized`.
- Authentication lasts for the connection only.
- 5 consecutive failures → `locked_out` for 30 s, doubling each time up to 5 min.

If the device has no secret yet (`paired` is 0), everything but `auth.pair` inside a pairing window is `unauthorized`.

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
  │── config.get / jobs.get (if rev changed) ──▶│
  │── logs.subscribe {after: lastSeq} ─────────▶│
  │◀──────── Status + job/log events (push) ────│
```

## 7. Implementation notes

- **Test vectors.** `protocol/test-vectors.json` holds vectors for framing, the auth proofs, Status, Device Info, the Config layout, every message, and every error. Each implementation (Node, ESP32, iOS, Android) must pass them. They are generated by `cd firmware/legacy-pi && node test/vectors.js --write` from the Node reference, which is the only place they are edited.
- **Reference implementations.** `firmware/esp32/` (C++, on an ESP32) and `firmware/legacy-pi/trigger/ble/` (Node, on the legacy Raspberry Pi). Both keep the protocol logic free of any radio code so it can be tested without hardware.
- **Job state after a restart.** State lives in memory. After a restart, any attempt whose wake-up time has already passed is `missed`, even if it ran before.
- **Log buffer.** The last 500 lines are kept in memory.
- **Firmware images.** A release image carries a small tag the apps read to show its version before installing: the 8 bytes `KAIROSFW`, a `u8` tag version (1), a `u8` chip (1 ESP32-S3, 2 ESP32-C3) and a 32-byte NUL-padded ASCII version. A client scans the whole file for it. `protocol/test-vectors.json` has vectors for reading images and for comparing versions.
- **Compatibility.** Additive changes (new ops, new trailing fields) don't change `v`. Anything that changes framing or existing layouts does.
