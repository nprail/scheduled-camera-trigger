import Foundation

// Firmware images and release manifests, on the phone side (docs/ble-protocol.md, section 7 and fw.*).
// Everything here is checked against the same vectors as the Node reference (protocol/test-vectors.json).

/// The chips a Kairos device can run on.
public enum Chip: String, CaseIterable, Equatable {
    case esp32s3, esp32c3

    /// Device Info `chip`: 1 ESP32-S3, 2 ESP32-C3.
    init?(deviceValue: Int) {
        switch deviceValue {
        case 1: self = .esp32s3
        case 2: self = .esp32c3
        default: return nil
        }
    }

    /// The chip id in an image header.
    var headerId: UInt16 { self == .esp32s3 ? 9 : 5 }

    public var title: String { self == .esp32s3 ? "ESP32-S3" : "ESP32-C3" }
}

/// What a firmware file turned out to be.
public enum FirmwareCheck: Equatable {
    /// An application image for this chip. `version` is its tag, or nil if it has none.
    case ok(version: String?)
    case notImage
    case wrongChip
    /// An image, but not an application image (for example the factory image).
    case notApp

    public var message: String? {
        switch self {
        case .ok: return nil
        case .notImage: return "That file isn't Kairos firmware."
        case .wrongChip: return "That firmware is for a different chip."
        case .notApp: return "That's a factory image. Use the file ending in -app.bin to update a device."
        }
    }
}

public enum FirmwareImage {
    private static let tagMagic = Data("KAIROSFW".utf8)
    private static let tagLength = 8 + 1 + 1 + 32

    /// Check a firmware file for `chip`, the same way the reference does.
    public static func check(_ data: Data, for chip: Chip) -> FirmwareCheck {
        let b = [UInt8](data)
        guard b.count >= 64, b[0] == 0xE9 else { return .notImage }
        let headerChip = UInt16(b[12]) | UInt16(b[13]) << 8
        guard headerChip == chip.headerId else { return .wrongChip }
        let appDesc = UInt32(b[32]) | UInt32(b[33]) << 8 | UInt32(b[34]) << 16 | UInt32(b[35]) << 24
        guard appDesc == 0xABCD_5432 else { return .notApp }

        var from = data.startIndex
        while let r = data.range(of: tagMagic, in: from ..< data.endIndex) {
            let at = r.lowerBound - data.startIndex
            guard at + tagLength <= b.count else { break }
            if b[at + 8] == 1 {
                if let tagChip = Chip(deviceValue: Int(b[at + 9])), tagChip != chip { return .wrongChip }
                let raw = b[(at + 10) ..< (at + tagLength)]
                let text = raw.prefix { $0 != 0 }
                let version = String(decoding: text, as: UTF8.self)
                return .ok(version: version.isEmpty ? nil : version)
            }
            from = r.lowerBound + 1
        }
        return .ok(version: nil)
    }

    /// A signature file: the 64 raw bytes, or the same as 128 hex characters.
    public static func signature(from data: Data) -> Data? {
        if data.count == 64 { return data }
        let text = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count == 128, let bytes = Data(hexString: text) else { return nil }
        return bytes
    }
}

extension Data {
    init?(hexString: String) {
        guard hexString.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(hexString.count / 2)
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index ..< next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }

    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}

// MARK: - Versions

public enum FirmwareVersion {
    private struct Parsed {
        var core: [Int]
        var pre: [String]
    }

    private static func parse(_ text: String) -> Parsed? {
        var s = text.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("v") { s.removeFirst() }
        if let plus = s.firstIndex(of: "+") { s = String(s[..<plus]) }  // build metadata
        var pre: [String] = []
        if let dash = s.firstIndex(of: "-") {
            let tail = String(s[s.index(after: dash)...])
            guard !tail.isEmpty, tail.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" }), tail.allSatisfy(\.isASCII) else { return nil }
            pre = tail.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
            s = String(s[..<dash])
        }
        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        var core: [Int] = []
        for p in parts {
            guard !p.isEmpty, p.allSatisfy({ $0.isASCII && $0.isNumber }), let n = Int(p) else { return nil }
            core.append(n)
        }
        return Parsed(core: core, pre: pre)
    }

    /// Whether `text` is a release version such as `1.3.0` or `1.3.0-beta.1`.
    public static func isValid(_ text: String) -> Bool { parse(text) != nil }

