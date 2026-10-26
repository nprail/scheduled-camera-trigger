import XCTest
@testable import KairosKit

/// A scripted device: reassembles what the client writes, hands each request
/// to `respond`, and notifies the reply (plus any extra messages) back.
final class FakeDevice: DeviceTransport {
    var maxWriteLength = 20
    let notifications: AsyncStream<Data>
    private let sink: AsyncStream<Data>.Continuation
    private var reassembler = Reassembler()
    var requests: [[String: Any]] = []
    var respond: ([String: Any]) -> [[String: Any]] = { _ in [] }

    init() {
        let s = AsyncStream.makeStream(of: Data.self)
        notifications = s.stream
        sink = s.continuation
    }

    func write(_ data: Data) async throws {
        guard case .message(let message) = reassembler.push(data) else { return }
        let request = try JSONSerialization.jsonObject(with: message) as! [String: Any]
        requests.append(request)
        for reply in respond(request) { send(reply) }
    }

    func send(_ object: [String: Any]) {
        let body = try! JSONSerialization.data(withJSONObject: object)
        for frag in Framing.fragments(for: body, maxFragment: 20) { sink.yield(frag) }
    }

    func disconnect() { sink.finish() }
}

private func ok(_ request: [String: Any], _ d: [String: Any] = [:]) -> [String: Any] {
    ["id": request["id"]!, "ok": true, "d": d]
}

@MainActor
final class DeviceClientTests: XCTestCase {

    func testHelloAndMutualAuthentication() async throws {
        let vectors = try Vectors.load().auth
        let secret = Data(hex: vectors.secretHex)!
        let nonce = Data(base64Encoded: vectors.nonceB64)!
        let device = FakeDevice()

        device.respond = { req in
            switch req["op"] as! String {
            case "hello":
                return [ok(req, ["v": 1, "nonce": vectors.nonceB64, "authRequired": true,
                                 "paired": true, "now": "2026-11-01T12:00:00.000Z", "maxMessage": 4096])]
            case "auth":
                let d = req["d"] as! [String: String]
                let clientNonce = Data(base64Encoded: d["clientNonce"]!)!
                // the device checks the client's proof, then proves itself
                XCTAssertEqual(
                    d["proof"],
                    AuthProof.client(secret: secret, nonce: nonce, clientNonce: clientNonce).base64EncodedString()
                )
                let proof = AuthProof.server(secret: secret, nonce: nonce, clientNonce: clientNonce)
                return [ok(req, ["proof": proof.base64EncodedString()])]
            default:
                return []
            }
        }

        let client = DeviceClient(transport: device)
        let hello = try await client.hello()
        XCTAssertTrue(hello.paired)
        try await client.authenticate(secret: secret, hello: hello)
    }

    func testAuthRejectsADeviceThatCannotProveItself() async throws {
        let device = FakeDevice()
        device.respond = { req in
            switch req["op"] as! String {
            case "hello":
                return [ok(req, ["v": 1, "nonce": Data(count: 16).base64EncodedString(), "authRequired": true,
                                 "paired": true, "now": "2026-11-01T12:00:00.000Z", "maxMessage": 4096])]
            default:
                return [ok(req, ["proof": Data(count: 32).base64EncodedString()])]
            }
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

    func testDeviceErrorsAreSurfaced() async throws {
        let device = FakeDevice()
        device.respond = { req in
            [["id": req["id"]!, "ok": false,
              "err": ["code": "invalid_config", "msg": "is not a valid date", "field": "attempts[0].time"]]]
        }
        let client = DeviceClient(transport: device)
        do {
            _ = try await client.status()
            XCTFail("expected failure")
        } catch let error as DeviceError {
            XCTAssertEqual(error.code, "invalid_config")
            XCTAssertEqual(error.field, "attempts[0].time")
        }
    }

    func testJobsAreFetchedAcrossPages() async throws {
        let device = FakeDevice()
        func job(_ i: Int) -> [String: Any] {
            ["id": "j\(i)", "name": "A\(i)", "camera": "generic", "wakeUpTime": "2026-11-02T17:38:35.000Z",
             "recordTime": "2026-11-02T17:38:40.000Z", "launchTime": "2026-11-02T17:39:00.000Z",
             "stopTime": "2026-11-02T17:39:30.000Z", "state": "pending"]
        }
        device.respond = { req in
            let d = req["d"] as! [String: Int]
            let offset = d["offset"]!
            let page = (offset ..< min(offset + 10, 25)).map(job)
            return [ok(req, ["jobs": page, "total": 25, "more": offset + page.count < 25])]
        }
        let client = DeviceClient(transport: device)
        let jobs = try await client.jobs()
        XCTAssertEqual(jobs.count, 25)
        XCTAssertEqual(device.requests.count, 3)
    }

    func testEventsAreDecoded() async throws {
        let device = FakeDevice()
        let client = DeviceClient(transport: device)
        var iterator = client.events.makeAsyncIterator()

        device.send(["ev": "job", "d": ["name": "A", "step": "wake", "at": "2026-11-02T17:38:35.000Z"]])
        device.send(["ev": "test", "d": ["step": "done", "ok": true]])
        device.send(["ev": "log", "d": ["seq": 4, "ts": "2026-11-02T17:38:35.000Z", "ns": "INDEX", "msg": "hi"]])
        device.send(["ev": "config", "d": ["configRev": 9]])

        let first = await iterator.next()
        XCTAssertEqual(first, .job(JobEvent(name: "A", step: "wake", at: "2026-11-02T17:38:35.000Z")))
        let second = await iterator.next()
        XCTAssertEqual(second, .test(TestEvent(step: "done", ok: true, error: nil)))
        guard case .log(let entry)? = await iterator.next() else { return XCTFail("log") }
        XCTAssertEqual(entry.msg, "hi")
        let fourth = await iterator.next()
        XCTAssertEqual(fourth, .config(revision: 9))
    }

    func testClockSyncSetsTheDeviceClockOnlyWhenItDrifts() async throws {
        let device = FakeDevice()
        var drift: TimeInterval = 3
        device.respond = { req in
            switch req["op"] as! String {
            case "time.get":
                return [ok(req, ["now": DeviceDate.string(Date().addingTimeInterval(drift))])]
            case "time.set":
                drift = 0
                return [ok(req, ["now": DeviceDate.string(Date()), "offsetMs": 3000])]
            default:
                return []
            }
        }
        let client = DeviceClient(transport: device)

        let first = try await client.syncClock()
        XCTAssertTrue(first.applied)
        XCTAssertEqual(first.offset, 3, accuracy: 0.5)

        let second = try await client.syncClock()
        XCTAssertFalse(second.applied)
        XCTAssertEqual(device.requests.filter { $0["op"] as? String == "time.set" }.count, 1)
    }

    func testRequestsFailWhenTheLinkDrops() async throws {
        let device = FakeDevice() // never answers
        let client = DeviceClient(transport: device)
        Task { try? await Task.sleep(nanoseconds: 50_000_000); device.disconnect() }
        do {
            _ = try await client.status()
            XCTFail("expected failure")
        } catch let error as DeviceError {
            XCTAssertEqual(error, DeviceError.disconnected)
        }
    }
}
