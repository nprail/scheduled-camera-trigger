import XCTest
@testable import KairosKit

final class FramingTests: XCTestCase {
    func testFragmentsMatchReferenceVectors() throws {
        for vector in try Vectors.load().list("framing") {
            let frags = Framing.fragments(for: vector.hexData("payloadHex"), maxFragment: vector.int("maxFragment"))
            XCTAssertEqual(frags.map(\.hex), vector["fragments"] as! [String], vector.str("name"))
        }
    }

    func testReferenceVectorsReassemble() throws {
        for vector in try Vectors.load().list("framing") {
            var r = Reassembler()
            var message: Data?
            for hex in vector["fragments"] as! [String] {
                if case .message(let m) = r.push(Data(hex: hex)!) { message = m }
            }
            XCTAssertEqual(message, vector.hexData("payloadHex"), vector.str("name"))
        }
    }

    func testRoundTripAcrossSizesAndMTUs() {
        for maxFragment in [20, 61, 182, 514] {
            for size in [1, 17, 18, 19, 20, 21, 500, 2048] {
                let payload = Data((0 ..< size).map { UInt8($0 % 251) })
                var r = Reassembler()
                var out: Data?
                for frag in Framing.fragments(for: payload, maxFragment: maxFragment) {
                    XCTAssertLessThanOrEqual(frag.count, maxFragment)
                    if case .message(let m) = r.push(frag) { out = m }
                }
                XCTAssertEqual(out, payload, "maxFragment \(maxFragment) size \(size)")
            }
        }
    }

    func testIndexWrapsAt128() {
        let payload = Data(repeating: 1, count: 2048)
        let frags = Framing.fragments(for: payload, maxFragment: 10)
        XCTAssertGreaterThan(frags.count, 128)
        XCTAssertEqual(frags[128][0], 0)
        var r = Reassembler()
        var out: Data?
        for f in frags { if case .message(let m) = r.push(f) { out = m } }
        XCTAssertEqual(out, payload)
    }

    func testOutOfOrderAndOrphanFragmentsAreErrors() {
        let frags = Framing.fragments(for: Data(repeating: 1, count: 60), maxFragment: 20)
        var r = Reassembler()
        _ = r.push(frags[0])
        XCTAssertEqual(r.push(frags[2]), .error(.badFrame))
        XCTAssertFalse(r.isPending)

        var r2 = Reassembler()
        XCTAssertEqual(r2.push(frags[1]), .error(.badFrame))
    }

    func testOversizedMessagesAreRejectedAndDropped() {
        var r = Reassembler(maxMessage: 100)
        let frags = Framing.fragments(for: Data(repeating: 1, count: 200), maxFragment: 20)
        XCTAssertEqual(r.push(frags[0]), .error(.tooLarge))
        for f in frags.dropFirst() { XCTAssertEqual(r.push(f), .needMore) }
        let ok = Framing.fragments(for: Data("hi".utf8), maxFragment: 20)
        XCTAssertEqual(r.push(ok[0]), .message(Data("hi".utf8)))
    }
}

final class AuthTests: XCTestCase {
    func testProofsMatchReferenceVectors() throws {
        let a = try Vectors.load().auth
        let secret = a.hexData("secretHex"), nonce = a.hexData("nonceHex"), clientNonce = a.hexData("clientNonceHex")
        XCTAssertEqual(AuthProof.client(secret: secret, nonce: nonce, clientNonce: clientNonce).hex, a.str("clientProofHex"))
        XCTAssertEqual(AuthProof.server(secret: secret, nonce: nonce, clientNonce: clientNonce).hex, a.str("serverProofHex"))
    }
}

final class StatusTests: XCTestCase {
    func testStatusDecodesReferenceVectors() throws {
        for v in try Vectors.load().list("status") {
            let e = v.obj("expected")
            let s = try XCTUnwrap(StatusValue(data: v.hexData("hex")))
            XCTAssertEqual(Int(s.flags.rawValue), e.int("flags"))
            XCTAssertEqual(String(describing: s.phase), e.str("phase"))
            XCTAssertEqual(Int(s.configRev), e.int("configRev"))
            XCTAssertEqual(Int(s.nextEventAt), e.int("nextEventAt"))
            XCTAssertEqual(String(describing: s.nextEventType), e.str("nextEventType"))
            XCTAssertEqual(Int(s.attemptsRemaining), e.int("attemptsRemaining"))
            XCTAssertEqual(s.nextAttempt, e.optInt("nextAttempt"))
        }
    }

