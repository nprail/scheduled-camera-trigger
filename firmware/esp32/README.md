# Kairos firmware for the ESP32-S3 and ESP32-C3

The device side of Kairos on an ESP32-S3 or an ESP32-C3, with an external RTC and a W5500 Ethernet module for a Z CAM. It is the recommended Kairos device. It speaks the same Bluetooth protocol as the legacy Raspberry Pi version in [`firmware/legacy-pi/`](../legacy-pi/) (protocol v2, [docs/ble-protocol.md](../../docs/ble-protocol.md)), so the iPhone and Android apps work with either. The design and the reasons behind it are in [docs/esp32-port.md](../../docs/esp32-port.md). Wiring and parts are in [docs/hardware.md](../../docs/hardware.md).

> **Status:** the firmware builds, and its logic is covered by host tests that run the shared protocol vectors. It has **not been run on hardware yet**. The first bring-up on a real board is where the remaining open items in the design doc (the generic camera's trigger pulse, the Z CAM's DHCP behavior, and the over-Bluetooth update: its speed, and a rollback from a deliberately crashing image) get settled.

## Build, test and flash

You need [PlatformIO](https://platformio.org) Core 6.1.19 or newer.

```sh
cd firmware/esp32
pio test -e native            # host unit tests: protocol, config, scheduler, cameras (no hardware)
pio run -e device             # build the firmware for an ESP32-S3
pio run -e device -t upload   # flash over USB
pio run -e device-c3          # build it for an ESP32-C3 (add -t upload to flash)
pio run -e device-dev         # a developer build: accepts unsigned updates over Bluetooth (device-c3-dev for the C3)
pio device monitor            # serial log
```

The `device` (ESP32-S3) and `device-c3` (ESP32-C3) environments build the same firmware. They differ only in the pins ([src/platform/board.h](src/platform/board.h), chosen by the chip) and the partition table: the C3 uses [partitions_4mb.csv](partitions_4mb.csv) for the usual 4 MB flash, with the same frozen `nvs` partition. Both use the pioarduino platform (Arduino-ESP32 core 3.3.x) and NimBLE-Arduino, both pinned in [platformio.ini](platformio.ini). The first build downloads the toolchain.

**Flashing keeps the pairing and the schedule.** They live in NVS, which `upload` doesn't touch. `upload` also writes a blank `otadata` ([otadata.py](otadata.py)): after an update over Bluetooth the device boots the other app slot, and without this the new USB image would be written but never run. Don't run `pio run -t erase` (or any "erase all flash" option) unless you want a factory reset, and never change the `nvs` partition in [partitions.csv](partitions.csv). After a factory reset, a phone that still holds the old bond must forget the device once in its Bluetooth settings.

## Versions and releases

The version the device reports (`fw` in Device Info) is set at build time by [version.py](version.py): `$KAIROS_VERSION` if set, else `git describe`, else `dev`.

Pushing a tag like `v1.2.0` runs [release.yml](../../.github/workflows/release.yml), which tests, builds both chips with that version, and attaches these to a GitHub Release, with `SHA256SUMS`:

- `kairos-<chip>-<version>-factory.bin`: the whole flash image, written at `0x0`. For a blank board. **It erases the pairing and the schedule.**
- `kairos-<chip>-<version>-app.bin`: the app alone, written at `0x20000`. For updating a paired board; it keeps NVS.

Every CI run also uploads the same binaries as a `firmware` artifact.

## Updates over Bluetooth

The apps can update a device over Bluetooth: see [docs/updating-firmware.md](../../docs/updating-firmware.md). Release images are signed with ECDSA P-256 by the release workflow (the private key is the `KAIROS_SIGNING_KEY` repository secret), and the device holds the public key in [src/platform/signing_keys.h](src/platform/signing_keys.h). [tools/signing.py](tools/signing.py) makes keys, signs images and writes the release `manifest.json`; its tests run with `python3 -m unittest discover -s tools`. [MIN_FROM](MIN_FROM) is the oldest firmware allowed to update directly to a release.

The `-dev` environments build with `KAIROS_ALLOW_UNSIGNED`, which lets a device take an unsigned image: for developers, flagged in the boot log, and never released.

## Layout

| Path | What |
| --- | --- |
| `lib/kairos/` | The firmware logic, in plain C++ with no Arduino or ESP-IDF includes, so it is tested on the host. It is one PlatformIO library. |
| `lib/kairos/protocol/` | Framing, binary messages, HMAC auth, the dispatcher and the per-connection `Link`. |
| `lib/kairos/config/` | The `Config` struct, its binary layout (also the form stored in flash) and validation. |
| `lib/kairos/update/` | The firmware update receiver (`Updater`): offset window, resume, header and size checks, timeouts. It sees the flash and the signature check only through small interfaces. |
| `lib/kairos/scheduler/` | The scheduler, the `Controller` that implements the protocol's device API, and the log ring. |
| `lib/kairos/cameras/` | The generic GPIO camera and the Z CAM HTTP camera, behind small `Gpio` and `Http` interfaces. |
| `src/platform/` | Everything that touches hardware: NimBLE server, W5500 Ethernet, DS3231 RTC, NVS storage, the button, the system clock, the OTA slot (`ota_flash`), the signature check (`ecdsa_verifier`) and the version tag (`fw_tag`). |
| `tools/` | `signing.py`: keys, signatures, the manifest. |
| `src/main.cpp` | Wires it together. One `core` task owns all device state; BLE callbacks only post to a queue. |
| `test/test_native/` | Host tests. They load `protocol/test-vectors.json`, the same file the Node reference and both apps pass. |

## Pins

The W5500 Ethernet module is optional: it is only needed to control a Z CAM. A `generic` (remote trigger) setup works without it. At boot the firmware reads the chip's version register; if the module does not answer, Ethernet is never started and Device Info reports only the `generic` camera type, so the apps hide the Z CAM option.

Every pin is a constant in [src/platform/board.h](src/platform/board.h). The values below are for an ESP32-S3-DevKitC-1 (verify them against your board). The ESP32-C3 has its own set, below.

| Function | GPIO |
| --- | --- |
| W5500 SCLK / MOSI / MISO / CS (Z CAM only) | 12 / 11 / 13 / 10 |
| W5500 INT / RST (Z CAM only) | 14 / 9 |
| RTC SDA / SCL (DS3231) | 8 / 18 |
| Pairing button | 0, the BOOT button |
| Camera release / focus | 5 / 6 |

**The pins are not configurable at run time, and not part of the protocol.** The apps and the BLE config carry no pins. To use different pins, edit the constants in `src/platform/board.h`, rebuild and flash:

```cpp
constexpr uint8_t KAIROS_BUTTON_PIN = 0;        // the BOOT button
constexpr bool KAIROS_BUTTON_ACTIVE_LOW = true;  // to ground, with a pull-up
constexpr uint8_t KAIROS_RELEASE_PIN = 5;
constexpr uint8_t KAIROS_FOCUS_PIN = 6;  // or kairos::kNoPin: wake then pulses the release line
constexpr int KAIROS_RTC_SDA = 8;        // and the W5500 and RTC pins above it
```

Avoid the strapping pins (0, 3, 45, 46), USB (19, 20) and flash/PSRAM (26 to 37), except for the BOOT button, which is only read after boot. Pairing: press the BOOT button (not while resetting the board). The build stops if the button shares a pin with a camera line.

### ESP32-C3 (ESP32-C3-DevKitM-1)

The C3 has only GPIO 0 to 21. After the strapping pins (2, 8), USB (18, 19), the console UART (20, 21) and the flash (12 to 17), only a few pins are left, and they are all used. Pairing uses the BOOT button (GPIO 9) so no extra button is needed:

| Function | GPIO |
| --- | --- |
| W5500 SCLK / MOSI / MISO / CS (Z CAM only) | 4 / 6 / 5 / 7 |
| W5500 INT / RST (Z CAM only) | 3 / not wired (-1) |
| RTC SDA / SCL (DS3231) | 0 / 1 |
| Pairing button | 9, the BOOT button |
| Camera release | 10 |
| Camera focus | not wired: wake pulses the release line |

With RST not wired, the firmware does not reset the W5500 before probing it. The C3 has one core, so the `core` task runs on core 0; the firmware is otherwise identical.

## Behavior notes

- **Clock.** The RTC restores the clock at boot. If the RTC is missing or untrusted, nothing is scheduled until a phone sets the clock. Every 10 minutes while idle, the system clock is stepped to the RTC if they differ by a whole second.
- **Crash recovery** matches the legacy Raspberry Pi version: job state is in memory, so after a reset any attempt whose wake time has passed is `missed`.
- **Wi-Fi is never started.** The prebuilt Arduino core still contains the Wi-Fi code, but nothing calls it.
- **One task, no locks.** A slow Z CAM call (up to its 5 s HTTP timeouts) delays the next BLE request or scheduled step by that long at most.
- **Generic camera trigger pulse** is a single constant, `kTriggerPulseMs` (100 ms), to be settled against real cameras.
