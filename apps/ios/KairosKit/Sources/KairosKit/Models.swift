import Foundation

// MARK: - Characteristic values

/// Device Info characteristic (docs/ble-protocol.md, Device Info).
public struct DeviceInfo: Equatable {
    public var v: Int
    /// The 6 hex digits of the device id, upper case.
    public var id: String
    public var name: String
    public var cam: String
    /// The camera types this device can drive. A device without the Ethernet module cannot drive a Z CAM.
    public var cams: [String]
    public var fw: String
    /// Capability bits; bit0 is firmware update over Bluetooth. 0 when the device does not send them.
    public var caps: Int
    /// The chip, when the device says (an update needs it to pick the right image).
    public var chip: Chip?
    /// The largest firmware image the device accepts, in bytes. 0 when not updatable.
    public var slot: Int

    public init(
        v: Int, id: String, name: String, cam: String, cams: [String] = CameraConfig.cameraTypes, fw: String,
        caps: Int = 0, chip: Chip? = nil, slot: Int = 0
    ) {
        self.v = v
        self.id = id
        self.name = name
        self.cam = cam
        self.cams = cams
        self.fw = fw
        self.caps = caps
        self.chip = chip
        self.slot = slot
    }

    /// The device can be updated over Bluetooth and the app knows which image fits it.
    public var canUpdate: Bool { caps & 1 != 0 && chip != nil && slot > 0 }

    public init?(data: Data) {
        var r = ByteReader(data)
        let v = Int(r.u8())
        let id = r.data(3).map { String(format: "%02X", $0) }.joined()
        let cam = Int(r.u8())
        let mask = Int(r.u8())
        let fw = r.string()
        let name = r.string()
        guard r.ok else { return nil }
        // optional trailing fields, missing on devices that cannot be updated
        let caps = r.remaining >= 1 ? Int(r.u8()) : 0
        let chip = r.remaining >= 1 ? Chip(deviceValue: Int(r.u8())) : nil
        let slot = r.remaining >= 4 ? Int(r.u32()) : 0
        let types = CameraConfig.cameraTypes
        self.init(
            v: v, id: id, name: name,
            cam: cam < types.count ? types[cam] : "generic",
            cams: types.enumerated().filter { mask & (1 << $0.offset) != 0 }.map(\.element),
            fw: fw, caps: caps, chip: chip, slot: slot)
    }
}

/// The fixed 13-byte Status characteristic.
public struct StatusValue: Equatable {
    public struct Flags: OptionSet, Equatable {
        public let rawValue: UInt8
        public init(rawValue: UInt8) { self.rawValue = rawValue }
        public static let recording = Flags(rawValue: 1 << 0)
        public static let cameraReady = Flags(rawValue: 1 << 1)
        public static let pairingOpen = Flags(rawValue: 1 << 2)
        public static let clockValid = Flags(rawValue: 1 << 3)
    }

    public enum Phase: UInt8 {
        case idle = 0, awake, recording, test, error
    }

    public enum EventType: UInt8 {
        case none = 0, wake, start, stop
    }

    public var flags: Flags
    public var phase: Phase
    public var configRev: UInt32
    /// Unix seconds, 0 if none.
    public var nextEventAt: UInt32
    public var nextEventType: EventType
    public var attemptsRemaining: UInt8
    /// Index (in the config's attempts) of the attempt that owns the next event.
    public var nextAttempt: Int?

    public static let length = 13

    public init?(data: Data) {
        var r = ByteReader(data)
        let flags = r.u8(), phase = r.u8(), rev = r.u32(), at = r.u32(), type = r.u8(), remaining = r.u8(), next = r.u8()
        guard r.ok else { return nil }
        self.flags = Flags(rawValue: flags)
        self.phase = Phase(rawValue: phase) ?? .error
        configRev = rev
        nextEventAt = at
        nextEventType = EventType(rawValue: type) ?? .none
        attemptsRemaining = remaining
        nextAttempt = next == 0xFF ? nil : Int(next)
    }

    public var nextEventDate: Date? {
        nextEventAt == 0 ? nil : Date(timeIntervalSince1970: TimeInterval(nextEventAt))
    }
}

// MARK: - Responses

public struct HelloResponse: Equatable {
    public var v: Int
    public var paired: Bool
    public var nonce: Data
    public var nowMs: UInt64
    public var maxMessage: Int

    public var now: Date { Date(timeIntervalSince1970: TimeInterval(nowMs) / 1000) }
}

/// One scheduled attempt and its state. The times come from the config; the device only owns the state.
public struct Job: Equatable, Identifiable {
    public var index: Int
    public var name: String
    public var launchDate: Date
    /// "pending", "active", "complete" or "missed".
    public var state: String

