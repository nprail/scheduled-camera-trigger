# Kairos for iOS

A native SwiftUI app that controls a Kairos device over Bluetooth Low Energy. It speaks the protocol in [docs/ble-protocol.md](../docs/ble-protocol.md).

- Find and connect to nearby devices, and pair using the device's button
- Phone-as-clock: the device's clock is set from the phone on every connect (the Pi has no RTC)
- Live status with a countdown to the next wake, record or stop
- Edit launch attempts, timing and camera settings, saved atomically to the device
- Run a test, manually wake/start/stop/sleep the camera
- Live device log

Requires Xcode 16+ and iOS 17+. CoreBluetooth doesn't work in the Simulator, so run on a device.

## Layout

| Path | What |
| --- | --- |
| `KairosKit/` | Swift package: framing, auth, models, `DeviceClient`, CoreBluetooth. No UI. |
| `Kairos/` | The SwiftUI app (`AppModel` + views). |
| `Kairos.xcodeproj` | The Xcode project. |

## Build

```sh
open ios/Kairos.xcodeproj
```

Pick your team under *Signing & Capabilities*, choose your iPhone, and run.

## Test

```sh
cd ios/KairosKit
swift test
```

The framing, HMAC proofs and binary status decoding are checked against `docs/test-vectors.json`, which is generated from the Node reference implementation (`node test/vectors.js --write`). If the protocol changes, regenerate it, and both sides' tests will tell you what drifted.

## Pairing and security

1. Tap the device in the list. If it isn't paired with this phone, you'll see the pairing screen.
2. Press the button on the Kairos device, then tap **Pair**. The app generates a random 16-byte key, sends it to the device (only accepted during the 2-minute window), and stores it in the iOS Keychain.
3. On later connections the app proves it holds the key with an HMAC challenge/response, and checks that the device does too.

The first write to the device makes iOS show its standard Bluetooth pairing prompt (the link is encrypted). If the device was paired before and you can't connect, remove it under *Settings › Bluetooth* and try again.

**Forget pairing** (in the app) removes the key from the Keychain. Pressing the device's button re-opens pairing, including replacing a key whose phone is lost.
