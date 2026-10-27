import CryptoKit
import XCTest
@testable import KairosKit

final class FirmwareVectorTests: XCTestCase {
    private func firmware() throws -> [String: Any] { try Vectors.load().root["firmware"] as! [String: Any] }

    func testImagesAreChecked() throws {
        for v in try firmware().array("images") {
            let chip = try XCTUnwrap(Chip(rawValue: v.str("chip")))
            let e = v.obj("expected")
            let result = FirmwareImage.check(v.hexData("hex"), for: chip)
            switch e.str("status") {
            case "ok": XCTAssertEqual(result, .ok(version: e.optStr("version")), v.str("name"))
            case "not_image": XCTAssertEqual(result, .notImage, v.str("name"))
            case "wrong_chip": XCTAssertEqual(result, .wrongChip, v.str("name"))
            case "not_app": XCTAssertEqual(result, .notApp, v.str("name"))
            default: XCTFail("unknown status \(e.str("status"))")
            }
        }
    }

    func testVersionsCompare() throws {
        for v in try firmware().array("versions") {
            XCTAssertEqual(FirmwareVersion.compare(v.str("a"), v.str("b")), v.optInt("expected"), "\(v.str("a")) vs \(v.str("b"))")
        }
    }

    func testManifestsAreAssessed() throws {
        for v in try firmware().array("manifests") {
            let data = try JSONSerialization.data(withJSONObject: v.obj("manifest"))
            let manifest = try XCTUnwrap(ReleaseManifest.parse(data))
            let chip = try XCTUnwrap(Chip(rawValue: v.str("chip")))
            let expected = v.obj("expected").str("status")
            let result = manifest.assess(chip: chip, deviceFw: v.str("deviceFw"), appProtocol: v.int("appProtocol"))
            XCTAssertEqual(result.status.rawValue, expected, v.str("name"))
        }
    }

    func testFirmwareWritesMatchTheVectors() throws {
        // [offset u32][bytes], built the way the uploader builds it
        for v in try Vectors.load().list("firmwareWrites") {
            var w = ByteWriter()
            w.u32(UInt32(v.int("offset")))
            w.data(v.hexData("dataHex"))
            XCTAssertEqual(Data(w.bytes).hex, v.str("hex"), v.str("name"))
        }
    }

    func testSignatureFilesAcceptRawBytesOrHex() {
        let raw = Data((0 ..< 64).map { UInt8($0) })
        XCTAssertEqual(FirmwareImage.signature(from: raw), raw)
        XCTAssertEqual(FirmwareImage.signature(from: Data((raw.hex + "\n").utf8)), raw)
        XCTAssertNil(FirmwareImage.signature(from: Data(count: 10)))
        XCTAssertNil(FirmwareImage.signature(from: Data(String(repeating: "zz", count: 64).utf8)))
    }
}

// MARK: - A device that receives firmware

/// A fake device that behaves like the firmware's updater: it accepts `[offset][bytes]` writes in order, asks
/// for a rewind on a gap, and can lose writes.
@MainActor
final class FakeUpdatingDevice: DeviceTransport {
    var maxWriteLength = 64
    let notifications: AsyncStream<Data>
    private let sink: AsyncStream<Data>.Continuation
    private var reassembler = Reassembler()

    var window = 512
    var received = Data()
    var expectedSize = 0
    var expectedSha = Data()
    var begun = false
    var ended = false
    var aborted = false
    var beginSignature = Data()
    /// Indexes of firmware writes (counting from 0) that are lost on the way.
    var drop: Set<Int> = []
    var writes = 0
    var failWith: UInt8?
    var beginError: (status: UInt8, body: [UInt8])?
    var resumeAt = 0
    private var lastReported = 0

    init() {
        let s = AsyncStream.makeStream(of: Data.self)
        notifications = s.stream
        sink = s.continuation
    }

    nonisolated func write(_ data: Data) async throws {
        await MainActor.run { self.handleCommand(data) }
    }

    func writeFirmware(_ data: Data) async throws {
        let index = writes
        writes += 1
        if drop.contains(index) { return }
        var r = ByteReader(data)
        let offset = Int(r.u32())
        let bytes = r.rest()
        guard begun, !ended else { return }
        if let reason = failWith {
            event(3, received.count, reason)
            begun = false
            return
        }
        if offset > received.count { event(0, received.count, 0); return }  // a gap
        let skip = received.count - offset
        if skip >= bytes.count { return }
        received.append(bytes.dropFirst(skip))
        if received.count - lastReported >= window / 4 || received.count == expectedSize {
            lastReported = received.count
            event(0, received.count, 0)
        }
    }

