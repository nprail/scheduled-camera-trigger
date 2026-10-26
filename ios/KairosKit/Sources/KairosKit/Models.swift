import Foundation

// MARK: - Characteristic values

/// Device Info characteristic (JSON).
public struct DeviceInfo: Codable, Equatable {
    public var v: Int
    public var id: String
    public var name: String
    public var cam: String
    public var fw: String
}

/// The fixed 12-byte Status characteristic.
public struct StatusValue: Equatable {
    public struct Flags: OptionSet, Equatable {
        public let rawValue: UInt8
        public init(rawValue: UInt8) { self.rawValue = rawValue }
        public static let recording = Flags(rawValue: 1 << 0)
        public static let cameraReady = Flags(rawValue: 1 << 1)
        public static let pairingOpen = Flags(rawValue: 1 << 2)
        public static let clockSet = Flags(rawValue: 1 << 3)
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

    public init?(data: Data) {
        let b = [UInt8](data)
        guard b.count >= 12 else { return nil }

        func u32(_ o: Int) -> UInt32 {
            UInt32(b[o]) | UInt32(b[o + 1]) << 8 | UInt32(b[o + 2]) << 16 | UInt32(b[o + 3]) << 24
        }

        flags = Flags(rawValue: b[0])
        phase = Phase(rawValue: b[1]) ?? .error
        configRev = u32(2)
        nextEventAt = u32(6)
        nextEventType = EventType(rawValue: b[10]) ?? .none
        attemptsRemaining = b[11]
    }

    public var nextEventDate: Date? {
        nextEventAt == 0 ? nil : Date(timeIntervalSince1970: TimeInterval(nextEventAt))
    }
}

// MARK: - Responses

public struct HelloResponse: Decodable, Equatable {
    public var v: Int
    public var nonce: String
    public var authRequired: Bool
    public var paired: Bool
    public var now: String
    public var maxMessage: Int
}

public struct AuthResponse: Decodable { public var proof: String }

public struct TimeResponse: Decodable {
    public var now: String
    public var offsetMs: Int?
}

public struct StatusInfo: Codable, Equatable {
    public struct Next: Codable, Equatable {
        public var type: String
        public var at: String
        public var attempt: String

        public var date: Date? { DeviceDate.parse(at) }
    }

    public var phase: String
    public var recording: Bool
    public var cameraReady: Bool
    public var configRev: Int
    public var next: Next?
}

public struct Job: Codable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var camera: String
    public var wakeUpTime: String
    public var recordTime: String
    public var launchTime: String
    public var stopTime: String
    public var state: String

    public var launchDate: Date? { DeviceDate.parse(launchTime) }
}

struct JobsPage: Decodable {
    var jobs: [Job]
    var total: Int
    var more: Bool
}

public struct LogEntry: Codable, Equatable, Identifiable {
    public var seq: Int
    public var ts: String
    public var ns: String
    public var msg: String

    public var id: Int { seq }
    public var date: Date? { DeviceDate.parse(ts) }
}

struct LogsPage: Decodable {
    var logs: [LogEntry]
    var last: Int
    var more: Bool
}

struct ConfigEnvelope: Codable { var config: CameraConfig }
struct ConfigSetResponse: Decodable { var configRev: Int }
struct CameraResponse: Decodable { var recording: Bool }
struct StartedResponse: Decodable { var started: Bool }
struct Empty: Codable {}

// MARK: - Config

public struct CameraConfig: Codable, Equatable {
    public struct Generic: Codable, Equatable {
        public var releaseGpioPin: Int?
        public var focusGpioPin: Int?
        public init(releaseGpioPin: Int? = nil, focusGpioPin: Int? = nil) {
            self.releaseGpioPin = releaseGpioPin
            self.focusGpioPin = focusGpioPin
        }
    }

    public struct Zcam: Codable, Equatable {
        public var cameraIp: String?
        public init(cameraIp: String? = nil) { self.cameraIp = cameraIp }
    }

    public struct Attempt: Codable, Equatable, Identifiable {
        /// Local identity for SwiftUI; not sent to the device.
        public var id = UUID()
        public var name: String
        public var time: String

        enum CodingKeys: String, CodingKey { case name, time }

        public init(name: String, time: Date) {
            self.name = name
            self.time = DeviceDate.string(time)
        }

        public var date: Date {
            get { DeviceDate.parse(time) ?? Date() }
            set { time = DeviceDate.string(newValue) }
        }

        // Identity is local; two attempts are equal if they schedule the same thing.
        public static func == (lhs: Attempt, rhs: Attempt) -> Bool {
            lhs.name == rhs.name && lhs.time == rhs.time
        }
    }

    // Device-owned (ignored by the device on write).
    public var deviceId: String?
    public var configRev: Int?

    public var name: String?
    public var cameraType: String
    /// All durations are seconds.
    public var wakeUpTimeout: Double
    public var startBefore: Double
    public var endAfter: Double
    public var logFile: String
    public var buttonGpioPort: Int?
    public var generic: Generic
    public var zcam: Zcam
    public var attempts: [Attempt]

    public static let cameraTypes = ["generic", "zcam"]
}

// MARK: - Events

public struct JobEvent: Decodable, Equatable {
    public var name: String
    public var step: String
    public var at: String
}

public struct TestEvent: Decodable, Equatable {
    public var step: String
    public var ok: Bool?
    public var error: String?
}

struct ConfigEvent: Decodable { var configRev: Int }

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

struct ErrorBody: Decodable {
    var code: String
    var msg: String
    var field: String?
    var retryAfter: Int?

    var asError: DeviceError {
        DeviceError(code: code, message: msg, field: field, retryAfter: retryAfter)
    }
}
