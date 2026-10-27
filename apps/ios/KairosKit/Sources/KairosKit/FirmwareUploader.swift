import CryptoKit
import Foundation

/// Sends one firmware image to a device (docs/ble-protocol.md, fw.*): `fw.begin`, the bytes on the Firmware
/// characteristic within the device's window, `fw.end`. The device reports progress; a lost write shows up as
/// an event that does not move, and the uploader sends again from where the device is.
@MainActor
public final class FirmwareUploader {
    public struct Progress: Equatable {
        /// Bytes the device has confirmed.
        public var acknowledged: Int
        public var total: Int
        public var fraction: Double { total == 0 ? 0 : Double(acknowledged) / Double(total) }
    }

    private let client: DeviceClient
    private let transport: DeviceTransport
    /// How long to wait for the device to report before sending again from its offset.
    private let stallTimeout: TimeInterval
    private let maxStalls: Int

    public init(client: DeviceClient, transport: DeviceTransport, stallTimeout: TimeInterval = 3, maxStalls: Int = 4) {
        self.client = client
        self.transport = transport
        self.stallTimeout = stallTimeout
        self.maxStalls = maxStalls
    }

    /// Send `image` and tell the device to install it. Returns the delay, in milliseconds, before it restarts.
    /// `signature` is the 64-byte signature, or empty for an unsigned (developer) image.
    public func upload(_ image: Data, signature: Data, progress: (Progress) -> Void) async throws -> Int {
        let total = image.count
        let sha = Data(SHA256.hash(data: image))
        let begin = try await client.firmwareBegin(size: total, sha256: sha, signature: signature)
        client.dropFirmwareEvents()

        let window = max(begin.window, 1024)
        let payload = max(transport.maxWriteLength - 4, 16)
        var acked = min(begin.offset, total)
        var sent = acked
        var stalls = 0
        var lastRewind = Date.distantPast
        progress(Progress(acknowledged: acked, total: total))

        func handle(_ event: FirmwareEvent) throws {
            if event.state == .failed {
                throw Wire.deviceError(status: 10, body: Data([UInt8(truncatingIfNeeded: event.reason)]))
            }
            if event.offset > acked {
                acked = min(event.offset, total)
                stalls = 0
                progress(Progress(acknowledged: acked, total: total))
            } else if event.offset == acked, sent > acked, Date().timeIntervalSince(lastRewind) > 0.3 {
                // the device is still waiting for byte `acked`: what came after it was lost
                sent = acked
                lastRewind = Date()
            }
        }

        do {
            while acked < total {
                try Task.checkCancellation()
                while let event = client.pollFirmwareEvent() { try handle(event) }
                if acked >= total { break }

                if sent < total, sent - acked < window {
                    let end = min(sent + payload, total)
                    var w = ByteWriter()
                    w.u32(UInt32(sent))
                    w.data(image.subdata(in: sent ..< end))
                    try await transport.writeFirmware(Data(w.bytes))
                    sent = end
                    continue
                }

                if let event = await client.awaitFirmwareEvent(timeout: stallTimeout) {
                    try handle(event)
                } else {
                    if client.isLinkClosed { throw DeviceError.disconnected }
                    stalls += 1
                    if stalls >= maxStalls { throw DeviceError.timeout }
                    sent = acked  // nothing came back: send the unconfirmed part again
                    lastRewind = Date()
                }
            }
            return try await client.firmwareEnd()
        } catch is CancellationError {
            await client.firmwareAbort()
            throw CancellationError()
        } catch let error as DeviceError where error.code == "bad_image" {
            throw error  // the device has already dropped the transfer
        } catch let error as DeviceError where error.code == "disconnected" || error.code == "timeout" {
            throw error  // the device keeps a partial transfer for two minutes: the same image can resume
        } catch {
            await client.firmwareAbort()
            throw error
        }
    }
}