    private func handleCommand(_ data: Data) {
        guard case .message(let message) = reassembler.push(data) else { return }
        let b = [UInt8](message)
        let id = b[1]
        var body = ByteReader(Data(b[2...]))
        switch b[0] {
        case 0x30:
            if let e = beginError { reply(Data([0x80, id, e.status] + e.body)); return }
            expectedSize = Int(body.u32())
            expectedSha = body.data(32)
            beginSignature = body.data(64)
            begun = true
            received = Data(repeating: 0, count: resumeAt)
            lastReported = resumeAt
            var w = ByteWriter()
            w.u8(0x80); w.u8(id); w.u8(0); w.u32(UInt32(resumeAt)); w.u16(UInt16(window))
            reply(Data(w.bytes))
        case 0x31:
            ended = true
            var w = ByteWriter()
            w.u8(0x80); w.u8(id); w.u8(0); w.u16(500)
            reply(Data(w.bytes))
        case 0x32:
            aborted = true
            begun = false
            reply(Data([0x80, id, 0]))
        default:
            reply(Data([0x80, id, 4]))
        }
    }

    private func reply(_ message: Data) {
        for frag in Framing.fragments(for: message, maxFragment: 20) { sink.yield(frag) }
    }

    private func event(_ state: UInt8, _ offset: Int, _ reason: UInt8) {
        var w = ByteWriter()
        w.u8(0x81); w.u8(0x05); w.u8(state); w.u32(UInt32(offset)); w.u8(reason)
        reply(Data(w.bytes))
    }

    func disconnect() { sink.finish() }
}

@MainActor
final class FirmwareUploaderTests: XCTestCase {
    private func image(_ size: Int) -> Data { Data((0 ..< size).map { UInt8(($0 &* 31 &+ 7) & 0xFF) }) }

    private func make(_ device: FakeUpdatingDevice, stall: TimeInterval = 0.15) -> FirmwareUploader {
        FirmwareUploader(client: DeviceClient(transport: device), transport: device, stallTimeout: stall, maxStalls: 4)
    }

    func testAWholeImageIsSentInOrderWithinTheWindow() async throws {
        let device = FakeUpdatingDevice()
        let img = image(5000)
        var seen: [Double] = []
        let delay = try await make(device).upload(img, signature: Data(repeating: 7, count: 64)) { seen.append($0.fraction) }
        XCTAssertEqual(delay, 500)
        XCTAssertEqual(device.received, img)
        XCTAssertEqual(device.expectedSha, Data(SHA256.hash(data: img)))
        XCTAssertEqual(device.beginSignature, Data(repeating: 7, count: 64))
        XCTAssertTrue(device.ended)
        XCTAssertEqual(seen.last, 1)
        XCTAssertEqual(seen, seen.sorted())
    }

    func testAnUnsignedImageSendsAllZeros() async throws {
        let device = FakeUpdatingDevice()
        _ = try await make(device).upload(image(2000), signature: Data()) { _ in }
        XCTAssertEqual(device.beginSignature, Data(count: 64))
    }

    func testLostWritesAreSentAgainFromTheDevicesOffset() async throws {
        let device = FakeUpdatingDevice()
        device.drop = [3, 4, 9]
        let img = image(4000)
        _ = try await make(device).upload(img, signature: Data(count: 64)) { _ in }
        XCTAssertEqual(device.received, img)
        XCTAssertGreaterThan(device.writes, 4000 / 60)  // some were sent again
    }

    func testALostFinalWriteIsRecoveredByTheStallTimeout() async throws {
        let device = FakeUpdatingDevice()
        let img = image(1000)
        let payload = device.maxWriteLength - 4
        device.drop = [Int(ceil(Double(img.count) / Double(payload))) - 1]  // the last chunk
        _ = try await make(device).upload(img, signature: Data(count: 64)) { _ in }
        XCTAssertEqual(device.received, img)
    }

