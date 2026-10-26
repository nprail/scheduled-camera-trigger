import Foundation

/// Message framing from docs/ble-protocol.md §3.
///
///     First fragment:        [0x80 | idx] [u16 BE total length] [payload…]
///     Continuation fragment: [idx]                              [payload…]
public enum Framing {
    public static let maxMessage = 2048

    /// Split `payload` into fragments of at most `maxFragment` bytes each.
    public static func fragments(for payload: Data, maxFragment: Int) -> [Data] {
        precondition(maxFragment >= 4, "maxFragment too small")
        precondition(payload.count <= 0xFFFF, "payload too long")

        let bytes = [UInt8](payload)
        var out: [Data] = []
        var idx: UInt8 = 0
        var offset = 0

        repeat {
            let first = out.isEmpty
            let headerSize = first ? 3 : 1
            let take = min(bytes.count - offset, maxFragment - headerSize)

            var frag = [UInt8]()
            frag.reserveCapacity(headerSize + take)
            frag.append((first ? 0x80 : 0x00) | (idx & 0x7F))
            if first {
                frag.append(UInt8((bytes.count >> 8) & 0xFF))
                frag.append(UInt8(bytes.count & 0xFF))
            }
            frag.append(contentsOf: bytes[offset ..< offset + take])
            out.append(Data(frag))

            offset += take
            idx = (idx + 1) & 0x7F
        } while offset < bytes.count

        return out
    }
}

public enum FramingError: Error, Equatable {
    case badFrame
    case tooLarge
}

/// Reassembles notification fragments into whole messages.
public struct Reassembler {
    public enum Result: Equatable {
        case needMore
        case message(Data)
        case error(FramingError)
    }

    public let maxMessage: Int
    private var total = 0
    private var buffer = [UInt8]()
    private var nextIdx: UInt8 = 0
    private var active = false
    private var discarding = false

    public init(maxMessage: Int = Framing.maxMessage) {
        self.maxMessage = maxMessage
    }

    public var isPending: Bool { active }

    public mutating func reset() {
        total = 0
        buffer.removeAll(keepingCapacity: true)
        nextIdx = 0
        active = false
        discarding = false
    }

    public mutating func push(_ data: Data) -> Result {
        let bytes = [UInt8](data)
        guard let header = bytes.first else { return .error(.badFrame) }

        let isFirst = (header & 0x80) != 0
        let idx = header & 0x7F

        if isFirst {
            reset()
            guard bytes.count >= 3, idx == 0 else { return .error(.badFrame) }

            let length = (Int(bytes[1]) << 8) | Int(bytes[2])
            if length == 0 { return .error(.badFrame) }
            if length > maxMessage {
                discarding = true
                return .error(.tooLarge)
            }

            total = length
            active = true
            nextIdx = 1
            return append(bytes[3...])
        }

        if discarding { return .needMore }
        if !active || idx != nextIdx {
            reset()
            return .error(.badFrame)
        }

        nextIdx = (nextIdx + 1) & 0x7F
        return append(bytes[1...])
    }

    private mutating func append(_ chunk: ArraySlice<UInt8>) -> Result {
        buffer.append(contentsOf: chunk)

        if buffer.count > total {
            reset()
            return .error(.badFrame)
        }
        if buffer.count == total {
            let message = Data(buffer)
            reset()
            return .message(message)
        }
        return .needMore
    }
}
