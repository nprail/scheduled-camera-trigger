# Kairos documentation

Kairos is an open source hardware, time-based camera trigger. It wakes a camera and starts and stops recording at the exact moment you choose, set from your phone. Kairos runs on an ESP32; the original Raspberry Pi version is legacy. These pages cover setting up a device, wiring it to a camera, configuring it, and working on the project.

## Get started

- [Setting up a device](setup.md): flash an ESP32, pair a phone, and how the Bluetooth side works. The legacy Raspberry Pi setup is at the end of the page.
- [Hardware](hardware.md): the ESP32 parts, pins, and wiring for remote trigger ports and Z CAMs. The legacy Raspberry Pi hardware is at the end of the page.
- [Configuration](configuration.md): every setting, and the timeline of an attempt.

## Reference

- [Bluetooth protocol](ble-protocol.md): how the phone and the device talk to each other.
- [ESP32 firmware](esp32-port.md): the design of the ESP32 firmware, its status, and what it changes from the legacy Raspberry Pi version.
- [Development](development.md): repository layout, running the tests, and the test vectors.