    func testAResumedTransferStartsAtTheDevicesOffset() async throws {
        let device = FakeUpdatingDevice()
        device.resumeAt = 1200
        let img = image(3000)
        var first: Double?
        _ = try await make(device).upload(img, signature: Data(count: 64)) { if first == nil { first = $0.fraction } }
        XCTAssertEqual(first, 0.4)
        XCTAssertEqual(device.received.suffix(1800), img.suffix(1800))
        XCTAssertEqual(device.writes, Int(ceil(1800.0 / Double(device.maxWriteLength - 4))))
    }

    func testTheDeviceRefusingTheImageSurfacesItsReason() async {
        let device = FakeUpdatingDevice()
        device.failWith = 3  // chip
        do {
            _ = try await make(device).upload(image(3000), signature: Data(count: 64)) { _ in }
            XCTFail("should fail")
        } catch let error as DeviceError {
            XCTAssertEqual(error.code, "bad_image")
            XCTAssertEqual(error.message, Wire.badImageText(3))
        } catch { XCTFail("\(error)") }
        XCTAssertFalse(device.aborted)  // the device already dropped it
    }

    func testBeginErrorsAreExplained() async {
        for (status, body, code, text) in [
            (UInt8(5), [UInt8](), "update_blocked", "15 minutes"),
            (UInt8(7), [UInt8](), "too_large", "too large"),
            (UInt8(10), [UInt8(6)], "bad_image", "isn't signed"),
            (UInt8(10), [UInt8(7)], "bad_image", "signature"),
            (UInt8(4), [UInt8](), "not_supported", "does not support"),
        ] {
            let device = FakeUpdatingDevice()
            device.beginError = (status, body)
            do {
                _ = try await make(device).upload(image(2000), signature: Data(count: 64)) { _ in }
                XCTFail("should fail")
            } catch let error as DeviceError {
                XCTAssertEqual(error.code, code)
                XCTAssertTrue(error.message.contains(text), error.message)
            } catch { XCTFail("\(error)") }
        }
    }

    func testCancellingAbortsTheTransfer() async throws {
        let device = FakeUpdatingDevice()
        device.drop = Set(0 ..< 10_000)  // nothing ever arrives, so the upload keeps waiting
        let uploader = make(device, stall: 5)
        let task = Task { try await uploader.upload(self.image(3000), signature: Data(count: 64)) { _ in } }
        try await Task.sleep(nanoseconds: 200_000_000)
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("should be cancelled")
        } catch is CancellationError {
        } catch { XCTFail("\(error)") }
        XCTAssertTrue(device.aborted)
    }

    func testAnUnansweredTransferTimesOutAndKeepsThePartialImage() async {
        let device = FakeUpdatingDevice()
        device.drop = Set(0 ..< 10_000)
        do {
            _ = try await make(device, stall: 0.05).upload(image(3000), signature: Data(count: 64)) { _ in }
            XCTFail("should time out")
        } catch let error as DeviceError {
            XCTAssertEqual(error.code, "timeout")
        } catch { XCTFail("\(error)") }
        XCTAssertFalse(device.aborted)  // the device keeps it for two minutes, so the same image can resume
    }

    func testALinkThatClosesEndsTheUpload() async throws {
        let device = FakeUpdatingDevice()
        device.drop = Set(0 ..< 10_000)
        let uploader = make(device, stall: 5)
        let task = Task { try await uploader.upload(self.image(3000), signature: Data(count: 64)) { _ in } }
        try await Task.sleep(nanoseconds: 200_000_000)
        device.disconnect()
        do {
            _ = try await task.value
            XCTFail("should fail")
        } catch let error as DeviceError {
            XCTAssertEqual(error.code, "disconnected")
        } catch { XCTFail("\(error)") }
    }
}

// MARK: - GitHub releases

private struct ScriptedFetcher: HTTPFetching {
    var responses: [String: HTTPResponse]
    var calls: Calls

    final class Calls { var urls: [String] = []; var etags: [String?] = [] }

    func fetch(_ url: URL, etag: String?) async throws -> HTTPResponse {
        calls.urls.append(url.absoluteString)
        calls.etags.append(etag)
        guard let r = responses[url.absoluteString] else { throw URLError(.notConnectedToInternet) }
        if etag != nil, etag == r.etag { return HTTPResponse(status: 304, data: Data(), etag: r.etag) }
        return r
    }
}

