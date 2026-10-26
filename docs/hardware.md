# Hardware

Kairos is built from a Raspberry Pi Zero W, a few GPIO connections to your camera, and a button. This page describes what the software expects, with a typical way to wire it. The software and protocol are MIT licensed. See [Status of this document](#status-of-this-document) for what is still missing.

## Overview

```plaintext
                 Bluetooth LE
   Phone  ◄───────────────────────►  Raspberry Pi Zero W
 (iOS/Android)                         │
                                       ├── GPIO ──► remote trigger port   (camera type: generic)
                                       ├── GPIO ◄── pairing button
                                       └── USB Ethernet ◄──► Z CAM        (camera type: zcam)
```

Wi-Fi is off: the device is controlled over Bluetooth, and a Z CAM is wired over Ethernet.

## Parts

| Part | Notes |
| --- | --- |
| Raspberry Pi Zero W | The device. Runs Raspberry Pi OS and the Node.js program in [`trigger/`](https://github.com/nprail/kairos/tree/main/trigger). Provides Bluetooth Low Energy for the phone. |
| microSD card | For the OS and `config.json`. |
| 5 V power supply | The Pi Zero W is low power, so a small battery pack works. |
| Push button | Momentary, normally open. Opens the 2-minute pairing window. |
| Pull-down resistor for the button | Needed unless you enable the Pi's internal pull-down (below). |
| **Generic cameras:** one optocoupler (or small transistor) per line, plus a resistor for each | Switches the camera's shutter (and optionally focus) line. See [Generic camera](#generic-camera-remote-trigger-port). |
| **Generic cameras:** a cable or plug for your camera's remote trigger port | Varies by camera. Check your camera's manual for the pinout. |
| **Z CAM only (optional):** a USB Ethernet adapter and a short Ethernet cable | Only needed for the Z CAM integration. The Pi Zero W has no Ethernet port. Use a micro-USB OTG Ethernet adapter. |
| Enclosure | Not specified yet. |

## GPIO pins

Pins are BCM GPIO numbers, set in `config.json` (see [Configuration](configuration.md)). The apps do not change them. The example config uses these:

| Function | Config option | Example | Direction |
| --- | --- | --- | --- |
| Pairing button | `buttonGpioPort` | 17 | Input, rising edge, 50 ms debounce |
| Shutter release | `generic.releaseGpioPin` | 25 | Output |
| Focus (optional) | `generic.focusGpioPin` | 23 | Output |

The GPIO pins are 3.3 V logic and cannot drive a camera's trigger line directly. Use the isolation described below.

## Generic camera (remote trigger port)

For cameras with a standard remote trigger port, the Pi closes a switch between the camera's shutter line and its common line, the same as pressing a wired remote. Wake is a pulse on the focus line (or the release line, if no focus pin is set). Start and stop each pulse the release line once.

A typical circuit uses an optocoupler such as a PC817 for each line, so the Pi is electrically isolated from the camera:

```plaintext
 Pi GPIO ──[ 330 Ω ]──► LED ┐   ┌ collector ──► camera shutter (or focus) line
                            │PC │
 Pi GND  ────────────────── LED ┘   └ emitter   ──► camera common (ground)
```

Notes:

- This is a typical circuit, not a tested reference design. Check that your camera's shutter and focus lines are switched to a common line. Some cameras differ.
- Do not connect a camera line to a Pi pin directly, and never connect a line that carries more than 3.3 V to a Pi pin.
- Check the pulse the camera needs. The software writes the pin high and then low back to back.

## Z CAM (Ethernet)

For a Z CAM, set `cameraType` to `zcam` and `zcam.cameraIp` to the camera's address. The Pi talks to the camera over its HTTP API on the wired Ethernet link: it opens a session to wake the camera, starts and stops recording, and puts the camera into standby when an attempt completes.

- Connect the USB Ethernet adapter to the Pi's data USB port and run a cable to the camera.
- Give the Pi's Ethernet interface an address on the camera's network. The firmware doesn't configure this for you.
- Wi-Fi stays off, so the camera must be reachable over Ethernet.

## Pairing button

Wire the button between the pin set in `buttonGpioPort` and 3.3 V. The software listens for a rising edge, so the pin must read low until the button is pressed. Add a pull-down resistor (about 10 kΩ to ground), or enable the Pi's internal pull-down in `/boot/firmware/config.txt` (`/boot/config.txt` on older releases):

```plaintext
gpio=17=pd
```

Use the same pin number as `buttonGpioPort`. Pressing the button opens a 2-minute window in which a phone can pair (see [Setting up a device](setup.md)).

## Power and clock

- The Pi Zero W runs from 5 V over micro-USB. An external battery pack is the expected setup.
- The Pi has no battery-backed clock, so it starts with the wrong time after every power cycle. The phone sets the clock every time it connects. Connect before an attempt and check the clock on the device screen.

## Assembly

1. Flash Raspberry Pi OS to the microSD card, and set up the device software ([Setting up a device](setup.md)).
2. Wire the button to the pin you chose for `buttonGpioPort`.
3. Wire the optocoupler stage for the release line (and the focus line, if you use it), then connect it to the camera's remote trigger port. Or connect the USB Ethernet adapter and the cable for a Z CAM.
4. Set the pins in `config.json`, and the camera address in `config.json` or from the app.
5. Power on, pair with the button, and run **Run test** from the app before every attempt.

## Safety

- Keep the Pi's pins away from any line that can carry more than 3.3 V.
- Test with the camera before you rely on it, and test again if you change cables or settings.
- A recording stopped by a power loss or reboot is not resumed. After a restart, any attempt whose wake-up time has passed is marked missed.

## ESP32-S3 version

The firmware in [`esp32/`](https://github.com/nprail/kairos/tree/main/esp32) runs the same protocol on an ESP32-S3 or an ESP32-C3, so the apps work with either device. The differences in hardware:

| | Raspberry Pi Zero W | ESP32-S3 |
| --- | --- | --- |
| Clock | None: the phone sets it on every connection | **DS3231 RTC** on I2C with a coin cell: the clock survives power loss |
| Z CAM link (optional, only for the Z CAM integration) | USB Ethernet adapter | **W5500 module** on SPI, with DHCP from the camera (and a static fallback) |
| Boot | 30 to 60 s | under 2 s |
| Power | about 1 W | about 100 to 150 mW (measure it) |

**The Ethernet module is optional.** Leave out the W5500 if you only use a remote trigger port (`generic`): everything else works without it. You only need it to control a Z CAM, and its pins in the table below can then stay unconnected. At boot the firmware looks for the module. If it does not answer, the device reports that it cannot drive a Z CAM, and the apps then only offer the remote trigger port.

Pins (constants in `esp32/src/platform/board.h`; verify against your board):

| Function | GPIO |
| --- | --- |
| W5500 SCLK / MOSI / MISO / CS (Z CAM only) | 12 / 11 / 13 / 10 |
| W5500 INT / RST (Z CAM only) | 14 / 9 |
| RTC SDA / SCL | 8 / 18 |
| Pairing button | 0 on the S3, 9 on the C3: the board's BOOT button (active low, to ground), so no extra button is needed |
| Camera release / focus | 5 / 6 |

The ESP32-C3 has far fewer usable pins and uses a separate set (no W5500 INT/RST, no focus line): see `esp32/README.md`. The pins are fixed in the firmware and are not part of the protocol, so no phone can change them. To change them, edit the constants in `board.h` and rebuild (see `esp32/README.md`). The same optocoupler stage as for the Pi (see [Generic camera](#generic-camera-remote-trigger-port)) isolates the camera, and a pull-down on each driver input keeps the camera lines quiet while the chip boots. Use a CR2032 in the RTC only if its charging circuit is disabled (many cheap DS3231 modules charge the cell). The ESP32 version runs from an external battery pack. Flash it with PlatformIO, and see `esp32/README.md` for the details and the things still to be confirmed on real hardware.

## Status of this document

This page was written from what the software does, so some hardware details are still missing:

- A bill of materials with part numbers.
- Wiring diagrams and a tested reference circuit for each supported camera.
- An enclosure design.
- A hardware license for any design files (schematics, PCB, enclosure). The MIT license in the repository covers the software.
