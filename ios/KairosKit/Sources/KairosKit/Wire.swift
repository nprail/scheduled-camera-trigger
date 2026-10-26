import Foundation

// The binary messages of protocol v2 (docs/ble-protocol.md, section 4). The app encodes requests and
// decodes responses and events. All integers are little-endian.

enum Op: UInt8 {
    case hello = 0x01
    case auth = 0x02
    case authPair = 0x03
    case timeGet = 0x04
    case timeSet = 0x05
    case configGet = 0x07
    case configSet = 0x08
    case jobsGet = 0x09
    case cameraWake = 0x10
    case cameraStart = 0x11
    case cameraStop = 0x12
    case cameraSleep = 0x13
    case testRun = 0x14
    case logsGet = 0x20
    case logsSubscribe = 0x21
}

struct ByteWriter {
    private(set) var bytes = [UInt8]()

    mutating func u8(_ v: UInt8) { bytes.append(v) }
    mutating func u16(_ v: UInt16) { bytes.append(UInt8(v & 0xFF)); bytes.append(UInt8(v >> 8)) }
    mutating func u32(_ v: UInt32) { u16(UInt16(v & 0xFFFF)); u16(UInt16(v >> 16)) }
    mutating func u64(_ v: UInt64) { u32(UInt32(v & 0xFFFF_FFFF)); u32(UInt32(v >> 32)) }
    mutating func data(_ d: Data) { bytes.append(contentsOf: d) }
    /// A `u8` length followed by the UTF-8 bytes. Callers check the limit.
    mutating func string(_ s: String) {
        let utf8 = Array(s.utf8)
        u8(UInt8(min(utf8.count, 255)))
        bytes.append(contentsOf: utf8.prefix(255))
    }
}

/// Reads past the end set `ok` to false and return zero, so a decoder can check once at the end.
struct ByteReader {
    let bytes: [UInt8]
    private(set) var position = 0
    private(set) var ok = true

    init(_ data: Data) { bytes = [UInt8](data) }

    var remaining: Int { bytes.count - position }

    private mutating func need(_ n: Int) -> Bool {
        if !ok || position + n > bytes.count { ok = false; return false }
        return true
    }

    mutating func u8() -> UInt8 {
        guard need(1) else { return 0 }
        defer { position += 1 }
        return bytes[position]
    }
    mutating func u16() -> UInt16 { let lo = UInt16(u8()); return lo | UInt16(u8()) << 8 }
    mutating func u32() -> UInt32 { let lo = UInt32(u16()); return lo | UInt32(u16()) << 16 }
    mutating func i32() -> Int32 { Int32(bitPattern: u32()) }
    mutating func u64() -> UInt64 { let lo = UInt64(u32()); return lo | UInt64(u32()) << 32 }
    mutating func data(_ n: Int) -> Data {
        guard need(n) else { return Data(count: n) }
        defer { position += n }
        return Data(bytes[position ..< position + n])
    }
    mutating func string() -> String {
        let n = Int(u8())
        guard need(n) else { return "" }
        defer { position += n }
        return String(decoding: bytes[position ..< position + n], as: UTF8.self)
    }
    /// The rest of the message.
    mutating func rest() -> Data {
        let d = Data(bytes[position...])
        position = bytes.count
        return d
    }
}

enum Wire {
    static let response: UInt8 = 0x80
    static let event: UInt8 = 0x81

    /// `[op][id][body]`
    static func request(_ op: Op, id: UInt8, body: Data = Data()) -> Data {
        var w = ByteWriter()
        w.u8(op.rawValue)
        w.u8(id)
        w.data(body)
        return Data(w.bytes)
    }

    enum Message: Equatable {
        case response(id: UInt8, status: UInt8, body: Data)
        case event(code: UInt8, body: Data)
    }

    static func parse(_ data: Data) -> Message? {
        let b = [UInt8](data)
        guard let first = b.first else { return nil }
        if first == event, b.count >= 2 { return .event(code: b[1], body: Data(b[2...])) }
        if first == response, b.count >= 3 { return .response(id: b[1], status: b[2], body: Data(b[3...])) }
        return nil
    }

    // MARK: Config

    static let configLayout: UInt8 = 1
    static let maxAttempts = 30
    static let maxNameBytes = 32
    static let noPin: UInt8 = 0xFF

    private static func invalid(_ field: String, _ message: String) -> DeviceError {
        DeviceError(code: "invalid_config", message: message, field: field)
    }

