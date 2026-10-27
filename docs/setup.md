# Setting up a Kairos device

How to flash a Kairos device, pair a phone with it, and how the Bluetooth side of the device works. The recommended device is an **ESP32** (an ESP32-S3 or an ESP32-C3 board). The original Raspberry Pi version is **legacy**, and its setup is [at the end](#legacy-raspberry-pi). For the wiring, see [Hardware](hardware.md). For the settings, see [Configuration](configuration.md).

## Flashing an ESP32

You need a board wired as described in [Hardware](hardware.md), a USB cable, and [PlatformIO](https://platformio.org) Core 6.1.19 or newer. The firmware is in [`firmware/esp32/`](https://github.com/nprail/kairos/tree/main/firmware/esp32).

```sh
git clone git@github.com:nprail/kairos.git
cd kairos/firmware/esp32
pio run -e device -t upload      # ESP32-S3
pio run -e device-c3 -t upload   # ESP32-C3
pio device monitor               # serial log
```

The first build downloads the toolchain. If the upload cannot find a board that uses the chip's built-in USB, hold BOOT, tap RESET, release BOOT, and run it again.

**Flashing again keeps the pairing and the schedule.** They live in flash storage that an upload doesn't touch. Do not run `pio run -t erase` unless you want a factory reset. After a factory reset, a phone that still holds the old Bluetooth bond must forget the device once in its Bluetooth settings.

The pins are constants in `firmware/esp32/src/platform/board.h`. If your board is wired differently, edit them and rebuild (see [firmware/esp32/README.md](https://github.com/nprail/kairos/blob/main/firmware/esp32/README.md)). The schedule, timings, camera type and camera address are all set from the apps, and kept on the device.

## Bluetooth control

The device is configured and controlled over Bluetooth Low Energy: set the clock, edit the schedule, run a test, watch the log, and manually control the camera. The protocol is specified in [ble-protocol.md](ble-protocol.md).

- **Pairing:** press the board's BOOT button, then tap *Pair* in the app within 2 minutes. Only the paired phone can control the device afterwards. Pressing the button again opens pairing for a new phone. Do not press BOOT while the board is resetting or powering up.
- **Clock:** the DS3231 keeps the time while the device is off, and the phone sets the clock every time it connects. Without the DS3231 the clock is lost on power-off and nothing runs until a phone sets it.
- **Wi-Fi is always off:** the device never uses Wi-Fi. It is controlled over Bluetooth, and a Z CAM is wired over Ethernet.

## Using the apps

Both apps find and connect to devices, pair with the button, show live status with a countdown to the next event, edit the schedule and camera settings, run a test, manually wake, start, stop and sleep the camera, show the device log live, and [update the device's firmware](updating-firmware.md) over Bluetooth when you choose to.

- **iPhone:** [apps/ios/README.md](https://github.com/nprail/kairos/blob/main/apps/ios/README.md)
- **Android:** [apps/android/README.md](https://github.com/nprail/kairos/blob/main/apps/android/README.md)

Bluetooth doesn't work in the iOS Simulator or an Android emulator, so run the apps on a phone. Debug builds have a demo mode that shows every screen with sample data (see [AGENTS.md](https://github.com/nprail/kairos/blob/main/AGENTS.md)).

## Legacy: Raspberry Pi

> [!NOTE]
> The Raspberry Pi Zero W version is legacy. It still works and speaks the same protocol, so the apps work with it, but new devices should use the ESP32.

Clone the repository and install the dependencies on the Pi. Node.js 20 is what the tests run on, and the native modules (`onoff`, `hci-socket`) only build on the Pi.

```sh
git clone git@github.com:nprail/kairos.git
cd kairos/firmware/legacy-pi
npm install
cp config.example.json config.json   # edit this for your camera and pins
node trigger/index.js
```

By default it reads `./config.json`. Set `CAMERA_CONFIG_FILE` to use another path. The settings are described in [Configuration](configuration.md#legacy-configjson-raspberry-pi).

> [!NOTE]
> The device writes to this file: it adds a `deviceId` and `configRev` the first time it runs, saves changes made from the apps, and stores the phone pairing key. Keep the file writable and private.

- **Radio:** the Pi program talks HCI directly (via [`ble-host`](https://github.com/Emill/node-ble-host)), so stop `bluetoothd` (`sudo systemctl disable --now bluetooth`) and give Node access to the radio: `sudo setcap cap_net_admin=ep $(readlink -f $(which node))`. Setting the clock and blocking Wi-Fi need root (`date`, `timedatectl`, `rfkill`). The service runs `rfkill block wifi` every time it starts.
- **Clock:** the Pi has no battery-backed clock, so the phone sets the device's clock every time it connects.
- **Pairing:** press the button wired to `buttonGpioPort`, then tap *Pair* in the app within 2 minutes.
