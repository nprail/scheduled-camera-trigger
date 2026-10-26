# Setting up a Kairos device

How to run Kairos on a Raspberry Pi Zero W, and how the Bluetooth side of the device works. For the settings in `config.json`, see [CONFIGURATION.md](CONFIGURATION.md).

## Running the device

Clone the repository and install the dependencies. Node.js 20 is what the tests run on, and the native modules (`onoff`, `hci-socket`) only build on the Pi.

```sh
git clone git@github.com:nprail/scheduled-camera-trigger.git
cd scheduled-camera-trigger
npm install
```

Copy the example config and edit it:

```sh
cp config.example.json config.json
```

Then start the trigger:

```sh
node trigger/index.js
```

By default it reads `./config.json`. Set `CAMERA_CONFIG_FILE` to use another path.

> [!NOTE]
> The device writes to this file: it adds a `deviceId` and `configRev` the first time it runs, saves changes made from the apps, and stores the phone pairing key. Keep the file writable and private.

## Bluetooth control

The device is configured and controlled over Bluetooth Low Energy: set the clock, edit the schedule, run a test, watch the log, and manually control the camera. The protocol is specified in [ble-protocol.md](ble-protocol.md).

- **Device:** `trigger/ble/`. It talks HCI directly (via [`ble-host`](https://github.com/Emill/node-ble-host)), so stop `bluetoothd` (`sudo systemctl disable --now bluetooth`) and give Node access to the radio: `sudo setcap cap_net_admin=ep $(readlink -f $(which node))`. Setting the clock and blocking Wi-Fi need root (`date`, `timedatectl`, `rfkill`).
- **Clock:** the Pi has no battery-backed clock, so the phone sets the device's clock every time it connects.
- **Wi-Fi is always off:** the device never uses Wi-Fi. It is controlled over Bluetooth, and a Z CAM is wired over Ethernet, so the service runs `rfkill block wifi` every time it starts.
- **Pairing:** press the button wired to `buttonGpioPort`, then tap *Pair* in the app within 2 minutes. Only the paired phone can control the device afterwards. Pressing the button again opens pairing for a new phone.

## Using the apps

Both apps find and connect to devices, pair with the button, show live status with a countdown to the next event, edit the schedule and camera settings, run a test, manually wake, start, stop and sleep the camera, and show the device log live.

- **iPhone:** [ios/README.md](https://github.com/nprail/scheduled-camera-trigger/blob/main/ios/README.md)
- **Android:** [android/README.md](https://github.com/nprail/scheduled-camera-trigger/blob/main/android/README.md)

Bluetooth doesn't work in the iOS Simulator or an Android emulator, so run the apps on a phone. Debug builds have a demo mode that shows every screen with sample data (see [AGENTS.md](https://github.com/nprail/scheduled-camera-trigger/blob/main/AGENTS.md)).
