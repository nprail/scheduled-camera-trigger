#if DEBUG
import Foundation
import CoreBluetooth
import KairosKit

/// Debug-only fixtures so every screen can be shown without BLE hardware.
/// Launch with `-demo <scene>`; see `scenes`.
extension AppModel {
    static let scenes = [
        "scan", "scanning", "off", "unauthorized", "unsupported", "connecting",
        "pairing", "pairing-error", "ready", "generic", "recording", "banner",
        "firmware", "firmware-progress", "firmware-failed",
    ]

    func applyDemo(_ scene: String) {
        func status(flags: UInt8, phase: UInt8, next: Date, type: UInt8, attempt: UInt8) -> Data {
            var d = Data([flags, phase, 7, 0, 0, 0])
            var t = UInt32(next.timeIntervalSince1970).littleEndian
            d.append(Data(bytes: &t, count: 4))
            d.append(contentsOf: [type, 2, attempt])
            return d
        }

        enterDemo()
        bluetooth = .poweredOn
        banner = nil
        let now = Date()
        let device = DeviceInfo(
            v: Int(protocolVersion), id: "A1B2C3", name: "Pad Cam", cam: "zcam", fw: "1.2.0",
            caps: 1, chip: .esp32s3, slot: 0x1F0000)

        switch scene {
        case "off": bluetooth = .poweredOff
        case "unauthorized": bluetooth = .unauthorized
        case "unsupported": bluetooth = .unsupported
        case "scanning": devices = []
        case "connecting": link = .connecting("Pad Cam-A1B2C3")
        case "pairing", "pairing-error":
            info = device
            link = .needsPairing(reason: scene == "pairing"
                ? "This device hasn't been paired with a phone yet."
                : "Press the button on the device, then try again within 2 minutes.")
        case "ready", "generic", "recording", "banner", "firmware", "firmware-progress", "firmware-failed":
            let recording = scene == "recording"
            // the generic scene is a unit without the Ethernet module: no Z CAM option
            info = scene == "generic"
                ? DeviceInfo(v: Int(protocolVersion), id: "A1B2C3", name: "Pad Cam", cam: "generic", cams: ["generic"], fw: "1.2.0")
                : device
            clock = ClockSync(offset: 0.4, applied: true)
            live = StatusValue(data: status(
                flags: recording ? 0b1011 : 0b1010, phase: recording ? 2 : 1,
                next: now.addingTimeInterval(recording ? 1800 : 5400), type: recording ? 3 : 2, attempt: 0))
            let config = CameraConfig(
                name: "Pad Cam",
                cameraType: scene == "generic" ? "generic" : "zcam",
                wakeUpTimeout: 120, startBefore: 60, endAfter: 300,
                zcam: .init(cameraIp: "192.168.1.50"),
                attempts: [
                    .init(name: "Sunday 10:00", time: now.addingTimeInterval(5400)),
                    .init(name: "Sunday 12:00", time: now.addingTimeInterval(12600)),
                    .init(name: "Sunday 08:00", time: now.addingTimeInterval(-9000)),
                ])
            self.config = config
            let states = [recording ? "active" : "pending", "pending", "complete"]
            jobs = config.attempts.enumerated().map { i, a in
                Job(index: i, name: a.name, launchDate: a.date, state: states[i])
            }
            func log(_ seq: Int, _ at: TimeInterval, _ ns: String, _ msg: String) -> LogEntry {
                LogEntry(seq: seq, ts: UInt32(now.addingTimeInterval(at).timeIntervalSince1970), ns: ns, msg: msg)
            }
            logs = [log(1, -300, "boot", "Kairos started"),
                    log(2, -240, "ble", "Phone connected"),
                    log(3, -120, "camera", "Camera woke up"),
                    log(4, -60, "camera", "Failed to reach camera, retrying")]
            link = .ready
            if scene == "banner" { banner = "The connection to the device was lost." }
            if scene.hasPrefix("firmware") {
                // idle, with the next event far enough away that an update could start
                live = StatusValue(data: status(flags: 0b1010, phase: 0, next: now.addingTimeInterval(5400), type: 1, attempt: 0))
                let image = ReleaseManifest.Image(
                    file: "kairos-esp32s3-1.3.0-app.bin", size: 1_187_344,
                    sha256: String(repeating: "a", count: 64), sig: String(repeating: "b", count: 128))
                firmware.offer = FirmwareOffer(version: "1.3.0", notes: "Fixes the Z CAM retry and adds firmware updates.", image: image)
                if scene == "firmware-progress" {
                    firmware.prepared = PreparedFirmware(
                        title: "Version 1.3.0", version: "1.3.0", fileName: image.file, data: Data(count: 1_187_344),
                        signature: Data(count: 64), sha256: String(repeating: "a", count: 64))
                    firmware.stage = .sending(0.42)
                } else if scene == "firmware-failed" {
                    firmware.stage = .failed("The firmware's signature isn't valid for this device.")
                }
            }
        default: // "scan"
            devices = [
                DiscoveredDevice(id: UUID(), name: "Pad Cam-A1B2C3", rssi: -52, lastSeen: now),
                DiscoveredDevice(id: UUID(), name: "Chapel-D4E5F6", rssi: -78, lastSeen: now),
            ]
        }
    }
}
#endif
