# Kairos for Android

A native Jetpack Compose app that controls a Kairos device over Bluetooth Low Energy. It speaks the protocol in [docs/ble-protocol.md](../../docs/ble-protocol.md). There is also an [iPhone app](../ios/README.md).

- Find and connect to nearby devices, and pair using the device's button
- Phone-as-clock: the device's clock is set from the phone on every connect (the Pi has no RTC)
- Live status with a countdown to the next wake, record or stop
- Edit launch attempts, timing and camera settings, saved atomically to the device
- Run a test, manually wake/start/stop/sleep the camera
- Live device log
- Material 3 design with the Kairos brand colors and fonts, following the system light and dark theme (Material You dynamic color is off, so the brand stays fixed)

Requires Android 8.0 (API 26) or later, and Bluetooth Low Energy. Building needs JDK 17 or newer and the Android SDK (platform 36).

## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/com/noahprail/kairos/protocol/` | Framing, auth, models and `DeviceClient`. No Android UI. |
| `app/src/main/java/com/noahprail/kairos/ble/` | The BLE scanner and connection, and pairing key storage. |
| `app/src/main/java/com/noahprail/kairos/AppModel.kt` | The `ViewModel` that owns the connection and everything on screen. |
| `app/src/main/java/com/noahprail/kairos/ui/` | The Compose screens and theme. |

## Build

Open `apps/android/` in Android Studio and run the `app` configuration on your phone, or from a terminal:

```sh
cd apps/android
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install -r` or by copying it to your phone.

The first time you open the app it asks for the Nearby devices permission (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`, or location on Android 11 and earlier), which it needs to find the device.

## Test

```sh
cd apps/android
./gradlew :app:testDebugUnitTest
```

The framing, HMAC proofs and binary status decoding are checked against `protocol/test-vectors.json`, which is generated from the Node reference implementation (`cd firmware/legacy-pi && node test/vectors.js --write`). If the protocol changes, regenerate it, and both sides' tests will tell you what drifted.

## Pairing and security

1. Tap the device in the list. If it isn't paired with this phone, you'll see the pairing screen.
2. Press the button on the Kairos device, then tap **Pair**. The app generates a random 16-byte key, sends it to the device (only accepted during the 2-minute window), and stores it encrypted with a key held in the Android Keystore. The key isn't included in backups.
3. On later connections the app proves it holds the key with an HMAC challenge/response, and checks that the device does too.

If the device was paired before and you can't connect, remove it under *Settings › Connected devices* and try again.

**Forget pairing** (in the app) deletes the stored key. Pressing the device's button re-opens pairing, including replacing a key whose phone is lost.

## Demo mode

Debug builds can show every screen with sample data and no Bluetooth: on the scan screen, tap the "Kairos" title 7 times. See [AGENTS.md](../../AGENTS.md) for the scenes and the `adb` shortcuts.
