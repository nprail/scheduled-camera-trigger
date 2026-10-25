import XCTest
@testable import CameraTriggerKit

final class FramingTests: XCTestCase {
    func testFragmentsMatchReferenceVectors() throws {
        for vector in try Vectors.load().framing {
            let frags = Framing.fragments(for: Data(vector.payload.utf8), maxFragment: vector.maxFragment)
            XCTAssertEqual(frags.map(\.hex), vector.fragments, vector.name)
        }
    }

    func testReferenceVectorsReassemble() throws {
        for vector in try Vectors.load().framing {
            var r = Reassembler()
            var message: Data?
            for hex in vector.fragments {
                if case .message(let m) = r.push(Data(hex: hex)!) { message = m }
            }
            XCTAssertEqual(message, Data(vector.payload.utf8), vector.name)
        }
    }

    func testRoundTripAcrossSizesAndMTUs() {
        for maxFragment in [20, 61, 182, 514] {
            for size in [1, 17, 18, 19, 20, 21, 500, 4096] {
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
        let payload = Data(repeating: 1, count: 4096)
        let frags = Framing.fragments(for: payload, maxFragment: 20)
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
        let secret = Data(hex: a.secretHex)!
        let nonce = Data(base64Encoded: a.nonceB64)!
        let clientNonce = Data(base64Encoded: a.clientNonceB64)!

        XCTAssertEqual(
            AuthProof.client(secret: secret, nonce: nonce, clientNonce: clientNonce).base64EncodedString(),
            a.clientProofB64
        )
        XCTAssertEqual(
            AuthProof.server(secret: secret, nonce: nonce, clientNonce: clientNonce).base64EncodedString(),
            a.serverProofB64
        )
    }
}

final class StatusTests: XCTestCase {
    func testStatusDecodesReferenceVectors() throws {
        for v in try Vectors.load().status {
            let s = try XCTUnwrap(StatusValue(data: Data(hex: v.hex)!))
            XCTAssertEqual(Int(s.flags.rawValue), v.expected.flags)
            XCTAssertEqual(String(describing: s.phase), v.expected.phase)
            XCTAssertEqual(s.configRev, v.expected.configRev)
            XCTAssertEqual(s.nextEventAt, v.expected.nextEventAt)
            XCTAssertEqual(String(describing: s.nextEventType), v.expected.nextEventType)
            XCTAssertEqual(Int(s.attemptsRemaining), v.expected.attemptsRemaining)
        }
    }

    func testFlagsAndShortData() throws {
        let v = try Vectors.load().status[1]
        let s = try XCTUnwrap(StatusValue(data: Data(hex: v.hex)!))
        XCTAssertTrue(s.flags.contains(.recording))
        XCTAssertTrue(s.flags.contains(.pairingOpen))
        XCTAssertNil(s.nextEventDate)
        XCTAssertNil(StatusValue(data: Data([1, 2, 3])))
    }
}

final class ModelTests: XCTestCase {
    func testConfigRoundTripsWithoutLocalIds() throws {
        let json = """
        {"config":{"deviceId":"ABC123","configRev":3,"name":"Pad Cam","cameraType":"generic",
         "wakeUpTimeout":5,"startBefore":20,"endAfter":30,"logFile":"log.txt",
         "generic":{"releaseGpioPin":25},"zcam":{},
         "attempts":[{"name":"Attempt 1","time":"2026-11-02T17:39:00.000Z"}]}}
        """
        let envelope = try JSONDecoder().decode(ConfigEnvelope.self, from: Data(json.utf8))
        XCTAssertEqual(envelope.config.attempts.count, 1)
        XCTAssertEqual(envelope.config.generic.releaseGpioPin, 25)

        let out = try JSONEncoder().encode(envelope.config)
        let text = String(decoding: out, as: UTF8.self)
        XCTAssertFalse(text.contains("\"id\""))
        let again = try JSONDecoder().decode(CameraConfig.self, from: out)
        XCTAssertEqual(again, envelope.config) // attempts compare by name+time, not local id
    }

    func testDeviceDateParsesWithAndWithoutFraction() {
        XCTAssertEqual(DeviceDate.parse("2026-11-02T17:39:00.000Z"), DeviceDate.parse("2026-11-02T17:39:00Z"))
        let d = Date(timeIntervalSince1970: 1_793_554_715.25)
        XCTAssertEqual(DeviceDate.string(d), "2026-11-01T17:38:35.250Z")
    }

    func testErrorMessagesAreFriendly() {
        XCTAssertTrue(DeviceError(code: "pairing_closed", message: "x").localizedDescription.contains("button"))
        XCTAssertEqual(
            DeviceError(code: "invalid_config", message: "is required", field: "logFile").localizedDescription,
            "logFile: is required"
        )
    }
}
