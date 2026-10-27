# Updating the firmware

An ESP32 Kairos device can be updated from the phone, over Bluetooth, with no cable. The apps (iPhone and Android) find the right firmware for the device's chip, send it, and the device restarts on it. The pairing key and the schedule are kept.

**An update is always your choice.** The device never updates itself, and the apps never install anything without you choosing to. The apps may *look* for a newer release when they connect, but that only shows a small "Update available" tag on the Firmware row.

> The first firmware with this feature has to be put on the device with a USB cable (see [Setting up a device](/setup/)). Older firmware cannot be updated over Bluetooth, and the apps hide the Firmware row for it. The legacy Raspberry Pi version cannot be updated this way either.

## Update from the app

1. Connect to the device and open **Firmware** (under Device). It shows the version and the chip.
2. The app may already show **Version X is available**. Tap **Check for updates** to look now. This works only while the phone has internet.
3. Tap **Download**. The file is saved on the phone, so you can install later with no signal.
4. Tap **Install…** and confirm. Keep the app open: the transfer takes a minute or two. The device then checks the file, restarts, and the app reconnects.

The app tells you when it is done ("Updated to 1.3.0") or when the device went back to its old firmware ("The update was rolled back").

### When an update can start

The device only takes an update when it is idle and no event is scheduled within the next **15 minutes**. It stops a transfer that is under way if an event gets within 2 minutes, or if no data arrives for 2 minutes. Either way it keeps running the firmware it had.

If the connection drops while the file is being sent, the device keeps what it received for 2 minutes. Connect again and install the same firmware to carry on from where it stopped.

### Checking on connect

The app checks GitHub for a newer release when it connects, at most once every 10 minutes. It sends nothing about your device: it just downloads a small file that lists the latest release. If the phone is offline, nothing happens and nothing is shown. Turn it off under **Firmware → Advanced → Check for updates when connecting**.

### Older versions and pre-releases

**Firmware → Advanced** has **Include pre-releases** and **Choose a version…**, which lists earlier releases. Installing an older version asks you to confirm.

## Update from a file

Under **Firmware → From a file**, choose the firmware: the file ending in **`-app.bin`**. If it has a matching `.sig` file, choose both together.

> Use the `-app.bin` file, never the `-factory.bin`. The factory image is for a blank board over USB and erases the pairing and the schedule. The apps and the device both refuse it.

A normal device only accepts firmware **signed by Kairos**. A file you built yourself is not, so a normal device refuses it ("That firmware isn't signed…"). See developer builds below.

## Why a device refuses an update

| Message | What it means |
| --- | --- |
| The device can't take an update now | It is busy (recording, awake, running a test) or an event is within 15 minutes. |
| That firmware isn't signed… | The device only takes firmware signed by Kairos. |
| The firmware's signature isn't valid for this device | The file was changed, or it was signed with a different key. |
| That firmware is for a different chip | An ESP32-S3 file for an ESP32-C3 device, or the other way round. |
| That isn't a Kairos application update | You chose the `-factory.bin`, or a file that isn't firmware. |
| That firmware is too large for this device | It does not fit the update slot. |
| The update was rolled back | The new firmware restarted before it proved itself, so the device went back to the old one. Its log (Device → Logs) may say why. |
| Version X needs a newer version of this app | The release uses a newer protocol. Update the app first. |
| Version X can't be installed over Bluetooth on this device | The device is too old for a direct update. Flash it over USB. |

## How it stays safe

- **Two slots.** The new firmware is written to the idle slot. The running one is untouched until the new one has been checked.
- **Checked three ways.** The phone and the device both check the file's SHA-256 and that it is an application image for this chip. The device also checks the signature before it accepts a single byte.
- **Proves itself.** After the restart, the new firmware keeps its slot only once it has run for 30 seconds **and a phone has connected and authenticated with it** (the app does this by itself right after the update). If it resets before then, including a power cycle, the bootloader goes back to the old firmware. An update whose Bluetooth does not work can therefore never replace a working one.
- **Only the paired phone.** The update operations need the pairing key.

## For developers

### Developer builds

The `device-dev` (ESP32-S3) and `device-c3-dev` (ESP32-C3) PlatformIO environments build firmware that **accepts unsigned updates**. They say so in the log at every boot, and the release workflow never builds them.

```sh
cd firmware/esp32
pio run -e device-dev -t upload    # flash it once over USB
pio run -e device-dev              # later: build, then send .pio/build/device-dev/firmware.bin from the app
```

In the app, choose `firmware.bin` under **Firmware → From a file**. It shows "unsigned", and the device accepts it.

### Your own signing key (forks)

A fork that releases its own firmware should use its own key:

```sh
python3 firmware/esp32/tools/signing.py keygen ~/.kairos-signing/my-key.pem
python3 firmware/esp32/tools/signing.py header firmware/esp32/src/platform/signing_keys.h ~/.kairos-signing/my-key.pem
```

Then rebuild and flash over USB once. From then on the device accepts images signed with that key (`signing.py sign`). A signed image from the project's key will no longer be accepted.

### The release key

Releases are signed by the release workflow with a private key kept as the repository secret **`KAIROS_SIGNING_KEY`**. The matching public key is in [`signing_keys.h`](https://github.com/nprail/kairos/blob/main/firmware/esp32/src/platform/signing_keys.h).

To set the secret from the key file (the file is never committed):

```sh
gh secret set KAIROS_SIGNING_KEY < ~/.kairos-signing/release-key.pem
```

**Back the key up somewhere safe and offline.** If it is lost, no device will accept a new release over Bluetooth, and every unit needs a USB flash.

To rotate the key without that: add the new public key as a second entry in `signing_keys.h` (`signing.py header OUT old-key new-key`), ship a release signed with the old key, and once units have it, sign with the new key. Devices accept any key in the list.

### What a release contains

Pushing a tag such as `v1.3.0` runs the release workflow. It builds both chips, signs each `-app.bin`, and attaches to the GitHub Release:

| File | Purpose |
| --- | --- |
| `kairos-<chip>-<version>-app.bin` | The firmware the apps send over Bluetooth. |
| `kairos-<chip>-<version>-app.bin.sig` | Its signature (64 bytes). |
| `kairos-<chip>-<version>-factory.bin` | The whole flash image, for a blank board over USB. |
| `manifest.json` | What the apps read: the version, the protocol, `minFrom`, and each image's size, SHA-256 and signature. |
| `SHA256SUMS` | Checksums of the `.bin` files. |

A tag with a hyphen (`v1.3.0-beta.1`) is a pre-release, offered only from Advanced. The oldest firmware allowed to update directly to a release is in [`firmware/esp32/MIN_FROM`](https://github.com/nprail/kairos/blob/main/firmware/esp32/MIN_FROM): raise it for a release that needs a USB flash first (for example one that changes the partition table). The workflow stops if an image is bigger than the update slot.

The protocol for all of this is in the [Bluetooth protocol](/ble-protocol/) (`fw.*`). The design notes are in [the update plan](/ota-plan/).