    public init(index: Int, name: String, launchDate: Date, state: String) {
        self.index = index
        self.name = name
        self.launchDate = launchDate
        self.state = state
    }

    public var id: Int { index }
}

public struct LogEntry: Equatable, Identifiable {
    public var seq: Int
    /// Unix seconds.
    public var ts: UInt32
    public var ns: String
    public var msg: String

    public init(seq: Int, ts: UInt32, ns: String, msg: String) {
        self.seq = seq
        self.ts = ts
        self.ns = ns
        self.msg = msg
    }

    public var id: Int { seq }
    public var date: Date? { Date(timeIntervalSince1970: TimeInterval(ts)) }
}

// MARK: - Config

public struct CameraConfig: Equatable {
    public struct Zcam: Equatable {
        public var cameraIp: String?
        public init(cameraIp: String? = nil) { self.cameraIp = cameraIp }
    }

    public struct Attempt: Equatable, Identifiable {
        /// Local identity for SwiftUI; not sent to the device.
        public var id = UUID()
        public var name: String
        /// T-0 in Unix seconds.
        public var time: UInt32

        public init(name: String, time: Date) {
            self.name = name
            self.time = UInt32(max(0, min(time.timeIntervalSince1970, Double(UInt32.max))))
        }

        public init(name: String, seconds: UInt32) {
            self.name = name
            time = seconds
        }

        public var date: Date {
            get { Date(timeIntervalSince1970: TimeInterval(time)) }
            set { time = UInt32(max(0, min(newValue.timeIntervalSince1970, Double(UInt32.max)))) }
        }

        // Identity is local; two attempts are equal if they schedule the same thing.
        public static func == (lhs: Attempt, rhs: Attempt) -> Bool {
            lhs.name == rhs.name && lhs.time == rhs.time
        }
    }

    public var name: String?
    public var cameraType: String
    /// All durations are seconds.
    public var wakeUpTimeout: Double
    public var startBefore: Double
    public var endAfter: Double
    public var zcam: Zcam
    public var attempts: [Attempt]

    public static let cameraTypes = ["generic", "zcam"]

    public init(
        name: String? = nil,
        cameraType: String = "generic",
        wakeUpTimeout: Double = 5,
        startBefore: Double = 20,
        endAfter: Double = 30,
        zcam: Zcam = Zcam(),
        attempts: [Attempt] = []
    ) {
        self.name = name
        self.cameraType = cameraType
        self.wakeUpTimeout = wakeUpTimeout
        self.startBefore = startBefore
        self.endAfter = endAfter
        self.zcam = zcam
        self.attempts = attempts
    }
}

// MARK: - Events

public struct JobEvent: Equatable {
    /// Index in the config's attempts.
    public var attempt: Int
    public var step: String
    public var at: Date
}

public struct TestEvent: Equatable {
    public var step: String
    public var ok: Bool
    public var error: String?
}

/// Progress of a firmware transfer, from the device (docs/ble-protocol.md, fw.*).
public struct FirmwareEvent: Equatable {
    public enum State: Int { case receiving = 0, verifying, ready, failed }
    public var state: State
    /// The next byte the device expects.
    public var offset: Int
    /// A `bad_image` reason when failed.
    public var reason: Int

    public init(state: State, offset: Int, reason: Int = 0) {
        self.state = state
        self.offset = offset
        self.reason = reason
    }
}

public enum DeviceEvent: Equatable {
    case job(JobEvent)
    case config(revision: Int)
    case test(TestEvent)
    case log(LogEntry)
}

// MARK: - Errors

public struct DeviceError: Error, LocalizedError, Equatable {
    public var code: String
    public var message: String
    public var field: String?
    public var retryAfter: Int?

    public init(code: String, message: String, field: String? = nil, retryAfter: Int? = nil) {
        self.code = code
        self.message = message
        self.field = field
        self.retryAfter = retryAfter
    }

    public static let disconnected = DeviceError(code: "disconnected", message: "The device disconnected.")
    public static let timeout = DeviceError(code: "timeout", message: "The device did not respond in time.")

    public var errorDescription: String? {
        switch code {
        case "busy": return "The device is busy right now. Try again after the current recording or test."
        case "unauthorized": return "The device rejected the pairing key. Re-pair using the device's button."
        case "locked_out":
            if let s = retryAfter { return "Too many failed attempts. Try again in \(s) s." }
            return "Too many failed attempts."
        case "pairing_closed": return "Press the button on the device, then try again within 2 minutes."
        case "invalid_config":
            if let f = field { return "\(f): \(message)" }
            return message
        default: return message
        }
    }
}
