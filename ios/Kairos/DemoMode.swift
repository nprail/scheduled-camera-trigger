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
    ]

    func applyDemo(_ scene: String) {
        func iso(_ offset: TimeInterval) -> String { DeviceDate.string(Date().addingTimeInterval(offset)) }
        func status(flags: UInt8, phase: UInt8, next: Date, type: UInt8) -> Data {
            var d = Data([flags, phase, 7, 0, 0, 0])
            var t = UInt32(next.timeIntervalSince1970).littleEndian
            d.append(Data(bytes: &t, count: 4))
            d.append(contentsOf: [type, 2])
            return d
        }

        enterDemo()
        bluetooth = .poweredOn
        banner = nil
        let now = Date()
        func json<T: Decodable>(_ text: String) -> T {
            try! JSONDecoder().decode(T.self, from: Data(text.utf8))
        }
        let device: DeviceInfo = json(#"{"v":1,"id":"A1B2C3","name":"Pad Cam","cam":"zcam","fw":"1.2.0"}"#)

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
        case "ready", "generic", "recording", "banner":
            let recording = scene == "recording"
            info = device
            clock = ClockSync(offset: 0.4, applied: true)
            live = StatusValue(data: status(
                flags: recording ? 0b1011 : 0b1010, phase: recording ? 2 : 1,
                next: now.addingTimeInterval(recording ? 1800 : 5400), type: recording ? 3 : 2))
            let next = iso(recording ? 1800 : 5400)
            self.status = json(#"""
            {"phase":"\#(recording ? "recording" : "awake")","recording":\#(recording),"cameraReady":true,
             "configRev":7,
             "next":{"type":"\#(recording ? "stop" : "start")","at":"\#(next)","attempt":"Sunday 10:00"}}
            """#)
            config = json(#"""
            {"deviceId":"A1B2C3","configRev":7,"name":"Pad Cam","cameraType":"\#(scene == "generic" ? "generic" : "zcam")",
             "wakeUpTimeout":120,"startBefore":60,"endAfter":300,"logFile":"camera.log","buttonGpioPort":17,
             "generic":{"releaseGpioPin":23,"focusGpioPin":24},"zcam":{"cameraIp":"192.168.1.50"},
             "attempts":[{"name":"Sunday 10:00","time":"\#(iso(5400))"},{"name":"Sunday 12:00","time":"\#(iso(12600))"}]}
            """#)
            func job(_ id: String, _ name: String, _ at: TimeInterval, _ state: String) -> Job {
                json(#"""
                {"id":"\#(id)","name":"\#(name)","camera":"zcam","wakeUpTime":"\#(iso(at - 2100))",
                 "recordTime":"\#(iso(at - 60))","launchTime":"\#(iso(at))","stopTime":"\#(iso(at + 1800))","state":"\#(state)"}
                """#)
            }
            jobs = [job("1", "Sunday 10:00", 5400, recording ? "active" : "pending"),
                    job("2", "Sunday 12:00", 12600, "pending"),
                    job("0", "Sunday 08:00", -9000, "complete")]
            func log(_ seq: Int, _ at: TimeInterval, _ ns: String, _ msg: String) -> LogEntry {
                json(#"""
                {"seq":\#(seq),"ts":"\#(iso(at))","ns":"\#(ns)","msg":"\#(msg)"}
                """#)
            }
            logs = [log(1, -300, "boot", "Kairos started"),
                    log(2, -240, "ble", "Phone connected"),
                    log(3, -120, "camera", "Camera woke up"),
                    log(4, -60, "camera", "Failed to reach camera, retrying")]
            link = .ready
            if scene == "banner" { banner = "The connection to the device was lost." }
        default: // "scan"
            devices = [
                DiscoveredDevice(id: UUID(), name: "Pad Cam-A1B2C3", rssi: -52, lastSeen: now),
                DiscoveredDevice(id: UUID(), name: "Chapel-D4E5F6", rssi: -78, lastSeen: now),
            ]
        }
    }
}
#endif
