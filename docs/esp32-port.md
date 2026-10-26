# ESP32 Port — Design Proposal

**Status:** Implemented in [`esp32/`](https://github.com/nprail/kairos/tree/main/esp32) (builds, with host tests; not yet run on hardware). See [Implementation notes](#implementation-notes) for where it differs from this proposal. · **Target:** ESP32-S3 + W5500 Ethernet + external RTC · **Framework:** Arduino-ESP32 on PlatformIO · **Storage:** NVS only · **Protocol:** new binary BLE protocol v2

This document describes how to port the device side of Kairos (`trigger/`, currently Node on a Raspberry Pi Zero W) to an ESP32. Because nobody uses the current system yet, the port also replaces the JSON BLE protocol (v1) with a compact binary one (v2, §5). **Both apps change with it.** Nothing needs to stay compatible with v1.

## 1. Why, and what changes

| | Pi Zero W (today) | ESP32-S3 (proposed) |
| --- | --- | --- |
| Runtime | Linux + Node, BlueZ-free raw HCI via `ble-host` | Arduino-ESP32 firmware (FreeRTOS underneath), NimBLE host on the on-chip controller |
| Protocol | JSON messages, ISO dates, base64, paged lists | **Binary v2.** Stored form equals wire form (§5) |
| Clock | No RTC. Phone sets it on every connect, lost at power-off | **External RTC with a coin cell.** The first launch after a reboot is still on time |
| Z CAM link | Ethernet (USB adapter / hat) | Ethernet via **W5500 over SPI** |
| Wi-Fi | `rfkill block wifi` at startup | **Never started.** The radio stays off (the core's prebuilt libraries still contain the code, §4) |
| Storage | `config.json`, log file on SD | **NVS only.** No filesystem. Logs live in RAM (§8) |
| Boot | ~30–60 s | < 2 s |
| Power | ~1 W idle | ~100–150 mW with BLE advertising (measure; battery is external in v1, so sleep modes are deferred) |
| Provisioning | SSH, edit `config.json` | Flash once over USB. Everything else is over BLE |

The boot-time and power wins matter in the field: the device can be cold-powered shortly before a launch and still be correct.

## 2. Scope

**In:** the ESP32 firmware, the v2 binary protocol, the matching iOS and Android changes, and **updating the Node device code to v2**. Node stays the working device until the ESP32 reaches parity, so the apps can be developed and tested against real hardware. Node is then retired.

**Out:**
- Wi-Fi, an HTTP server, and OTA in v1. Updates are over USB, but the flash layout reserves two app slots so BLE OTA can be added later without repartitioning (§8, §14).
- Persisting schedule state across resets. After a reboot any attempt whose wake time has passed is `missed`, exactly as on the Pi.
- A persistent log. The log is a RAM ring, as on the Pi's in-memory buffer.

## 3. Hardware

### 3.1 Bill of materials

| Part | Notes |
| --- | --- |
| ESP32-S3 dev board | e.g. ESP32-S3-DevKitC-1 (N8; PSRAM is not needed). BLE 5, USB-serial/JTAG for flashing |
| W5500 SPI Ethernet module | Hardware TCP/IP offload, 10/100 Mbit, supported by the Arduino core's `ETH` library. Connects to the Z CAM with a short Cat5e cable |
| RTC module | See §3.3 |
| Push button | Pairing. Active-high with pull-down to match today's rising-edge behavior, or active-low with the internal pull-up (decide in §18) |
| Output stage for `generic` cameras | 3.3 V GPIO is **not** enough to drive a camera release. Use an optocoupler or small MOSFET per line (release, focus) |
| Power | The battery is **external to the device in v1**. Specify the input (voltage range, connector, reverse-polarity protection) and use a regulator with headroom for ~250 mA peaks from W5500 + BLE. Brown-outs are the main reliability risk (§13) |

### 3.2 Suggested pin map (verify against your board)

All pins are constants in `esp32/src/platform/board.h`, not settings. Anyone wiring a different board edits that file and rebuilds. They are not part of the BLE protocol (§19).

| Function | GPIO | Why |
| --- | --- | --- |
| W5500 SCLK / MOSI / MISO / CS | 12 / 11 / 13 / 10 | Default SPI2 (FSPI) pins |
| W5500 INT / RST | 14 / 9 | INT is required for efficient receive. RST lets firmware recover a hung PHY |
| RTC SDA / SCL | 8 / 18 | Any free pins work. These avoid SPI2 |
| RTC SQW/INT (optional) | 15 | 1 Hz edge for sub-second alignment (§7.3) |
| Button (default) | 4 | |
| Release / Focus (default) | 5 / 6 | |

Avoid on ESP32-S3: GPIO 0, 3, 45, 46 (strapping), 19 and 20 (USB), 26–32 (flash), and 33–37 on octal-PSRAM modules.

### 3.3 RTC choice (not yet decided)

| Chip | Accuracy | Notes |
| --- | --- | --- |
| **DS3231** (recommended) | ±2 ppm, about ±1 min/year, temperature-compensated | I2C 0x68, 1 Hz SQW, oscillator-stop flag (OSF). Cheap modules often include a charging circuit meant for LIR2032. **Use a CR2032 only after removing the charge resistor/diode**, or buy a module without it |
| PCF8523 | ~±20 ppm, about ±10 min/year | Lowest power, simple. Fine if the phone re-syncs the clock often |
| RV-3028-C7 | ~±1 ppm (typ.), very low power | Best specs, harder to source on a breakout |

The firmware hides the chip behind a small `rtc` interface (`read()`, `write()`, `is_valid()`), so swapping is a driver file, not a redesign. Choose DS3231 unless power draw on a coin cell is the constraint. The phone-driven sync (§7) stays in all cases, because the RTC can still be seconds off after months.

## 4. Firmware framework: Arduino-ESP32 on PlatformIO (decided)

Chosen over ESP-IDF for faster iteration and a lower barrier for contributors who aren't firmware specialists. Arduino-ESP32 is built on ESP-IDF, so the IDF API (`esp_timer`, `esp_ota_ops`, mbedTLS, `esp_reset_reason`, FreeRTOS) is available from Arduino code.

| | **ESP-IDF** | **Arduino-ESP32 / PlatformIO (chosen)** |
| --- | --- | --- |
| BLE | NimBLE host directly | `NimBLE-Arduino` (h2zero, 2.x). Same host with a friendlier API. LE Secure Connections, bonding and MTU are exposed |
| Ethernet | `esp_eth` + `esp_netif` | `ETH.begin()` with `ETH_PHY_W5500` (core 3.x). DHCP by default, static via `ETH.config()`, link events via `Network.onEvent` |
| HTTP client (Z CAM) | `esp_http_client` | `HTTPClient` with `setConnectTimeout` / `setTimeout`. Blocking, so it runs on its own task |
| Scheduling | FreeRTOS | Same FreeRTOS. Use real tasks, not `loop()` polling |
| Storage | NVS | `Preferences` (NVS) |
| OTA | `esp_ota_ops` | `Update` library, or `esp_ota_ops` directly for rollback control |
| Testing | `linux` target | PlatformIO `native` env with Unity, with no hardware or Arduino headers |
| Sdkconfig | Fully editable | **Prebuilt** with the core. See the limitations below |

**Limitations to plan around**

- **Prebuilt sdkconfig.** The Arduino core ships precompiled IDF libraries. Options such as core-dump-to-flash, secure boot, flash encryption and bootloader rollback are fixed by the core build. Check each against the pinned core version before relying on it. If one is needed and missing, either use the core as an IDF component (`framework = arduino, espidf`) or rebuild with Arduino Lib Builder. For v1 we only need Wi-Fi to stay uninitialised, which is a runtime choice.
- **Core version churn.** Arduino-ESP32 3.x changed the Ethernet and network event APIs from 2.x. PlatformIO's official `espressif32` platform lags behind, so use the community `pioarduino` platform for core 3.x, and **pin the platform, core and library versions** in `platformio.ini`.
- **Two BLE stacks exist in the core.** Don't use the core's built-in `BLEDevice` (Bluedroid) at all. Use `NimBLE-Arduino` only.
- **`loop()` is a task.** Keep `loop()` empty or trivial and create explicit FreeRTOS tasks (`xTaskCreatePinnedToCore`) so priorities and watchdogs are under control.

If any of these bite, the pure modules (§6) are framework-free, so moving to ESP-IDF only rewrites the `platform/` layer.

## 5. Protocol v2: binary

Since the protocol is free to change, v2 is designed for the cheapest possible encode, decode and storage on both ends. This section is the proposal. It is moved into `docs/ble-protocol.md` (rewritten as v2) before any code is written (§16).

### 5.1 Design decisions

| Decision | Why |
| --- | --- |
| **Fixed binary layouts, little-endian**, no JSON | Status already works this way. No parser, no allocation, no base64, and no ISO date parsing on the device. Each side needs only a small encoder and decoder, covered by shared vectors |
| **Stored form = wire form** | The config blob in NVS is the exact byte layout the phone sends and receives (§8). `config.get` is a memcpy and `config.set` validates then stores |
| **Keep the v1 fragment framing unchanged** (§3 of the v1 doc) | It is orthogonal to the payload, already specified and vector-tested. Only `maxMessage` changes (4096 → 2048) |
| **No paging for config or jobs** | A full binary config is about 1.2 KB, so it fits one message. `logs.get` keeps paging |
| **No `status.get`.** The Status characteristic is the source | It already carries everything (§5.4). Fewer ops, no duplicate state |
| **`jobs.get` returns states only** | The phone computes wake, record and stop times from each attempt's launch time and the three timings. The device only owns the state of each attempt |
| **Errors are codes, not text** | The device sends a code plus structured detail. The apps already map codes to friendly text, and `invalid_config` is built from enums. No strings on the device, and the text can be localized |
| **Times:** launch times are u32 Unix seconds. Clock ops use u64 Unix milliseconds | Attempts are picked to the minute, so seconds are enough. The clock sync needs ms |
| **Strings:** `u8 length` + UTF-8, with limits in **bytes** | Cheap to parse and bounded. Names ≤ 32 bytes |
| **Pins:** `u8`, `0xFF` = none. **Camera IP:** 4 raw IPv4 bytes | The apps already use IPv4 (`192.168.1.50`) |
| **App-layer HMAC auth stays** (raw bytes instead of base64) | See 5.6 for why we do not rely on BLE bonding alone |
| **Hand-written layouts, no serialization library** | See 5.6 |

Rough size effect (estimates, not measured): a full config drops from about 2.4 KB of JSON to about 1.1 KB. `camera.start` goes from about 60 bytes to 3. A clock set goes from about 100 bytes to 11.

### 5.2 Conventions

- Integers are little-endian. `u64` times are Unix milliseconds. Booleans are `u8` 0/1.
- Messages are split into fragments exactly as in v1 (`0x80|idx` + `u16` BE total length, then continuation fragments). Only one request is in flight at a time.
- Device Info `v` becomes **2**. An app that sees any other version refuses the device, as today.
- Forward compatibility: a parser must check the **minimum** length and ignore trailing bytes. New fields are appended. New ops use new codes. A layout change that cannot be appended bumps `v`.

### 5.3 Envelope

```
Request : [op u8][id u8][body …]
Response: [0x80][id u8][status u8][body …]      status 0 = ok, else an error code and its detail
Event   : [0x81][ev u8][body …]
```

| Op | Code | Auth | Request body | Response body |
| --- | --- | --- | --- | --- |
| `hello` | 0x01 | no | — | `v u8`, `flags u8` (bit0 paired), `nonce[16]`, `now u64`, `maxMessage u16` |
| `auth` | 0x02 | no | `clientNonce[16]`, `proof[32]` | `proof[32]` |
| `auth.pair` | 0x03 | window | `secret[16]` | — |
| `time.get` | 0x04 | no | — | `now u64` |
| `time.set` | 0x05 | yes | `now u64` | `now u64`, `offsetMs i32` |
| `config.get` | 0x07 | yes | — | Config (5.5) |
| `config.set` | 0x08 | yes | Config | `configRev u32` |
| `jobs.get` | 0x09 | yes | — | `count u8`, then `count × state u8` (0 pending, 1 active, 2 complete, 3 missed), in config attempt order |
| `camera.wake` / `start` / `stop` / `sleep` | 0x10–0x13 | yes | — | `recording u8` |
| `test.run` | 0x14 | yes | — | — (progress arrives as events) |
| `logs.get` | 0x20 | yes | `after u32`, `limit u8` | `last u32`, `more u8`, `count u8`, then lines |
| `logs.subscribe` | 0x21 | yes | `enabled u8`, `after u32` | — |

A **log line** is `seq u32`, `ts u32` (Unix seconds), `ns` (`u8` length + ASCII, ≤ 8), `msg` (`u8` length + UTF-8, ≤ 120). Device-side `data` blobs are never sent.

**Events** (`[0x81][ev]…`):

| Event | Code | Body |
| --- | --- | --- |
| `job` | 0x01 | `attempt u8` (config index), `step u8` (0 wake, 1 start, 2 stop, 3 complete, 4 missed), `at u32` |
| `config` | 0x02 | `configRev u32` |
| `test` | 0x03 | `step u8` (0 wake, 1 start, 2 stop, 3 sleep, 4 done), `ok u8`, `error` (`u8` length + UTF-8, empty unless a failed `done`) |
| `log` | 0x04 | a log line |

**Errors** (`status ≠ 0`; the body is the detail):

| Code | Name | Detail body |
| --- | --- | --- |
| 1 | `bad_request` | — |
| 2 | `unauthorized` | — |
| 3 | `locked_out` | `retryAfter u16` (seconds) |
| 4 | `not_supported` | — |
| 5 | `busy` | — |
| 6 | `invalid_config` | `field u8`, `index u8` (attempt index, else 0), `reason u8` |
| 7 | `too_large` | — |
| 8 | `pairing_closed` | — |
| 9 | `internal` | optional `u8` length + UTF-8 text |

`invalid_config` fields: 1 `name`, 2 `cameraType`, 3 `wakeUpTimeout`, 4 `startBefore`, 5 `endAfter`, 9 `cameraIp` (6 to 8 are unused), 10 `attempts` (the list), 11 `attempts[i].name`, 12 `attempts[i].time`. Reasons: 1 required, 2 out of range, 3 bad value, 4 in the past, 5 duplicate, 6 too soon (the wake-up time has passed), 7 too many. The apps keep showing "{field}: {message}", built from these enums.

### 5.4 Status characteristic (13 bytes)

The v1 layout, plus the index of the next attempt so no extra fetch is needed.

| Offset | Type | Field |
| --- | --- | --- |
| 0 | u8 | `flags`: bit0 recording · bit1 camera ready · bit2 pairing window open · bit3 clock valid |
| 1 | u8 | `phase`: 0 idle · 1 awake · 2 recording · 3 test · 4 error |
| 2 | u32 | `configRev` |
| 6 | u32 | `nextEventAt` (Unix seconds, 0 for none) |
| 10 | u8 | `nextEventType`: 0 none · 1 wake · 2 start · 3 stop |
| 11 | u8 | `attemptsRemaining` |
| 12 | u8 | `nextAttempt`: index into the config's attempts, `0xFF` for none |

Notified on every change, and at most once per second while recording. The apps re-fetch the config when `configRev` changes, and the next-event name comes from `nextAttempt`.

### 5.5 Config layout

This is both the `config.get`/`config.set` body and the stored blob (§8).

| Field | Type | Notes |
| --- | --- | --- |
| `layout` | u8 | Config layout version, starts at 1 |
| `cameraType` | u8 | 0 `generic`, 1 `zcam` |
| `wakeUpTimeout` | u16 | seconds |
| `startBefore` | u16 | seconds |
| `endAfter` | u16 | seconds |
| `cameraIp` | 4 bytes | IPv4 (required when `zcam`) |
| `name` | u8 length + UTF-8 | ≤ 32 bytes |
| `count` | u8 | ≤ 30 |
| `attempts[count]` | `time u32`, then `name` (`u8` length + UTF-8, 1–32 bytes) | |

The maximum size is about 1.2 KB. `deviceId` and `configRev` are device-owned and are not part of this layout (`deviceId` is in Device Info, `configRev` is in Status). `logFile` is gone. The `config.set` rules are the v1 rules, with the validation errors above: required fields by camera type, attempt times valid, unique and in the future, wake-up time not already passed, and refused with `busy` unless idle.

### 5.6 Alternatives considered

- **CBOR or MessagePack.** About the same size win as hand-written layouts for a fixed schema, but each adds a dependency to three code bases, and self-describing overhead we don't need.
- **Protobuf (nanopb on the device).** Schema evolution is nice, but it adds code generation to PlatformIO, Gradle and an Xcode project that deliberately has no generator. Not worth it for about 15 messages.
- **Relying on BLE bonding alone and dropping the HMAC auth.** This would delete the secret, `hello`, `auth`, the lockout and mbedTLS use. We keep the app-layer HMAC because it works the same on both platforms, and it is the part covered by shared vectors. Bonding behavior (iOS "peer removed pairing", Android bond cache) differs by OS, and gating *new bonds* to the pairing window is awkward in NimBLE-Arduino. The security gain of bonding-only is also small, since Just Works bonding is not man-in-the-middle protected either way. Worth revisiting if the platform quirks turn out to be minor.
- **One GATT characteristic per setting.** No atomic save, many round trips.

### 5.7 What changes in the apps

Both apps, per `AGENTS.md`:
- Replace the JSON codec with the binary one: framing is unchanged, payloads are new. The `DeviceClient` request/response logic, timeouts and single-flight queue stay.
- Remove paging for jobs, remove `status.get`, derive job times from the config, and read the next attempt from Status.
- Build error text from the code plus the `invalid_config` field and reason enums. Drop the device-supplied message text.
- Remove the `logFile` handling, and send and receive the config as the binary layout. Camera IP is parsed and validated as IPv4 in the editor.
- Update the demo-mode fixtures, the app spec in `AGENTS.md` (§3 protocol summary, §6.5, §6.6, §7, §9), and pass the new test vectors.

## 6. Architecture

Everything protocol-related is **pure C++ with no Arduino or IDF includes**, so it builds in the PlatformIO `native` environment and runs against the shared vectors. There is no JSON anywhere in the pure code.

```
esp32/
  platformio.ini        envs: `device` (esp32-s3), `native` (host tests)
  partitions.csv
  src/main.cpp          setup(): wires modules, starts tasks. loop(): empty
  lib/
    protocol/           PURE: framing, message codecs, auth (HMAC), dispatcher, status encode, error codes
    config/             PURE: Config struct, validate, binary codec
    scheduler/          PURE: job planning, phases, next-event (port of lib/scheduler.js)
    cameras/            Camera interface; generic (GPIO), zcam (HTTP) behind small adapters
    platform/           ARDUINO-ONLY: ble (NimBLE-Arduino), eth (W5500), rtc, storage (NVS), button, log ring, clock
  test/                 Unity tests, run with `pio test -e native`
```

Pure libraries take their dependencies (clock, HMAC, random, HTTP, GPIO, storage) as small interfaces, so the native tests substitute fakes. HMAC-SHA256 on device uses mbedTLS, which the core includes. On the host, use a small test implementation checked against the vectors. ArduinoJson is used only by the Z CAM adapter, to read the camera's own `{code, msg}` replies.

Mapping from the Node tree:

| Node | ESP32 | Notes |
| --- | --- | --- |
| `trigger/ble/framing.js` | `protocol/framing` | Unchanged format |
| `trigger/ble/auth.js` | `protocol/auth` | HMAC-SHA256, constant-time compare, lockout, pairing window. Raw bytes |
| `trigger/ble/dispatcher.js` | `protocol/dispatcher` | One op table, binary bodies |
| `trigger/ble/status.js` | `protocol/status` | 13-byte little-endian |
| `trigger/ble/gatt.js`, `link.js` | `platform/ble` | NimBLE-Arduino GATT server, fragment queue, MTU |
| `trigger/controller.js` | `src/controller` | `save`, `setSecret`, `setTime`, `test` |
| `trigger/lib/config.js` | `config/` | Same rules, binary form. Fixed pins (§19) |
| `trigger/lib/scheduler.js` | `scheduler/` | Replace `node-schedule` (§7.2) |
| `trigger/lib/logger.js` | `platform/log` | 500-line RAM ring with `seq`. No file |
| `trigger/lib/system.js` | `platform/clock` | `setTime` writes the RTC. `disableWifi` becomes a no-op |
| `trigger/lib/button.js` | `platform/button` | GPIO interrupt, 50 ms debounce |
| `trigger/cameras/*` | `cameras/` | Same contract (`wake`, `start`, `stop`, `sleep`, `dispose`) |

### Tasks (FreeRTOS)

| Task | Priority | Does |
| --- | --- | --- |
| `sched` | high | Waits for the next event and runs camera steps (§7.2) |
| NimBLE host | mid | Managed by `NimBLE-Arduino`. Callbacks must only enqueue work |
| `cmd` | mid | Runs dispatcher requests off a queue (one at a time, as the protocol requires), so a slow Z CAM call never blocks the BLE stack |

Camera calls (especially the blocking Z CAM `HTTPClient`) run on `sched` or `cmd`, never in a BLE callback.

## 7. Time and the RTC

This is the biggest behavioral difference from the Pi, and it is a strict improvement.

### 7.1 Source of truth

- **UTC everywhere.** The RTC stores UTC. There are no time zones on the device.
- **At boot:** read the RTC. If valid (DS3231: OSF flag clear and year ≥ 2025), `settimeofday()` from it and set the Status `clock valid` flag (bit 3). If invalid, leave the system clock at the build time, clear the flag, and the schedule is **not armed** until the phone sets the clock. Log `Clock invalid`.
- **`time.set`** (authenticated, refused with `busy` unless idle): set the system clock, write the RTC, re-plan the scheduler, return `offsetMs = old − new`.
- **`time.get`** returns the system clock in ms. Bit 3 was "clock set since boot" in v1. In v2 it means "the clock is valid (set since boot, or restored from a valid RTC)".
- **Periodic discipline:** every 10 minutes while idle, compare the system clock to the RTC and, if they differ by more than 100 ms, step the system clock to the RTC and re-plan. The ESP32 crystal is ~±10 ppm, so this keeps it within about 6 ms per 10 minutes. During an active attempt, never step the clock.

### 7.2 Scheduler without node-schedule

`node-schedule` timers are absolute dates that Node re-evaluates for us. On the ESP32 a naive `esp_timer` delay is relative, so a clock step would silently break it. Instead:

1. `initialize(config)` builds the job list exactly as `Scheduler.initialize` does: `recordTime = launch − startBefore`, `wakeUpTime = recordTime − wakeUpTimeout`, `stopTime = launch + endAfter`. Any job whose wake time has already passed is `missed` (and emits a `job` event with step `missed`).
2. The `sched` task keeps a sorted list of pending steps (`wake`, `start`, `stop`) as **absolute UTC epoch ms**.
3. It sleeps until `min(next step, 1 s)` using a task notification with timeout, then re-reads the wall clock. Within 50 ms of a step it waits on a precise `esp_timer`. This makes it robust to clock steps (a re-plan wakes the task) and keeps jitter to a few ms.
4. `reinitialize()` (after `time.set`) and `save()` post a notification so the task re-sorts.

The state model (`pending`, `active`, `complete`, `missed`), phases (`idle`, `awake`, `recording`, `test`), `locked`, `next`, `attemptsRemaining`, and the `busy` rules are copied verbatim. Port the existing `test/` cases as native tests to guard that.

### 7.3 Sub-second accuracy (optional)

DS3231 and friends have 1 s resolution. When writing the RTC after `time.set`, delay the write to the next second boundary (or the 1 Hz SQW edge) so the RTC is within a few ms of the phone's time, not up to 1 s off. The boot restore then loses at most ~1 s versus the previous run, which is fine against the ±20 s recording window. If this proves fiddly, skip it for v1.

## 8. Storage: NVS only

There is no filesystem. Everything persistent is in NVS, written through `Preferences`.

| Namespace / key | Content | Size | Notes |
| --- | --- | --- | --- |
| `cfg` / `blob` | `configRev u32` + the Config layout (§5.5) | ≤ ~1.2 KB | One key, so a save is atomic: a power loss leaves the old config or the new one, never a mix |
| `auth` / `secret` | the 16-byte pairing secret | 16 B | Separate from the config, so it is never sent or overwritten by a config save |
| NimBLE store | BLE bonds | small | Managed by the BLE stack |

- **Device identity:** `deviceId` is derived from the last 3 bytes of the factory MAC (6 uppercase hex, the format the apps parse). It is not stored, and it is stable across erases. Keep the BLE address as the default **public** address (derived from the same MAC), never a random one, because phone bonds are tied to it.
- **First boot** (nothing stored): use in-code defaults: empty `name`, `generic`, timings 5 / 20 / 30 s, no attempts. The device is immediately pairable and configurable over BLE.
- **Saving:** validate → one `putBytes` of the new blob → re-plan the scheduler → emit the `config` event. `configRev` increments on every successful save.
- **If the stored blob fails to decode or validate** (corruption, or a future layout it doesn't know), log it and fall back to the defaults. Keep the secret and bonds.
- **Validation** is a port of `validateConfig` into the binary form (§5.5 field and reason codes). One deliberate difference: the pins are not configurable on the S3. They are constants in `board.h`, and a config naming other pins is refused with `invalid_config` (reason "not valid").
- **Logs:** a 500-line RAM ring (about 60 KB worst case at 120-byte messages), each line with a `seq`. It is lost at reset. At boot, log `esp_reset_reason()` as the first line, so a brownout or watchdog reset is visible to the phone afterwards. For crash detail, the `coredump` partition is raw flash, not a filesystem (§13).
- **Partitions** (a custom `partitions.csv`, selected with `board_build.partitions`). Use an 8 MB flash part (the N8 DevKitC modules have it). A 4 MB part also works with 1.75 MB app slots.

  | Name | Type | Size | Notes |
  | --- | --- | --- | --- |
  | `nvs` | data/nvs | 32 KB | **Frozen for the life of the product** (below) |
  | `otadata` | data/ota | 8 KB | Which slot boots, and rollback state |
  | `phy_init` | data/phy | 4 KB | |
  | `app0` | app/ota_0 | 2 MB | Firmware (expected ~1.1–1.3 MB) |
  | `app1` | app/ota_1 | 2 MB | Idle until OTA exists. Reserved now so adding OTA later needs no repartition or re-flash |
  | `coredump` | data/coredump | 64 KB | Post-mortem crashes (§13) |

- **Why 32 KB is plenty:** a save writes a fresh ~1.2 KB blob and NVS reclaims the old copy later, so the config needs a few KB at a time. The secret and bonds are tiny. NVS rotates writes across pages, and saves only happen when someone edits the schedule, so wear is not a concern.
- **The `nvs` partition's offset and size are frozen.** Moving or resizing it invalidates what is stored, and so would a full flash erase. Only `app0`, `app1` and the empty space may change later.
- **Pairing and bonds survive re-flashing** as long as NVS is untouched (§14 has the flashing rules). A factory reset (flash erase, or a future long-press on the button) clears the secret, bonds and config on purpose. A phone that still holds the old bond must then forget the device once in system Bluetooth settings. Say so in the user docs.

## 9. BLE

Implement `docs/ble-protocol.md` (v2: GATT layout, advertising, framing, security) with `NimBLE-Arduino`.

- **GATT:** one primary service `6b610001-6169-4f72-8f73-b3e1c5a97d24`. Device Info (read, now binary), Status (read + notify, 13 bytes), Command (`WRITE` | `WRITE_NR` | `WRITE_ENC`, so it **requires an encrypted link**), Response (notify).
- **Device Info (binary):** `v u8` (2), `id[3]` (the 6 hex digits as raw bytes), `cam u8`, `fw` (`u8` length + ASCII), `name` (`u8` length + UTF-8). It is short, but keep using Read Long.
- **Connections:** `-DCONFIG_BT_NIMBLE_MAX_CONNECTIONS=1` in `build_flags`. Stop advertising on connect, restart on disconnect. Interval ~1 s.
- **Advertising:** service UUID (128-bit, 18 bytes) in the advertisement. Local name in the scan response. The 31-byte scan response limits the name to 29 bytes.
  - **Pitfall (also in the Node code):** `gatt.js` truncates `deviceName.slice(0, 29)`, which cuts off the `-ID` suffix for names over ~22 characters, and the apps identify "already paired" devices by that suffix. On the ESP32 truncate the **name part**, not the ID: `name[:22] + "-" + ID`. State this rule in the v2 protocol doc.
- **MTU:** `NimBLEDevice::setMTU(517)`. The apps already size writes to `MTU − 3`. Frame notifications to the negotiated `MTU − 3`. Pace fragments by at least one connection interval. A bigger MTU means far fewer fragments than the Pi's default of 23.
- **Security:** `NimBLEDevice::setSecurityAuth(bond=true, mitm=false, sc=true)`, Just Works, bonds stored in NVS. The shared-secret check at the application layer is what actually gates control, as today.
- **Entropy:** `esp_fill_random()` is a true RNG only while the RF subsystem is on. BLE is always on here, but generate the nonce after BLE start, and note it in code.
- **Notifications:** Status at most once per second while recording, plus on every change. Debounce on the `cmd`/`sched` side, not in the BLE callback.
- **`fw`** is a version string injected as a build flag (`-DFW_VERSION`), the same value an OTA slot will report later.

## 10. Z CAM over Ethernet

No Wi-Fi at all. The camera is on the W5500 link.

- **Driver:** the core's `ETH` library: `ETH.begin(ETH_PHY_W5500, phy_addr, CS, INT, RST, SPI)` on SPI2 at 20 MHz (INT pin in use). Check the exact signature against the pinned core version, because it changed between 2.x and 3.x.
- **Addressing:** the Z CAM provides DHCP (to be confirmed on the hardware, §18), so the device is a **DHCP client** on the Ethernet interface. The stored `cameraIp` stays the address the firmware calls. DHCP is the `ETH` default and starts when the link comes up. Keep the lease across `wake`/`sleep`.
  - **Fallback:** if no lease arrives within ~10 s, call `ETH.config()` with a static IP derived from `cameraIp` (same /24, host `.250`, or `.249` if that is the camera's address). This also covers a camera set to a static IP with its DHCP server off.
  - Log the leased address and gateway. If `cameraIp` is not on the leased subnet, log a warning, because it is the likely cause of a failed `wake`.
- **Link events:** subscribe with `Network.onEvent` (Ethernet connected / got IP / disconnected). At `wake`, wait up to a few seconds for link-up and for the camera to answer before declaring failure. `cameraReady` in Status reflects link up *and* last successful call.
- **API calls** (same endpoints as `zcam-api.js`): `GET /ctrl/session`, `/ctrl/mode?action=to_standby`, `/ctrl/rec?action=start|stop|remain`. Use `HTTPClient` with `setConnectTimeout` and `setTimeout` of 3–5 s per call and one retry on the time-critical `start`/`stop`. It blocks, so it runs on `sched` or `cmd` only. The protocol's request timeout for camera actions is 20 s, so retries fit. Parse the camera's `{code, msg}` reply with ArduinoJson.
- **Parity quirk:** the Node driver catches and logs errors rather than failing the step, so a `test.run` can report success with no camera. Keep that for parity in v1, but note it as follow-up (§18).
- **No Wi-Fi:** never call `WiFi.begin()`, `WiFi.mode()` or anything else that starts the Wi-Fi driver, so the radio stays off. The code is still linked into the prebuilt core (§4). `System.disableWifi()` has no ESP32 equivalent and is simply gone. Don't add a library that starts Wi-Fi for you.

## 11. Generic GPIO camera

- Use `pinMode`/`digitalWrite` for release and focus (focus defaults to the release pin when unset, like the Node driver). Drive through the optocoupler/MOSFET stage from §3.1.
- **Pulse width (TBD, tuned during bring-up):** the Node driver writes `1` then `0` back-to-back (effectively the shortest pulse the Pi can make). The ESP32 behavior is not decided. Start with a single named constant (initial guess 100 ms) in one place, and settle it, along with polarity and any focus-before-release delay, while debugging against the real cameras. Don't expose it over BLE unless it turns out to need per-camera tuning.
- `start`/`stop` toggle, guarded by `recording` as today. `dispose()` releases pins (`gpio_reset_pin`, available through the IDF API under Arduino) when the config is replaced.
- GPIO state at boot and during reset must be **inactive** (no accidental shutter). Verify the pin default state of every chosen pin in the S3 datasheet, and use a pull-down on the driver-stage input.

## 12. Button and pairing

- GPIO interrupt on press, 50 ms debounce, opens the 120 s pairing window, which sets Status flag bit 2, and closes after one success or on timeout. This is identical to `PairingWindow`.
- Lockout: 5 consecutive `auth` failures → `locked_out` for 30 s, doubling to a maximum of 5 min. Keep this state in RAM, so a reboot resets it. This is the same as Node.
- Optional (nice for a boardless install): a status LED. Not in scope, because the apps don't depend on it.

## 13. Reliability

An ESP32 reboots in ways a Linux box rarely does, so plan for it:

- **Task watchdog** (`esp_task_wdt_add`) on `sched` and `cmd`. A hung HTTP call must not stop the schedule. Give `HTTPClient` explicit timeouts.
- **Brownout detector** is on in the Arduino core's defaults. Keep it, add decoupling for BLE/W5500 bursts, and log `esp_reset_reason()` as the first log line at boot.
- **State after reset.** Matches the Pi: job state is in memory, so after a reboot any attempt whose wake time has passed becomes `missed`, even one that was mid-recording. No journal is kept. (Decided. A persistent journal that stops a stranded recording is a possible later improvement, not part of the port.)
- **Core dumps** to the `coredump` partition, if the pinned core's prebuilt config has it enabled (§4). Otherwise rely on the logged reset reason.
- **Secure boot and flash encryption** are not available in the prebuilt Arduino core (§4). If they are required for deployment, that is the trigger to switch to Arduino-as-an-IDF-component.

## 14. Build, flash, update

- **Toolchain:** PlatformIO with the `pioarduino` platform (Arduino-ESP32 core 3.x), `board = esp32-s3-devkitc-1`, `framework = arduino`, `board_build.partitions = partitions.csv`. There is no filesystem image. Pin the platform and every library version (`NimBLE-Arduino`, `ArduinoJson`).
- **Dev loop:** `pio run -e device -t upload` and `pio device monitor` over the DevKitC's USB. No special hardware.
- **Flashing rules that protect pairing and the schedule:**
  - Use plain `upload`. It writes the bootloader, partition table and app, and leaves NVS alone.
  - Don't run `erase` (or any "erase all flash" option) unless you mean a factory reset.
  - Don't change the `nvs` partition (§8).
- **Updates in v1:** reflash over USB.
- **CI:** one ESP32 job in the existing workflow: `pio test -e native`, then `pio run -e device` to prove it builds. Separate from the iOS and Android jobs.

### BLE OTA (future, not in v1)

Nothing in the port blocks it, and §8 reserves the flash. The transport is the same NimBLE stack. On Arduino the write path is `Update` (a wrapper around `esp_ota_ops`), or `esp_ota_ops` directly where you need rollback.

- **Protocol:** a firmware image of 1 MB or more doesn't suit a 2 KB message and the one-request-in-flight rule. Add a bulk write-without-response characteristic, or ops such as `fw.begin` / `fw.chunk` / `fw.end` with chunk acknowledgements, resume and a SHA-256 check. Authenticated only, and refused unless the device is idle. This is an additive change to v2.
- **Apps:** both need a firmware picker and a progress screen.
- **Device:** write to the inactive slot, verify the hash, switch slots, and **roll back automatically if the new image doesn't confirm itself** (`esp_ota_mark_app_valid_cancel_rollback()`). Rollback depends on the core's bootloader supporting it (§4). Confirm that before promising OTA to anyone, because an unreachable field unit that gets a bad image is the worst case.
- **Speed:** with a 247–517 byte MTU expect roughly 5–20 KB/s, so a 1.5 MB image takes a few minutes.
- A slot swap leaves NVS alone, so pairing, bonds and the schedule survive an update.

## 15. Testing

1. **Native unit tests** (`pio test -e native`, Unity) for `protocol`, `config` and `scheduler`:
   - Run the shared `docs/test-vectors.json`, regenerated for v2: framing, HMAC proofs (v2 labels), Status decoding, **every message encode and decode**, the Config layout, and the error bodies. The firmware and both apps must pass the same vectors.
   - Port `test/config.test.js`, `test/framing.test.js`, `test/protocol.test.js` and the scheduler cases, using a fake clock and fake camera, as the Node tests do.
2. **Hardware-in-loop smoke tests** (manual checklist): pair, `time.set`, config save, test run, manual wake/start/stop/sleep, reboot with valid and invalid RTC, power-cycle with the coin cell, pull the Ethernet cable mid-attempt, **re-flash with `upload` and confirm the phone still connects without re-pairing**.
3. **Real apps:** connect both the iOS and Android apps. Each screen in `AGENTS.md` §6 must behave as before. This is the acceptance test.
4. **Timing:** log the difference between scheduled and actual `start`/`stop` times over a week with a scope or logic analyzer on the output pins. Target: under 50 ms.
5. **Soak:** 7 days with one scheduled attempt per hour and a real camera.

## 16. Milestones

| # | Deliverable | Done when |
| --- | --- | --- |
| 0 | **Spike** on one bench board: BLE advertising with an encrypted write, W5500 DHCP plus one Z CAM HTTP call, RTC read/write, a ~1.2 KB NVS blob write, and a bond that survives `upload` | Each piece works together on the S3, and the version-specific API assumptions in §4 are confirmed or corrected |
| 1 | **Protocol v2 spec**: rewrite `docs/ble-protocol.md`, new vectors and their generator (`test/vectors.js`, kept in the Node tree until Node is retired) | Reviewed, with vectors checked in |
| 1b | **Node device speaks v2** (`trigger/ble/`: codecs, dispatcher, Status, Device Info, errors). Config stays `config.json` internally and is converted at the edge. Also fix the advertised-name truncation | Node passes the v2 vectors, and a Pi running it works with the v2 apps. This is the real-device test bed for milestone 2 |
| 2 | **Apps**: `KairosKit` and the Android protocol module speak v2 and pass the vectors. UI and demo-mode changes (§5.7) | Both apps' tests pass, demo mode shows every screen on both, and both connect to the v2 Node device. Can run in parallel with 3 |
| 3 | `esp32/` PlatformIO skeleton, `partitions.csv`, CI build, native test harness. `protocol` + `config` + `scheduler` (pure) | Vectors and ported Node tests pass in the `native` env |
| 4 | `platform/ble` + NVS storage | Apps scan, connect, pair and authenticate, and config save and reload work |
| 5 | `rtc` + clock logic | `time.set`/`time.get`, reboot restore, periodic discipline |
| 6 | `cameras/generic` + button | Real camera triggers on schedule and manually |
| 7 | `eth` + `cameras/zcam` | Z CAM wake/start/stop/sleep and test run over Ethernet |
| 8 | Reliability pass (§13) and soak | 7-day soak passes |
| 9 | Docs: README section, `AGENTS.md`, wiring diagram. Retire the Node device code and move the vector generator out of the Node tree | Reviewed, and the ESP32 has run the soak in place of the Pi |

## 17. Docs and repo changes that come with the port

- Rewrite `docs/ble-protocol.md` as v2 (from §5 here), and regenerate `docs/test-vectors.json`. The Node reference (`trigger/ble/`) is updated to v2 and the vectors keep being generated from it (`node test/vectors.js --write`) while Node exists. When Node is retired, move the generator out of the device tree.
- Update `AGENTS.md`: the repository layout (add `esp32/`), the app spec's protocol summary and sections affected by §5.7, and the test commands (`cd esp32 && pio test -e native`).
- README: hardware section and wiring diagram for the ESP32 variant.
- The Node device code (`trigger/`, `test/`) is updated to v2 (milestone 1b) and kept until the ESP32 fully replaces it, then removed (milestone 9). The Node `package.json` dependencies and the Pi setup docs go with it.

## 18. Decisions and open questions

**Decided**

- **Power:** battery is external in v1. No sleep modes yet. Measure real draw during bring-up.
- **Z CAM addressing:** DHCP client, with the static fallback in §10.
- **Crash recovery:** match the Pi (§13). No journal.
- **GPIO pulse behavior:** deferred to debugging (§11).
- **Framework:** Arduino-ESP32 on PlatformIO, via the `pioarduino` platform (§4).
- **Storage:** NVS only, no filesystem, no persistent log (§8). Flash layout reserves two app slots. BLE OTA itself is deferred (§14).
- **Protocol:** binary v2, with no v1 compatibility (§5).
- **Node device:** updated to v2 as the interim device and reference, then replaced fully by the ESP32 and removed (milestones 1b and 9).

**Open**

1. **When exactly is Node removed?** Planned at milestone 9, after the ESP32 soak. Confirm that, or pick an earlier trigger.
2. **Keep app-layer HMAC auth (recommended) or move to bonding-only** (§5.6)?
3. **Attempt time precision:** u32 seconds, and name limits in bytes (§5.1). Confirm that is acceptable for the apps' editors.
4. **Confirm the Z CAM's DHCP server** is on in the camera's Ethernet settings, and which address it hands out versus `cameraIp`.
5. **Button polarity**, and whether a status LED is wanted.
6. **`test.run` honesty:** keep Node's swallow-and-log behavior, or make failures real?
7. **Flash encryption / secure boot** for deployed units, yes or no? Not available in the prebuilt Arduino core (§4). A yes means switching to Arduino-as-an-IDF-component.
8. **RTC part:** DS3231 recommended, final choice pending (§3.3).
9. **Bootloader rollback and core dumps:** confirm whether the pinned Arduino core's prebuilt config enables them (§4, §13, §14). It decides whether OTA is safe to build later.

## 19. Implementation notes

The firmware in `esp32/`, the v2 protocol in [ble-protocol.md](ble-protocol.md), the Node device and both apps follow this document, except for these differences:

- **Platform version.** The firmware pins the pioarduino platform **55.03.311** (Arduino-ESP32 3.3.11) and NimBLE-Arduino **2.5.1**. 55.03.312 needs PlatformIO Core 6.2 or newer.
- **One task.** Instead of separate `sched` and `cmd` tasks (section 6), one `core` task owns all device state: BLE writes (queued by the radio callbacks), scheduled steps, notifications, the RTC discipline and the button. There are no locks. The cost is that a Z CAM call of up to several seconds delays the next request or step. The watchdog is set to 60 s for that reason.
- **No `busy` for overlapping requests.** The protocol asks for `busy` when a second request arrives while one runs. With one task, a second request simply waits its turn in the queue. Clients never send overlapping requests.
- **Test run** is a small state machine stepped by the scheduler tick (wake, 1 s, start, 2 s, stop, sleep, done), so `test.run` returns at once.
- **Clock discipline** only steps the clock when the system and the RTC differ by two seconds or more, since the RTC has 1 s resolution and is written truncated. Sub-second alignment (section 7.3) is not implemented.
- **Fixed pins, not in the protocol.** The button, release and focus pins are the constants `KAIROS_BUTTON_PIN`, `KAIROS_RELEASE_PIN` and `KAIROS_FOCUS_PIN` in `esp32/src/platform/board.h`, next to the W5500 and RTC pins. The config layout and the apps carry no pins, so there is no allow-list or pin validation. To use another board layout, edit the constants and rebuild.
- **Storage.** `NvsStorage` keeps `[revision][config layout]` in one NVS key and the secret in another; `Controller` falls back to the defaults if the stored config doesn't decode.
- **Device id** comes from the Bluetooth MAC (`ESP_MAC_BT`), the last 3 bytes.
- **Wi-Fi code.** The prebuilt core still links it. Nothing starts it.

Still open until it runs on hardware: the trigger pulse width for generic cameras, the Z CAM's DHCP behavior, the pairing-and-bond flow on both phone platforms, and whether the core's bootloader supports rollback (needed before OTA).