final class UpdateServiceTests: XCTestCase {
    private let sha = String(repeating: "a", count: 64)
    private let sig = String(repeating: "b", count: 128)

    private func manifest(version: String, image: Data? = nil) -> Data {
        let bytes = image ?? Data(count: 10)
        let entry: [String: Any] = [
            "file": "kairos-esp32s3-\(version)-app.bin", "size": bytes.count,
            "sha256": image == nil ? sha : UpdateService.sha256Hex(bytes), "sig": sig,
        ]
        let json: [String: Any] = ["version": version, "protocol": 2, "minFrom": "1.0.0", "images": ["esp32s3": entry]]
        return try! JSONSerialization.data(withJSONObject: json)
    }

    private static let latestURL = "https://github.com/nprail/kairos/releases/latest/download/manifest.json"

    func testTheLatestCheckIsThrottledToTenMinutesAndForceAsksAgain() async throws {
        let calls = ScriptedFetcher.Calls()
        let fetcher = ScriptedFetcher(responses: [Self.latestURL: HTTPResponse(status: 200, data: manifest(version: "1.3.0"), etag: "\"v1\"")], calls: calls)
        let store = MemoryUpdateStore()
        var clock = Date(timeIntervalSince1970: 1_000_000)
        let service = UpdateService(fetcher: fetcher, store: store, now: { clock })

        var result = try await service.latest()
        XCTAssertFalse(result.fromCache)
        XCTAssertEqual(result.manifest.version, "1.3.0")
        XCTAssertEqual(calls.urls.count, 1)

        clock += 599
        result = try await service.latest()
        XCTAssertTrue(result.fromCache)
        XCTAssertEqual(calls.urls.count, 1, "no request inside the window")

        result = try await service.latest(force: true)  // the Check button ignores the window
        XCTAssertTrue(result.fromCache, "a 304 keeps the saved copy")
        XCTAssertEqual(calls.urls.count, 2)
        XCTAssertEqual(calls.etags.last, "\"v1\"")

        clock += 600
        _ = try await service.latest()
        XCTAssertEqual(calls.urls.count, 3)
    }

    func testAFailedCheckDoesNotStartTheWindow() async throws {
        let calls = ScriptedFetcher.Calls()
        let store = MemoryUpdateStore()
        let offline = UpdateService(fetcher: ScriptedFetcher(responses: [:], calls: calls), store: store)
        do {
            _ = try await offline.latest()
            XCTFail("should fail")
        } catch let error as DeviceError {
            XCTAssertEqual(error.code, "network")
        }
        XCTAssertNil(store.lastCheck)

        let online = UpdateService(fetcher: ScriptedFetcher(responses: [Self.latestURL: HTTPResponse(status: 200, data: manifest(version: "1.3.0"))], calls: calls), store: store)
        let result = try await online.latest()
        XCTAssertFalse(result.fromCache)
    }

    func testAnOfflinePhoneStillSeesTheSavedManifestInsideTheWindow() async throws {
        let store = MemoryUpdateStore()
        store.manifest = manifest(version: "1.3.0")
        store.lastCheck = Date()
        let service = UpdateService(fetcher: ScriptedFetcher(responses: [:], calls: .init()), store: store)
        let result = try await service.latest()
        XCTAssertTrue(result.fromCache)
    }

    func testAnUnusableAnswerIsAnError() async {
        let fetcher = ScriptedFetcher(responses: [Self.latestURL: HTTPResponse(status: 404, data: Data())], calls: .init())
        let service = UpdateService(fetcher: fetcher, store: MemoryUpdateStore())
        do {
            _ = try await service.latest()
            XCTFail("should fail")
        } catch let error as DeviceError {
            XCTAssertEqual(error.code, "network")
        } catch { XCTFail("\(error)") }
    }

