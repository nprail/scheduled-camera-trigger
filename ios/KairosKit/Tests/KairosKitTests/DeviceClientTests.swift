import XCTest
@testable import KairosKit

/// A scripted device: reassembles what the client writes, hands each request (op, id, body) to
/// `respond`, and notifies the replies it returns.
final class FakeDevice: DeviceTransport {
    var maxWriteLength = 20
    let notifications: AsyncStream<Data>
    private let sink: AsyncStream<Data>.Continuation
    private var reassembler = Reassembler()
    var requests: [(op: UInt8, id: UInt8, body: Data)] = []
    var respond: (UInt8, UInt8, Data) -> [Data] = { _, _, _ in [] }

    init() {
        let s = AsyncStream.makeStream(of: Data.self)
        notifications = s.stream
        sink = s.continuation
    }

    func write(_ data: Data) async throws {
        guard case .message(let message) = reassembler.push(data) else { return }
        let b = [UInt8](message)
        requests.append((b[0], b[1], Data(b[2...])))
        for reply in respond(b[0], b[1], Data(b[2...])) { send(reply) }
    }

    func send(_ message: Data) {
        for frag in Framing.fragments(for: message, maxFragment: 20) { sink.yield(frag) }
    }

    func disconnect() { sink.finish() }
}

private func ok(_ id: UInt8, _ body: [UInt8] = []) -> Data { Data([0x80, id, 0] + body) }
private func fail(_ id: UInt8, _ status: UInt8, _ body: [UInt8] = []) -> Data { Data([0x80, id, status] + body) }

private func helloBody(nonce: Data, paired: Bool = true) -> [UInt8] {
    var w = ByteWriter()
    w.u8(2); w.u8(paired ? 1 : 0); w.data(nonce); w.u64(1_793_554_715_482); w.u16(2048)
    return w.bytes
}

@MainActor
final class DeviceClientTests: XCTestCase {

    func testHelloAndMutualAuthentication() async throws {
        let a = try Vectors.load().auth
        let secret = a.hexData("secretHex"), nonce = a.hexData("nonceHex")
        let device = FakeDevice()

        device.respond = { op, id, body in
            switch op {
            case 0x01:
                return [ok(id, helloBody(nonce: nonce))]
            case 0x02:
                let clientNonce = body.prefix(16), proof = body.dropFirst(16)
                // the device checks the client's proof, then proves itself
                XCTAssertEqual(Data(proof), AuthProof.client(secret: secret, nonce: nonce, clientNonce: Data(clientNonce)))
                return [ok(id, [UInt8](AuthProof.server(secret: secret, nonce: nonce, clientNonce: Data(clientNonce))))]
            default:
                return []
            }
        }

        let client = DeviceClient(transport: device)
        let hello = try await client.hello()
        XCTAssertTrue(hello.paired)
        XCTAssertEqual(hello.maxMessage, 2048)
        XCTAssertEqual(hello.now, Date(timeIntervalSince1970: 1_793_554_715.482))
        try await client.authenticate(secret: secret, hello: hello)
    }

    func testAuthRejectsADeviceThatCannotProveItself() async throws {
        let device = FakeDevice()
        device.respond = { op, id, _ in
            op == 0x01 ? [ok(id, helloBody(nonce: Data(count: 16)))] : [ok(id, [UInt8](Data(count: 32)))]
        }
        let client = DeviceClient(transport: device)
        let hello = try await client.hello()
        do {
            try await client.authenticate(secret: Data(count: 16), hello: hello)
            XCTFail("expected failure")
        } catch let error as DeviceError {
            XCTAssertEqual(error.code, "unauthorized")
        }
    }

    func testAnUnsupportedVersionIsAnError() async throws {
        let device = FakeDevice()
        device.respond = { _, id, _ in
            var w = ByteWriter()
            w.u8(1); w.u8(0); w.data(Data(count: 16)); w.u64(0); w.u16(4096)
            return [ok(id, w.bytes)]
        }
        let client = DeviceClient(transport: device)
        do {
            _ = try await client.hello()
            XCTFail("expected failure")
        } catch let error as DeviceError {
            XCTAssertEqual(error.code, "unsupported")
            XCTAssertTrue(error.message.contains("Update the app"))
        }
    }

    func testDeviceErrorsAreSurfacedWithTheirDetail() async throws {
        let device = FakeDevice()
        device.respond = { _, id, _ in [fail(id, 6, [12, 3, 4])] } // attempts[3].time is in the past
        let client = DeviceClient(transport: device)
        do {
            _ = try await client.setConfig(CameraConfig(generic: .init(releaseGpioPin: 5)))
            XCTFail("expected failure")
        } catch let error as DeviceError {
            XCTAssertEqual(error.code, "invalid_config")
            XCTAssertEqual(error.field, "attempts[3].time")
            XCTAssertEqual(error.localizedDescription, "attempts[3].time: must be in the future")
        }
    }

    func testAnErrorWithIdZeroFailsTheRequestInFlight() async throws {
        let device = FakeDevice()
        device.respond = { _, _, _ in [fail(0, 1)] } // a bad frame: the device could not read the id
        let client = DeviceClient(transport: device)
        do {
            _ = try await client.config()
            XCTFail("expected failure")
        } catch let error as DeviceError {
            XCTAssertEqual(error.code, "bad_request")
        }
    }