    func testFlagsAndShortData() throws {
        let v = try Vectors.load().list("status")[1]
        let s = try XCTUnwrap(StatusValue(data: v.hexData("hex")))
        XCTAssertTrue(s.flags.contains(.recording))
        XCTAssertTrue(s.flags.contains(.pairingOpen))
        XCTAssertTrue(s.flags.contains(.clockValid))
        XCTAssertNil(s.nextEventDate)
        XCTAssertNil(s.nextAttempt)
        XCTAssertNil(StatusValue(data: Data([1, 2, 3])))
        XCTAssertNil(StatusValue(data: Data(count: 12))) // the v1 length
    }

    func testDeviceInfoDecodesReferenceVectors() throws {
        for v in try Vectors.load().list("deviceInfo") {
            let e = v.obj("expected")
            let info = try XCTUnwrap(DeviceInfo(data: v.hexData("hex")))
            XCTAssertEqual(
                info,
                DeviceInfo(
                    v: e.int("v"), id: e.str("id"), name: e.str("name"), cam: e.str("cam"), cams: e.strs("cams"), fw: e.str("fw"),
                    caps: e.int("caps"), chip: Chip(rawValue: e.str("chip")), slot: e.int("slot")))
            XCTAssertEqual(info.canUpdate, e.int("caps") & 1 != 0 && e.str("chip") != "none" && e.int("slot") > 0)
        }
        XCTAssertNil(DeviceInfo(data: Data([2, 1, 2])))
    }
}

final class WireTests: XCTestCase {
    func testConfigVectorsEncodeAndDecode() throws {
        for v in try Vectors.load().list("config") {
            let config = configFrom(v.obj("config"))
            XCTAssertEqual(try Wire.encodeConfig(config).hex, v.str("hex"), v.str("name"))
            XCTAssertEqual(try Wire.decodeConfig(v.hexData("hex")), config, v.str("name"))
        }
    }

    func testMalformedConfigsAreRefused() throws {
        let ok = try Vectors.load().list("config")[0].hexData("hex")
        var wrongLayout = ok
        wrongLayout[0] = 9
        XCTAssertThrowsError(try Wire.decodeConfig(wrongLayout))
        XCTAssertThrowsError(try Wire.decodeConfig(ok.dropLast()))
        var wrongType = ok
        wrongType[1] = 7
        XCTAssertThrowsError(try Wire.decodeConfig(wrongType))
    }

    func testConfigEncodingChecksLimitsBeforeSending() {
        var c = CameraConfig()
        c.name = String(repeating: "x", count: 33)
        XCTAssertThrowsError(try Wire.encodeConfig(c)) { XCTAssertEqual(($0 as? DeviceError)?.field, "name") }

        c.name = nil
        c.zcam.cameraIp = "192.168.1"
        XCTAssertThrowsError(try Wire.encodeConfig(c)) { XCTAssertEqual(($0 as? DeviceError)?.field, "zcam.cameraIp") }
        c.zcam.cameraIp = "300.1.1.1"
        XCTAssertThrowsError(try Wire.encodeConfig(c))
        c.zcam.cameraIp = ""
        XCTAssertNoThrow(try Wire.encodeConfig(c))

        c.attempts = (0 ... 30).map { CameraConfig.Attempt(name: "a\($0)", seconds: UInt32($0)) }
        XCTAssertThrowsError(try Wire.encodeConfig(c)) { XCTAssertEqual(($0 as? DeviceError)?.field, "attempts") }
    }

    func testNamesAreCountedInBytes() {
        let c = CameraConfig(name: String(repeating: "🚀", count: 9)) // 36 bytes
        XCTAssertThrowsError(try Wire.encodeConfig(c))
    }

