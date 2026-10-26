# Hardware

Kairos runs on an **ESP32**: a small board with Bluetooth, a real-time clock module, and a few connections to your camera. This page describes what the firmware expects, with a typical way to wire it. The original Raspberry Pi Zero W version still works and is described [at the end](#legacy-raspberry-pi-zero-w). The software and protocol are MIT licensed. See [Status of this document](#status-of-this-document) for what is still missing.

## Overview

```plaintext
                 Bluetooth LE
   Phone  ◄───────────────────────►  ESP32-S3 or ESP32-C3
 (iOS/Android)                         │
                                       ├── I2C ◄── DS3231 real-time clock
                                       ├── GPIO ──► remote trigger port   (camera type: generic)
                                       ├── BOOT button (pairing)
                                       └── SPI ──► W5500 Ethernet ◄──► Z CAM   (camera type: zcam, optional)
```

Wi-Fi is never started: the device is controlled over Bluetooth, and a Z CAM is wired over Ethernet.

## Parts

| Part | Notes |
| --- | --- |
| ESP32-S3 or ESP32-C3 development board | For example an ESP32-S3-DevKitC-1 or an ESP32-C3-DevKitM-1. It provides Bluetooth Low Energy for the phone, and the BOOT button used for pairing. |
| DS3231 real-time clock module with a coin cell | Keeps the time across power loss. Recommended: without it the clock is only set while a phone is connected, so nothing runs after a reboot until you reconnect. |
| Battery pack | The battery is external to the device. The board runs from 5 V over USB, or 3.3 V on the 3V3 pin. |
| **Generic cameras:** one optocoupler (or small transistor) per line, plus a resistor for each | Switches the camera's shutter (and optionally focus) line. See [Generic camera](#generic-camera-remote-trigger-port). |
| **Generic cameras:** a cable or plug for your camera's remote trigger port | Varies by camera. Check your camera's manual for the pinout. |
| **Z CAM only (optional):** a W5500 SPI Ethernet module and a short Ethernet cable | Only needed for the Z CAM integration. See [Z CAM (Ethernet)](#z-cam-ethernet). |
| Enclosure | Not specified yet. |

There is no extra button to buy: pairing uses the board's BOOT button.

## Pins

Every pin is a constant in [`firmware/esp32/src/platform/board.h`](https://github.com/nprail/kairos/blob/main/firmware/esp32/src/platform/board.h), picked by the chip. They are fixed in the firmware and are not part of the Bluetooth protocol, so no phone can change them. To use other pins, edit the constants and rebuild (see [firmware/esp32/README.md](https://github.com/nprail/kairos/blob/main/firmware/esp32/README.md)). Verify the values against your board.

**ESP32-S3 (ESP32-S3-DevKitC-1)**

| Function | GPIO |
| --- | --- |
| Pairing button | 0, the BOOT button (active low, to ground) |
| Camera release / focus | 5 / 6 |
| RTC SDA / SCL (DS3231) | 8 / 18 |
| W5500 SCLK / MOSI / MISO / CS (Z CAM only) | 12 / 11 / 13 / 10 |
| W5500 INT / RST (Z CAM only) | 14 / 9 |

**ESP32-C3 (ESP32-C3-DevKitM-1)**

The C3 has only GPIO 0 to 21, and after USB, the console UART, the flash and the strapping pins very few are left, so the pin set is smaller: there is no focus line (wake pulses the release line) and the W5500 reset is not wired.

| Function | GPIO |
| --- | --- |
| Pairing button | 9, the BOOT button (active low, to ground) |
| Camera release | 10 |
| RTC SDA / SCL (DS3231) | 0 / 1 |
| W5500 SCLK / MOSI / MISO / CS (Z CAM only) | 4 / 6 / 5 / 7 |
| W5500 INT (Z CAM only) | 3 |

The pins are 3.3 V logic and cannot drive a camera's trigger line directly. Use the isolation described below.

## Generic camera (remote trigger port)

For cameras with a standard remote trigger port, the device closes a switch between the camera's shutter line and its common line, the same as pressing a wired remote. Wake is a pulse on the focus line (or the release line, if no focus pin is wired). Start and stop each pulse the release line once.

A typical circuit uses an optocoupler such as a PC817 for each line, so the board is electrically isolated from the camera:

```plaintext
 GPIO ──[ 330 Ω ]──► LED ┐   ┌ collector ──► camera shutter (or focus) line
                         │PC │
 GND  ─────────────── LED ┘   └ emitter   ──► camera common (ground)
```

Notes:

- This is a typical circuit, not a tested reference design. Check that your camera's shutter and focus lines are switched to a common line. Some cameras differ.
- Do not connect a camera line to a pin directly, and never connect a line that carries more than 3.3 V to a pin.
- Add a pull-down on each driver input so the camera lines stay quiet while the chip boots.
- The pulse is a fixed length (100 ms), to be tuned against real cameras. Check the pulse your camera needs.

## Z CAM (Ethernet)

**The Ethernet module is optional.** Leave out the W5500 if you only use a remote trigger port: everything else works without it, and its pins can stay unconnected. You only need it to control a Z CAM.

At boot the firmware looks for the module. If it does not answer, the device reports that it cannot drive a Z CAM, and the apps then only offer the remote trigger port.

With the module fitted, the device talks to the camera over its HTTP API on the wired link: it opens a session to wake the camera, starts and stops recording, and puts the camera into standby when an attempt completes. Set the camera type to Z CAM and enter the camera's IP address in the app.

- Connect the W5500 to the SPI pins above and run a short Ethernet cable to the camera.
- The device asks the camera for an address by DHCP, and falls back to a fixed address in the camera's network (`.250`, or `.249`) if no lease arrives. The camera's DHCP behavior is still to be confirmed on real hardware.
- Wi-Fi stays off, so the camera must be reachable over Ethernet.

## Pairing button

Pairing uses the board's BOOT button, which reads low when pressed. Press it, then tap **Pair** in the app within two minutes (see [Setting up a device](setup.md)). Do not press it while the board is resetting or powering up: holding BOOT at reset starts the flashing mode instead of the firmware.

Attaching a computer to the board's USB port can briefly toggle the same pin, which may open the pairing window. This does not matter for a deployed unit with no USB host.

## Power and clock

- The firmware boots in under 2 seconds and draws roughly 100 to 150 mW (to be measured). An external battery pack is the expected setup, with a regulator that has headroom for the Bluetooth and Ethernet peaks.
- The DS3231 keeps the time while the board is off. At boot the firmware sets its clock from the DS3231, and nothing is scheduled until the clock is valid. The phone also sets the clock every time it connects, and writes it to the DS3231.
- Use a CR2032 in the DS3231 only if its charging circuit is disabled (many cheap modules charge the cell).

## Assembly

1. Wire the DS3231 to the I2C pins, and the optocoupler stage for the release line (and the focus line, if you use it) to the camera's remote trigger port. Or fit the W5500 and the cable for a Z CAM.
2. Flash the firmware with PlatformIO ([Setting up a device](setup.md)).
3. Power on, press the BOOT button, and pair from the app.
4. Run **Run test** from the app before every attempt.

## Safety

- Keep the board's pins away from any line that can carry more than 3.3 V.
- Test with the camera before you rely on it, and test again if you change cables or settings.
- A recording stopped by a power loss or reboot is not resumed. After a restart, any attempt whose wake-up time has passed is marked missed.

## Legacy: Raspberry Pi Zero W

> [!NOTE]
> The Raspberry Pi version is **legacy**. It still works and speaks the same protocol, so the apps work with it, but new builds should use the ESP32. The Pi version needs a microSD card and an operating system, takes 30 to 60 seconds to boot, draws about 1 W and has no battery-backed clock.

The Pi program is in [`firmware/legacy-pi/trigger/`](https://github.com/nprail/kairos/tree/main/firmware/legacy-pi/trigger) (Node.js). It needs a Raspberry Pi Zero W, a microSD card for Raspberry Pi OS and `config.json`, a 5 V supply (a small battery pack works), a momentary push button, and the same optocoupler stage and camera cable as above. A Z CAM uses a micro-USB OTG Ethernet adapter and a short Ethernet cable, since the Pi Zero W has no Ethernet port.

**GPIO pins.** Pins are BCM GPIO numbers, set in `config.json` (see [Configuration](configuration.md)). The apps do not change them. The example config uses these:

| Function | Config option | Example | Direction |
| --- | --- | --- | --- |
| Pairing button | `buttonGpioPort` | 17 | Input, rising edge, 50 ms debounce |
| Shutter release | `generic.releaseGpioPin` | 25 | Output |
| Focus (optional) | `generic.focusGpioPin` | 23 | Output |

**Pairing button.** Wire the button between the pin set in `buttonGpioPort` and 3.3 V. The software listens for a rising edge, so the pin must read low until the button is pressed. Add a pull-down resistor (about 10 kΩ to ground), or enable the Pi's internal pull-down in `/boot/firmware/config.txt` (`/boot/config.txt` on older releases):

```plaintext
gpio=17=pd
```

**Z CAM.** Connect the USB Ethernet adapter to the Pi's data USB port, and give the Pi's Ethernet interface an address on the camera's network. The software does not configure this for you.

**Clock.** The Pi has no battery-backed clock, so it starts with the wrong time after every power cycle. The phone sets the clock every time it connects. Connect before an attempt and check the clock on the device screen.

**Assembly.** Flash Raspberry Pi OS to the microSD card, set up the device software ([Setting up a device](setup.md#legacy-raspberry-pi)), wire the button and the camera stage, set the pins in `config.json`, then power on, pair and run a test.

## Status of this document

This page was written from what the software does, so some hardware details are still missing:

- A bill of materials with part numbers.
- Wiring diagrams and a tested reference circuit for each supported camera.
- An enclosure design.
- A hardware license for any design files (schematics, PCB, enclosure). The MIT license in the repository covers the software.
- The ESP32 firmware has not yet been run with a camera, a DS3231 or a W5500 on real hardware.
