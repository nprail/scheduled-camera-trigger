# Kairos documentation

Kairos is an open source hardware, time-based camera trigger. It wakes a camera and starts and stops recording at the exact moment you choose, set from your phone. These pages cover setting up a device, wiring it to a camera, configuring it, and working on the project.

## Get started

- [Setting up a device](SETUP.md): run Kairos on a Raspberry Pi, set up Bluetooth, and pair a phone.
- [Hardware](HARDWARE.md): parts, GPIO pins, and wiring for remote trigger ports and Z CAMs.
- [Configuration](CONFIGURATION.md): every `config.json` option, and the timeline of an attempt.

## Reference

- [Bluetooth protocol](ble-protocol.md): how the phone and the device talk to each other.
- [Development](DEVELOPMENT.md): repository layout, running the tests, and the test vectors.

## Proposals

- [ESP32 port](esp32-port.md): a design proposal for an ESP32 version of the device.