    private func requestBody(_ op: Op, _ f: [String: Any]) throws -> Data {
        var w = ByteWriter()
        switch op {
        case .auth: w.data(f.hexData("clientNonceHex")); w.data(f.hexData("proofHex"))
        case .authPair: w.data(f.hexData("secretHex"))
        case .timeSet: w.u64(f.u64("nowMs"))
        case .configSet: return try Wire.encodeConfig(configFrom(f.obj("config")))
        case .logsGet: w.u32(UInt32(f.int("after"))); w.u8(UInt8(f.int("limit")))
        case .logsSubscribe: w.u8(f.bool("enabled") ? 1 : 0); w.u32(UInt32(f.int("after")))
        case .fwBegin: w.u32(UInt32(f.int("size"))); w.data(f.hexData("sha256Hex")); w.data(f.hexData("sigHex"))
        default: break
        }
        return Data(w.bytes)
    }

    private let ops: [String: Op] = [
        "hello": .hello, "auth": .auth, "auth.pair": .authPair, "time.get": .timeGet, "time.set": .timeSet,
        "config.get": .configGet, "config.set": .configSet, "jobs.get": .jobsGet, "camera.wake": .cameraWake,
        "camera.start": .cameraStart, "camera.stop": .cameraStop, "camera.sleep": .cameraSleep, "test.run": .testRun,
        "logs.get": .logsGet, "logs.subscribe": .logsSubscribe, "fw.begin": .fwBegin, "fw.end": .fwEnd, "fw.abort": .fwAbort,
    ]

    func testRequestVectorsEncode() throws {
        for v in try Vectors.load().list("messages") where v.str("kind") == "request" {
            let op = ops[v.str("op")]!
            let message = Wire.request(op, id: UInt8(v.int("id")), body: try requestBody(op, v.obj("fields")))
            XCTAssertEqual(message.hex, v.str("hex"), v.str("name"))
        }
    }

    func testResponseVectorsDecode() throws {
        for v in try Vectors.load().list("messages") where v.str("kind") == "response" {
            let f = v.obj("fields")
            guard case .response(let id, let status, let body)? = Wire.parse(v.hexData("hex")) else { return XCTFail(v.str("name")) }
            XCTAssertEqual(Int(id), v.int("id"))
            XCTAssertEqual(status, 0)
            switch v.str("op") {
            case "hello":
                let h = try Wire.hello(body)
                XCTAssertEqual(h.v, f.int("v"))
                XCTAssertEqual(h.paired, f.bool("paired"))
                XCTAssertEqual(h.nonce, f.hexData("nonceHex"))
                XCTAssertEqual(h.nowMs, f.u64("nowMs"))
                XCTAssertEqual(h.maxMessage, f.int("maxMessage"))
            case "time.get":
                XCTAssertEqual(try Wire.timeGet(body), f.u64("nowMs"))
            case "time.set":
                var r = ByteReader(body)
                XCTAssertEqual(r.u64(), f.u64("nowMs"))
                XCTAssertEqual(Int(r.i32()), f.int("offsetMs"))
            case "config.get":
                XCTAssertEqual(try Wire.decodeConfig(body), configFrom(f.obj("config")))
            case "config.set":
                var r = ByteReader(body)
                XCTAssertEqual(Int(r.u32()), f.int("configRev"))
            case "jobs.get":
                XCTAssertEqual(try Wire.jobStates(body), (f["states"] as! [String]))
            case "camera.start", "camera.stop":
                var r = ByteReader(body)
                XCTAssertEqual(r.u8() != 0, f.bool("recording"))
            case "logs.get":
                let page = try Wire.logs(body)
                XCTAssertEqual(page.last, f.int("last"))
                XCTAssertEqual(page.more, f.bool("more"))
                XCTAssertEqual(page.entries.map(\.seq), f.array("lines").map { $0.int("seq") })
                XCTAssertEqual(page.entries.map(\.msg), f.array("lines").map { $0.str("msg") })
            case "auth":
                XCTAssertEqual(body, f.hexData("proofHex"))
            case "fw.begin":
                let b = try Wire.firmwareBegin(body)
                XCTAssertEqual(b.offset, f.int("offset"))
                XCTAssertEqual(b.window, f.int("window"))
            case "fw.end":
                XCTAssertEqual(try Wire.firmwareEnd(body), f.int("rebootInMs"))
            default:
                XCTAssertTrue(body.isEmpty, v.str("name"))
            }
        }
    }