    static func encodeConfig(_ c: CameraConfig) throws -> Data {
        guard let type = CameraConfig.cameraTypes.firstIndex(of: c.cameraType) else {
            throw invalid("cameraType", "is not a known camera type")
        }
        let name = c.name ?? ""
        guard name.utf8.count <= maxNameBytes else { throw invalid("name", "must be at most \(maxNameBytes) bytes") }
        guard c.attempts.count <= maxAttempts else { throw invalid("attempts", "has too many attempts (at most \(maxAttempts))") }

        func seconds(_ v: Double) -> UInt16 { UInt16(max(0, min(v.rounded(), 65535))) }
        func pin(_ p: Int?, _ field: String) throws -> UInt8 {
            guard let p else { return noPin }
            guard (0 ..< 255).contains(p) else { throw invalid(field, "is not a GPIO number") }
            return UInt8(p)
        }

        var w = ByteWriter()
        w.u8(configLayout)
        w.u8(UInt8(type))
        w.u16(seconds(c.wakeUpTimeout))
        w.u16(seconds(c.startBefore))
        w.u16(seconds(c.endAfter))
        w.u8(try pin(c.buttonGpioPort, "buttonGpioPort"))
        w.u8(try pin(c.generic.releaseGpioPin, "generic.releaseGpioPin"))
        w.u8(try pin(c.generic.focusGpioPin, "generic.focusGpioPin"))
        for octet in try ipBytes(c.zcam.cameraIp) { w.u8(octet) }
        w.string(name)
        w.u8(UInt8(c.attempts.count))
        for (i, a) in c.attempts.enumerated() {
            guard a.name.utf8.count <= maxNameBytes else { throw invalid("attempts[\(i)].name", "must be at most \(maxNameBytes) bytes") }
            w.u32(a.time)
            w.string(a.name)
        }
        return Data(w.bytes)
    }