    /// -1, 0 or 1; nil if either side is not a semantic version (a development build, "dev").
    public static func compare(_ a: String, _ b: String) -> Int? {
        guard let x = parse(a), let y = parse(b) else { return nil }
        for i in 0 ..< 3 where x.core[i] != y.core[i] { return x.core[i] < y.core[i] ? -1 : 1 }
        if x.pre.isEmpty || y.pre.isEmpty { return x.pre.count == y.pre.count ? 0 : (x.pre.isEmpty ? 1 : -1) }
        for i in 0 ..< min(x.pre.count, y.pre.count) {
            let p = x.pre[i], q = y.pre[i]
            if p == q { continue }
            let pn = p.allSatisfy(\.isNumber), qn = q.allSatisfy(\.isNumber)
            if pn && qn { return (Int(p) ?? 0) < (Int(q) ?? 0) ? -1 : 1 }
            if pn != qn { return pn ? -1 : 1 }
            return p < q ? -1 : 1
        }
        return x.pre.count == y.pre.count ? 0 : (x.pre.count < y.pre.count ? -1 : 1)
    }
}

// MARK: - Release manifest

/// A release's `manifest.json` (see docs/ota-plan.md).
public struct ReleaseManifest: Equatable {
    public struct Image: Equatable {
        public var file: String?
        public var size: Int?
        public var sha256: String?
        public var sig: String?

        /// All four fields are present and well formed.
        var isValid: Bool {
            guard file != nil, let size, size > 0, let sha256, let sig else { return false }
            return sha256.count == 64 && sha256.allSatisfy(\.isLowercaseHex)
                && sig.count == 128 && sig.allSatisfy(\.isLowercaseHex)
        }

        public var signature: Data? { sig.flatMap(Data.init(hexString:)) }

        public init(file: String?, size: Int?, sha256: String?, sig: String?) {
            self.file = file
            self.size = size
            self.sha256 = sha256
            self.sig = sig
        }
    }

    public var version: String?
    public var protocolVersion: Int?
    /// The oldest firmware that may update directly to this release.
    public var minFrom: String?
    public var notes: String?
    public var images: [String: Image]?

    /// Nil if `data` is not a JSON object.
    public static func parse(_ data: Data) -> ReleaseManifest? {
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        var m = ReleaseManifest()
        m.version = json["version"] as? String
        m.protocolVersion = (json["protocol"] as? NSNumber).flatMap { $0.doubleValue == Double($0.intValue) ? $0.intValue : nil }
        m.minFrom = json["minFrom"] as? String
        m.notes = json["notes"] as? String
        if let images = json["images"] as? [String: Any] {
            m.images = images.mapValues { value in
                let d = value as? [String: Any] ?? [:]
                return Image(
                    file: d["file"] as? String,
                    size: (d["size"] as? NSNumber).flatMap { $0.doubleValue == Double($0.intValue) ? $0.intValue : nil },
                    sha256: d["sha256"] as? String,
                    sig: d["sig"] as? String)
            }
        }
        return m
    }

    public enum Status: String, Equatable {
        case invalid, unavailable
        case upToDate = "up_to_date"
        case newerInstalled = "newer_installed"
        case unknownCurrent = "unknown_current"
        case appTooOld = "app_too_old"
        case needsUsb = "needs_usb"
        case available
    }

    public struct Assessment: Equatable {
        public var status: Status
        public var image: Image?
    }

    /// What this manifest means for a device running `deviceFw`.
    public func assess(chip: Chip, deviceFw: String, appProtocol: Int) -> Assessment {
        guard let version, FirmwareVersion.isValid(version), let images, let protocolVersion else {
            return Assessment(status: .invalid, image: nil)
        }
        guard let image = images[chip.rawValue] else { return Assessment(status: .unavailable, image: nil) }
        guard image.isValid else { return Assessment(status: .invalid, image: nil) }

        guard let cmp = FirmwareVersion.compare(version, deviceFw) else { return Assessment(status: .unknownCurrent, image: image) }
        if cmp == 0 { return Assessment(status: .upToDate, image: image) }
        if cmp < 0 { return Assessment(status: .newerInstalled, image: image) }
        if protocolVersion > appProtocol { return Assessment(status: .appTooOld, image: image) }
        if let minFrom, FirmwareVersion.compare(deviceFw, minFrom) == -1 { return Assessment(status: .needsUsb, image: image) }
        return Assessment(status: .available, image: image)
    }
}

private extension Character {
    var isLowercaseHex: Bool { ("0" ... "9").contains(self) || ("a" ... "f").contains(self) }
}