    func testEventVectorsDecode() throws {
        for v in try Vectors.load().list("messages") where v.str("kind") == "event" {
            let f = v.obj("fields")
            guard case .event(let code, let body)? = Wire.parse(v.hexData("hex")) else { return XCTFail(v.str("name")) }
            if v.str("ev") == "fw" {
                XCTAssertEqual(code, 0x05)
                let e = try XCTUnwrap(Wire.firmwareEvent(body), v.str("name"))
                XCTAssertEqual(e.state.rawValue, ["receiving", "verifying", "ready", "failed"].firstIndex(of: f.str("state")))
                XCTAssertEqual(e.offset, f.int("offset"))
                XCTAssertEqual(e.reason, f.int("reason"))
                XCTAssertNil(Wire.decodeEvent(code: code, body: body))  // it has its own channel
                continue
            }
            let event = try XCTUnwrap(Wire.decodeEvent(code: code, body: body), v.str("name"))
            switch (v.str("ev"), event) {
            case ("job", .job(let e)):
                XCTAssertEqual(e.attempt, f.int("attempt"))
                XCTAssertEqual(e.step, f.str("step"))
                XCTAssertEqual(Int(e.at.timeIntervalSince1970), f.int("at"))
            case ("config", .config(let rev)):
                XCTAssertEqual(rev, f.int("configRev"))
            case ("test", .test(let e)):
                XCTAssertEqual(e.step, f.str("step"))
                XCTAssertEqual(e.ok, f.bool("ok"))
                XCTAssertEqual(e.error ?? "", f.str("error"))
            case ("log", .log(let l)):
                XCTAssertEqual(l.seq, f.int("seq"))
                XCTAssertEqual(l.ns, f.str("ns"))
                XCTAssertEqual(l.msg, f.str("msg"))
            default:
                XCTFail("wrong event for \(v.str("name"))")
            }
        }
    }

    func testErrorVectorsDecode() throws {
        for v in try Vectors.load().list("errors") {
            let d = v.obj("detail")
            guard case .response(let id, let status, let body)? = Wire.parse(v.hexData("hex")) else { return XCTFail(v.str("name")) }
            XCTAssertEqual(Int(id), v.int("id"))
            let error = Wire.deviceError(status: status, body: body)
            XCTAssertEqual(error.code, v.str("code"), v.str("name"))
            XCTAssertEqual(error.retryAfter, d.optInt("retryAfter"), v.str("name"))
            if v.str("code") == "invalid_config" {
                XCTAssertEqual(error.field, Wire.fieldName(d.int("field"), index: d.int("index")))
                XCTAssertEqual(error.message, Wire.reasonText(d.int("reason")))
            }
            if v.str("code") == "internal", let m = d.optStr("message") { XCTAssertEqual(error.message, m) }
            if v.str("code") == "bad_image" { XCTAssertEqual(error.message, Wire.badImageText(d.int("reason")), v.str("name")) }
        }
    }

    func testAnUnknownEventIsIgnoredAndTrailingBytesAreTolerated() {
        XCTAssertNil(Wire.decodeEvent(code: 0x7E, body: Data()))
        var r = ByteReader(Data([1, 2]))
        _ = r.u32()
        XCTAssertFalse(r.ok)
        XCTAssertNotNil(Wire.decodeEvent(code: 0x02, body: Data([9, 0, 0, 0, 77, 77])))
    }
}

final class ModelTests: XCTestCase {
    func testAttemptsCompareByNameAndTimeNotLocalId() {
        let a = CameraConfig.Attempt(name: "A", seconds: 100)
        let b = CameraConfig.Attempt(name: "A", seconds: 100)
        XCTAssertNotEqual(a.id, b.id)
        XCTAssertEqual(a, b)
        XCTAssertEqual(CameraConfig.Attempt(name: "A", time: Date(timeIntervalSince1970: 100.9)).time, 100)
    }

    func testErrorMessagesAreFriendly() {
        XCTAssertTrue(DeviceError(code: "pairing_closed", message: "x").localizedDescription.contains("button"))
        XCTAssertEqual(
            Wire.deviceError(status: 6, body: Data([12, 2, 4])).localizedDescription,
            "attempts[2].time: must be in the future"
        )
        XCTAssertEqual(Wire.deviceError(status: 3, body: Data([44, 1])).localizedDescription, "Too many failed attempts. Try again in 300 s.")
    }
}