    /// `192.168.1.50` to its 4 bytes. Empty means none (all zeros).
    static func ipBytes(_ text: String?) throws -> [UInt8] {
        let trimmed = (text ?? "").trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return [0, 0, 0, 0] }
        let parts = trimmed.split(separator: ".", omittingEmptySubsequences: false)
        let octets = parts.compactMap { UInt8($0) }
        guard parts.count == 4, octets.count == 4 else {
            throw invalid("zcam.cameraIp", "must be an IPv4 address like 192.168.1.50")
        }
        return octets
    }

    static func decodeConfig(_ data: Data) throws -> CameraConfig {
        var r = ByteReader(data)
        guard r.u8() == configLayout else { throw DeviceError(code: "bad_request", message: "The device sent a config this app does not understand.") }
        let typeIndex = Int(r.u8())
        let wake = r.u16(), start = r.u16(), end = r.u16()
        func pin(_ v: UInt8) -> Int? { v == noPin ? nil : Int(v) }
        let button = pin(r.u8()), release = pin(r.u8()), focus = pin(r.u8())
        let ip = [r.u8(), r.u8(), r.u8(), r.u8()]
        let name = r.string()
        let count = Int(r.u8())
        var attempts: [CameraConfig.Attempt] = []
        for _ in 0 ..< count {
            let time = r.u32()
            attempts.append(CameraConfig.Attempt(name: r.string(), seconds: time))
        }
        guard r.ok, typeIndex < CameraConfig.cameraTypes.count, count <= maxAttempts else {
            throw DeviceError(code: "bad_request", message: "The device sent a config this app does not understand.")
        }
        return CameraConfig(
            name: name.isEmpty ? nil : name,
            cameraType: CameraConfig.cameraTypes[typeIndex],
            wakeUpTimeout: Double(wake), startBefore: Double(start), endAfter: Double(end),
            buttonGpioPort: button,
            generic: .init(releaseGpioPin: release, focusGpioPin: focus),
            zcam: .init(cameraIp: ip.allSatisfy { $0 == 0 } ? nil : ip.map(String.init).joined(separator: ".")),
            attempts: attempts
        )
    }

    // MARK: Errors

    static func deviceError(status: UInt8, body: Data) -> DeviceError {
        var r = ByteReader(body)
        switch status {
        case 1: return DeviceError(code: "bad_request", message: "The device could not understand the request.")
        case 2: return DeviceError(code: "unauthorized", message: "Not authorized.")
        case 3:
            let retry = r.ok && r.remaining >= 2 ? Int(r.u16()) : nil
            return DeviceError(code: "locked_out", message: "Too many failed attempts.", retryAfter: retry)
        case 4: return DeviceError(code: "not_supported", message: "The device does not support that.")
        case 5: return DeviceError(code: "busy", message: "The device is busy.")
        case 6:
            guard r.remaining >= 3 else { return DeviceError(code: "invalid_config", message: "The config is not valid.") }
            let field = Int(r.u8()), index = Int(r.u8()), reason = Int(r.u8())
            return DeviceError(code: "invalid_config", message: reasonText(reason), field: fieldName(field, index: index))
        case 7: return DeviceError(code: "too_large", message: "The request is too large for the device.")
        case 8: return DeviceError(code: "pairing_closed", message: "Pairing is closed.")
        default:
            let detail = r.remaining >= 1 ? r.string() : ""
            return DeviceError(code: "internal", message: detail.isEmpty ? "The device had an internal error." : detail)
        }
    }

    /// The `invalid_config` field ids, as the names the editor uses.
    static func fieldName(_ id: Int, index: Int) -> String {
        switch id {
        case 1: return "name"
        case 2: return "cameraType"
        case 3: return "wakeUpTimeout"
        case 4: return "startBefore"
        case 5: return "endAfter"
        case 6: return "buttonGpioPort"
        case 7: return "generic.releaseGpioPin"
        case 8: return "generic.focusGpioPin"
        case 9: return "zcam.cameraIp"
        case 10: return "attempts"
        case 11: return "attempts[\(index)].name"
        case 12: return "attempts[\(index)].time"
        default: return "config"
        }
    }

    static func reasonText(_ id: Int) -> String {
        switch id {
        case 1: return "is required"
        case 2: return "is out of range"
        case 3: return "is not valid"
        case 4: return "must be in the future"
        case 5: return "duplicates another attempt"
        case 6: return "is too soon: the camera wake-up time has already passed"
        case 7: return "has too many attempts (at most \(maxAttempts))"
        default: return "is not valid"
        }
    }

    // MARK: Responses

    static func hello(_ body: Data) throws -> HelloResponse {
        var r = ByteReader(body)
        let v = Int(r.u8()), flags = r.u8(), nonce = r.data(16), now = r.u64(), max = Int(r.u16())
        guard r.ok else { throw malformed }
        return HelloResponse(v: v, paired: flags & 1 != 0, nonce: nonce, nowMs: now, maxMessage: max)
    }

    static func timeGet(_ body: Data) throws -> UInt64 {
        var r = ByteReader(body)
        let ms = r.u64()
        guard r.ok else { throw malformed }
        return ms
    }

    static func jobStates(_ body: Data) throws -> [String] {
        var r = ByteReader(body)
        let n = Int(r.u8())
        var states: [String] = []
        let names = ["pending", "active", "complete", "missed"]
        for _ in 0 ..< n {
            let s = Int(r.u8())
            states.append(s < names.count ? names[s] : "pending")
        }
        guard r.ok else { throw malformed }
        return states
    }

    static func logLine(_ r: inout ByteReader) -> LogEntry {
        let seq = Int(r.u32()), ts = r.u32()
        let ns = r.string(), msg = r.string()
        return LogEntry(seq: seq, ts: ts, ns: ns, msg: msg)
    }

    static func logs(_ body: Data) throws -> (entries: [LogEntry], last: Int, more: Bool) {
        var r = ByteReader(body)
        let last = Int(r.u32()), more = r.u8() != 0, n = Int(r.u8())
        var entries: [LogEntry] = []
        for _ in 0 ..< n { entries.append(logLine(&r)) }
        guard r.ok else { throw malformed }
        return (entries, last, more)
    }

    static func decodeEvent(code: UInt8, body: Data) -> DeviceEvent? {
        var r = ByteReader(body)
        switch code {
        case 0x01:
            let attempt = Int(r.u8()), step = Int(r.u8()), at = r.u32()
            let steps = ["wake", "start", "stop", "complete", "missed"]
            guard r.ok, step < steps.count else { return nil }
            return .job(JobEvent(attempt: attempt, step: steps[step], at: Date(timeIntervalSince1970: TimeInterval(at))))
        case 0x02:
            let rev = Int(r.u32())
            return r.ok ? .config(revision: rev) : nil
        case 0x03:
            let step = Int(r.u8()), ok = r.u8() != 0
            let error = r.remaining > 0 ? r.string() : ""
            let steps = ["wake", "start", "stop", "sleep", "done"]
            guard r.ok, step < steps.count else { return nil }
            return .test(TestEvent(step: steps[step], ok: ok, error: error.isEmpty ? nil : error))
        case 0x04:
            let line = logLine(&r)
            return r.ok ? .log(line) : nil
        default:
            return nil
        }
    }

    private static let malformed = DeviceError(code: "internal", message: "Malformed response.")
}