    func testReleasesAreListedAndPrereleasesAreHiddenByDefault() async throws {
        let list = """
        [{"tag_name":"v1.4.0-beta.1","prerelease":true,"draft":false,"assets":[{"name":"manifest.json","browser_download_url":"https://example.test/m1.json"}]},
         {"tag_name":"v1.3.0","prerelease":false,"draft":false,"assets":[{"name":"manifest.json","browser_download_url":"https://example.test/m2.json"},{"name":"x.bin","browser_download_url":"https://example.test/x"}]},
         {"tag_name":"v1.2.0","prerelease":false,"draft":true,"assets":[]}]
        """
        let url = "https://api.github.com/repos/nprail/kairos/releases?per_page=30"
        let service = UpdateService(fetcher: ScriptedFetcher(responses: [url: HTTPResponse(status: 200, data: Data(list.utf8))], calls: .init()), store: MemoryUpdateStore())
        let stable = try await service.releases(includePrereleases: false)
        XCTAssertEqual(stable.map(\.tag), ["v1.3.0"])
        XCTAssertEqual(stable[0].version, "1.3.0")
        XCTAssertEqual(stable[0].manifestURL?.absoluteString, "https://example.test/m2.json")
        let all = try await service.releases(includePrereleases: true)
        XCTAssertEqual(all.map(\.tag), ["v1.4.0-beta.1", "v1.3.0"])
    }

    func testWithPrereleasesTheLatestCheckFindsTheNewestReleaseOfAnyKind() async throws {
        let list = """
        [{"tag_name":"v1.4.0-beta.1","prerelease":true,"draft":false,"assets":[{"name":"manifest.json","browser_download_url":"https://example.test/beta.json"}]},
         {"tag_name":"v1.3.0","prerelease":false,"draft":false,"assets":[{"name":"manifest.json","browser_download_url":"https://example.test/stable.json"}]}]
        """
        let api = "https://api.github.com/repos/nprail/kairos/releases?per_page=30"
        let fetcher = ScriptedFetcher(responses: [
            api: HTTPResponse(status: 200, data: Data(list.utf8)),
            "https://example.test/beta.json": HTTPResponse(status: 200, data: manifest(version: "1.4.0-beta.1")),
            Self.latestURL: HTTPResponse(status: 200, data: manifest(version: "1.3.0")),
        ], calls: .init())
        let service = UpdateService(fetcher: fetcher, store: MemoryUpdateStore())
        let withBeta = try await service.latest(force: true, includePrereleases: true)
        XCTAssertEqual(withBeta.manifest.version, "1.4.0-beta.1")
        let stable = try await service.latest(force: true)
        XCTAssertEqual(stable.manifest.version, "1.3.0")
    }

    func testADownloadIsCheckedAgainstTheManifest() async throws {
        let bytes = Data((0 ..< 500).map { UInt8($0 % 251) })
        let entry = ReleaseManifest.Image(file: "kairos-esp32s3-1.3.0-app.bin", size: bytes.count, sha256: UpdateService.sha256Hex(bytes), sig: sig)
        let url = "https://github.com/nprail/kairos/releases/download/v1.3.0/kairos-esp32s3-1.3.0-app.bin"
        let good = UpdateService(fetcher: ScriptedFetcher(responses: [url: HTTPResponse(status: 200, data: bytes)], calls: .init()), store: MemoryUpdateStore())
        let data = try await good.download(entry, version: "1.3.0")
        XCTAssertEqual(data, bytes)

        var corrupt = bytes
        corrupt[10] ^= 1
        let bad = UpdateService(fetcher: ScriptedFetcher(responses: [url: HTTPResponse(status: 200, data: corrupt)], calls: .init()), store: MemoryUpdateStore())
        do {
            _ = try await bad.download(entry, version: "1.3.0")
            XCTFail("should fail")
        } catch let error as DeviceError {
            XCTAssertEqual(error.code, "bad_download")
        }
    }

    func testTheCacheKeepsVerifiedImagesAndTheNewestTwo() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("kairos-cache-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let cache = FirmwareCache(directory: dir, keep: 2)
        let a = Data(repeating: 1, count: 100), b = Data(repeating: 2, count: 100), c = Data(repeating: 3, count: 100)
        for d in [a, b, c] {
            cache.store(d, sha256: UpdateService.sha256Hex(d))
            Thread.sleep(forTimeInterval: 0.02)
        }
        XCTAssertNil(cache.image(sha256: UpdateService.sha256Hex(a), size: 100), "the oldest was trimmed")
        XCTAssertEqual(cache.image(sha256: UpdateService.sha256Hex(b), size: 100), b)
        XCTAssertEqual(cache.image(sha256: UpdateService.sha256Hex(c), size: 100), c)
        XCTAssertNil(cache.image(sha256: UpdateService.sha256Hex(c), size: 99), "a wrong size is not trusted")
    }
}