    func testJobsAreDerivedFromTheConfigAndTheStates() async throws {
        let device = FakeDevice()
        device.respond = { _, id, _ in [ok(id, [3, 1, 0, 3])] } // active, pending, missed
        let client = DeviceClient(transport: device)
        let config = CameraConfig(attempts: [
            .init(name: "A", seconds: 1_793_554_740), .init(name: "B", seconds: 1_793_641_140), .init(name: "C", seconds: 1_793_700_000),
        ])
        let jobs = try await client.jobs(for: config)
        XCTAssertEqual(jobs.map(\.name), ["A", "B", "C"])
        XCTAssertEqual(jobs.map(\.state), ["active", "pending", "missed"])
        XCTAssertEqual(jobs[1].launchDate, Date(timeIntervalSince1970: 1_793_641_140))
    }

    func testAFullConfigRoundTripsAcrossManyFragments() async throws {
        let device = FakeDevice()
        let full = configFrom(try Vectors.load().list("config")[3].obj("config"))
        var received: Data?
        device.respond = { op, id, body in
            if op == 0x08 {
                received = body
                return [ok(id, [13, 0, 0, 0])]
            }
            return [ok(id, [UInt8](try! Wire.encodeConfig(full)))]
        }
        let client = DeviceClient(transport: device)
        let rev = try await client.setConfig(full)
        XCTAssertEqual(rev, 13)
        XCTAssertEqual(received, try Wire.encodeConfig(full))
        let back = try await client.config()
        XCTAssertEqual(back, full)
    }

    func testEventsAreDecoded() async throws {
        let device = FakeDevice()
        let client = DeviceClient(transport: device)
        var iterator = client.events.makeAsyncIterator()

        for v in try Vectors.load().list("messages") where ["job wake", "test done", "log", "config"].contains(v.str("name")) && v.str("kind") == "event" {
            device.send(v.hexData("hex"))
        }

        guard case .job(let job)? = await iterator.next() else { return XCTFail("job") }
        XCTAssertEqual(job.attempt, 0)
        XCTAssertEqual(job.step, "wake")
        guard case .config(let rev)? = await iterator.next() else { return XCTFail("config") }
        XCTAssertEqual(rev, 14)
        guard case .test(let t)? = await iterator.next() else { return XCTFail("test") }
        XCTAssertEqual(t, TestEvent(step: "done", ok: true, error: nil))
        guard case .log(let entry)? = await iterator.next() else { return XCTFail("log") }
        XCTAssertEqual(entry.msg, "Start 'Attempt 1'")
        XCTAssertEqual(entry.seq, 121)
    }

    func testClockSyncSetsTheDeviceClockOnlyWhenItDrifts() async throws {
        let device = FakeDevice()
        var drift: TimeInterval = 3
        var setTo: UInt64?
        device.respond = { op, id, body in
            var w = ByteWriter()
            if op == 0x04 {
                w.u64(UInt64((Date().timeIntervalSince1970 + drift) * 1000))
                return [ok(id, w.bytes)]
            }
            var r = ByteReader(body)
            setTo = r.u64()
            drift = 0
            w.u64(setTo!); w.u32(UInt32(bitPattern: Int32(3000)))
            return [ok(id, w.bytes)]
        }
        let client = DeviceClient(transport: device)

        let first = try await client.syncClock()
        XCTAssertTrue(first.applied)
        XCTAssertEqual(first.offset, 3, accuracy: 0.5)
        XCTAssertNotNil(setTo)
        XCTAssertEqual(Double(setTo!) / 1000, Date().timeIntervalSince1970, accuracy: 2)

        let second = try await client.syncClock()
        XCTAssertFalse(second.applied)
        XCTAssertEqual(device.requests.filter { $0.op == 0x05 }.count, 1)
    }

    func testABusyDeviceKeepsItsClock() async throws {
        let device = FakeDevice()
        device.respond = { op, id, _ in
            if op == 0x04 {
                var w = ByteWriter()
                w.u64(UInt64((Date().timeIntervalSince1970 + 9) * 1000))
                return [ok(id, w.bytes)]
            }
            return [fail(id, 5)]
        }
        let sync = try await DeviceClient(transport: device).syncClock()
        XCTAssertFalse(sync.applied)
    }

    func testLogsAreRequestedAndParsed() async throws {
        let device = FakeDevice()
        var asked: Data?
        let line = try Vectors.load().list("messages").first { $0.str("name") == "logs.get" && $0.str("kind") == "response" }!
        device.respond = { _, id, body in
            asked = body
            guard case .response(_, _, let rb)? = Wire.parse(line.hexData("hex")) else { return [] }
            return [ok(id, [UInt8](rb))]
        }
        let page = try await DeviceClient(transport: device).logs(after: 120, limit: 20)
        XCTAssertEqual(asked?.hex, "7800000014")
        XCTAssertEqual(page.entries.count, 2)
        XCTAssertEqual(page.last, 121)
        XCTAssertTrue(page.more)
    }

    func testRequestIdsStayInOneByte() async throws {
        let device = FakeDevice()
        device.respond = { _, id, _ in [ok(id, [0])] }
        let client = DeviceClient(transport: device)
        for _ in 0 ..< 300 { try await client.camera(.wake) }
        XCTAssertTrue(device.requests.allSatisfy { $0.id >= 1 })
        XCTAssertEqual(device.requests.last?.id, UInt8(300 % 255 == 0 ? 255 : 300 % 255))
    }

    func testRequestsFailWhenTheLinkDrops() async throws {
        let device = FakeDevice() // never answers
        let client = DeviceClient(transport: device)
        Task { try? await Task.sleep(nanoseconds: 50_000_000); device.disconnect() }
        do {
            _ = try await client.config()
            XCTFail("expected failure")
        } catch let error as DeviceError {
            XCTAssertEqual(error, DeviceError.disconnected)
        }
    }
}
