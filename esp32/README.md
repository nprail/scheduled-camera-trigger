# Kairos firmware for the ESP32-S3

The device side of Kairos on an ESP32-S3, with an external RTC and a W5500 Ethernet module for a Z CAM. It speaks the same Bluetooth protocol as the Raspberry Pi version in [`trigger/`](../trigger/) (protocol v2, [docs/ble-protocol.md](../docs/ble-protocol.md)), so the iPhone and Android apps work with either. The design and the reasons behind it are in [docs/esp32-port.md](../docs/esp32-port.md). Wiring and parts are in [docs/hardware.md](../docs/hardware.md).

> **Status:** the firmware builds, and its logic is covered by host tests that run the shared protocol vectors. It has **not been run on hardware yet**. The first bring-up on a real board is where the remaining open items in the design doc (the generic camera's trigger pulse, the Z CAM's DHCP behavior, whether the Arduino core's bootloader supports rollback) get settled.

## Build, test and flash

You need [PlatformIO](https://platformio.org) Core 6.1.19 or newer.

```sh
cd esp32
pio test -e native            # host unit tests: protocol, config, scheduler, cameras (no hardware)
pio run -e device             # build the firmware
pio run -e device -t upload   # flash over USB
pio device monitor            # serial log
```

The `device` environment uses the pioarduino platform (Arduino-ESP32 core 3.3.x) and NimBLE-Arduino, both pinned in [platformio.ini](platformio.ini). The first build downloads the toolchain.

**Flashing keeps the pairing and the schedule.** They live in NVS, which `upload` doesn't touch. Don't run `pio run -t erase` (or any "erase all flash" option) unless you want a factory reset, and never change the `nvs` partition in [partitions.csv](partitions.csv). After a factory reset, a phone that still holds the old bond must forget the device once in its Bluetooth settings.

## Layout

| Path | What |
| --- | --- |
| `lib/kairos/` | The firmware logic, in plain C++ with no Arduino or ESP-IDF includes, so it is tested on the host. It is one PlatformIO library. |
| `lib/kairos/protocol/` | Framing, binary messages, HMAC auth, the dispatcher and the per-connection `Link`. |
| `lib/kairos/config/` | The `Config` struct, its binary layout (also the form stored in flash) and validation. |
| `lib/kairos/scheduler/` | The scheduler, the `Controller` that implements the protocol's device API, and the log ring. |
| `lib/kairos/cameras/` | The generic GPIO camera and the Z CAM HTTP camera, behind small `Gpio` and `Http` interfaces. |
| `src/platform/` | Everything that touches hardware: NimBLE server, W5500 Ethernet, DS3231 RTC, NVS storage, the button, the system clock. |
| `src/main.cpp` | Wires it together. One `core` task owns all device state; BLE callbacks only post to a queue. |
| `test/test_native/` | Host tests. They load `docs/test-vectors.json`, the same file the Node reference and both apps pass. |

## Pins

Every pin is a constant in [src/platform/board.h](src/platform/board.h). The values below are for an ESP32-S3-DevKitC-1 (verify them against your board):

| Function | GPIO |
| --- | --- |
| W5500 SCLK / MOSI / MISO / CS | 12 / 11 / 13 / 10 |
| W5500 INT / RST | 14 / 9 |
| RTC SDA / SCL (DS3231) | 8 / 18 |
| Pairing button | 4 |
| Camera release / focus | 5 / 6 |

**The pins are not configurable at run time, and not part of the protocol.** The apps and the BLE config carry no pins. To use different pins, edit the constants in `src/platform/board.h`, rebuild and flash:

```cpp
constexpr uint8_t KAIROS_BUTTON_PIN = 4;
constexpr uint8_t KAIROS_RELEASE_PIN = 5;
constexpr uint8_t KAIROS_FOCUS_PIN = 6;  // or kairos::kNoPin: wake then pulses the release line
constexpr int KAIROS_RTC_SDA = 8;        // and the W5500 and RTC pins above it
```

Avoid the strapping pins (0, 3, 45, 46), USB (19, 20) and flash/PSRAM (26 to 37). The build stops if the button shares a pin with a camera line. 

## Behavior notes

- **Clock.** The RTC restores the clock at boot. If the RTC is missing or untrusted, nothing is scheduled until a phone sets the clock. Every 10 minutes while idle, the system clock is stepped to the RTC if they differ by a whole second.
- **Crash recovery** matches the Raspberry Pi version: job state is in memory, so after a reset any attempt whose wake time has passed is `missed`.
- **Wi-Fi is never started.** The prebuilt Arduino core still contains the Wi-Fi code, but nothing calls it.
- **One task, no locks.** A slow Z CAM call (up to its 5 s HTTP timeouts) delays the next BLE request or scheduled step by that long at most.
- **Generic camera trigger pulse** is a single constant, `kTriggerPulseMs` (100 ms), to be settled against real cameras.
