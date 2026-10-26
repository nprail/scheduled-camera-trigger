import Foundation

/// What the client needs from a BLE link. Implemented over CoreBluetooth by
/// `BLEDeviceConnection`, and by fakes in tests.
public protocol DeviceTransport: AnyObject {
    /// Largest single write (ATT payload) the link accepts.
    var maxWriteLength: Int { get }
    /// Write one fragment to the Command characteristic, with response.
    func write(_ data: Data) async throws
    /// Fragments notified on the Response characteristic. Finishes on disconnect.
    var notifications: AsyncStream<Data> { get }
}

public enum CameraAction {
    case wake, start, stop, sleep

    var op: Op {
        switch self {
        case .wake: return .cameraWake
        case .start: return .cameraStart
        case .stop: return .cameraStop
        case .sleep: return .cameraSleep
        }
    }
}

/// The protocol version this app speaks.
public let protocolVersion: UInt8 = 2

public struct ClockSync: Equatable {
    /// Device clock minus phone clock, seconds (round-trip compensated).
    public var offset: TimeInterval
    /// Whether the device clock was set.
    public var applied: Bool

    public init(offset: TimeInterval, applied: Bool) {
        self.offset = offset
        self.applied = applied
    }
}

/// Speaks the request/response protocol (docs/ble-protocol.md, section 4) over a
/// `DeviceTransport`. One request is in flight at a time; extra callers wait.
@MainActor
public final class DeviceClient {
    /// Unsolicited events (job steps, test progress, log lines, config changes).
    public let events: AsyncStream<DeviceEvent>

    private let transport: DeviceTransport
    private let eventSink: AsyncStream<DeviceEvent>.Continuation
    private var reassembler = Reassembler()
    private var nextId = 1
    private var pending: [Int: CheckedContinuation<Data, Error>] = [:]
    private var inFlight: Int?
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var readTask: Task<Void, Never>?

    public private(set) var maxMessage = Framing.maxMessage

    public init(transport: DeviceTransport) {
        self.transport = transport
        let stream = AsyncStream.makeStream(of: DeviceEvent.self)
        events = stream.stream
        eventSink = stream.continuation

        readTask = Task { [weak self] in
            guard let notifications = self?.transport.notifications else { return }
            for await fragment in notifications {
                guard let self else { return }
                self.receive(fragment)
            }
            self?.linkClosed()
        }
    }

    deinit {
        readTask?.cancel()
        eventSink.finish()
    }

    // MARK: - Receiving

    private func receive(_ fragment: Data) {
        switch reassembler.push(fragment) {
        case .needMore:
            break
        case .message(let data):
            dispatch(data)
        case .error(let error):
            // Framing errors carry no id; only one request is ever in flight.
            if let id = inFlight {
                resolve(id, .failure(DeviceError(code: "bad_request", message: "Framing error: \(error)")))
            }
        }
    }

    private func dispatch(_ data: Data) {
        switch Wire.parse(data) {
        case .event(let code, let body)?:
            if let event = Wire.decodeEvent(code: code, body: body) { eventSink.yield(event) }
        case .response(let id, let status, _)?:
            if id == 0, status != 0 {
                // an error the device could not attribute to a request (id 0)
                if let current = inFlight { resolve(current, .success(data)) }
            } else {
                resolve(Int(id), .success(data))
            }
        case nil:
            break
        }
    }

    private func resolve(_ id: Int, _ result: Result<Data, Error>) {
        guard let continuation = pending.removeValue(forKey: id) else { return }
        if inFlight == id { inFlight = nil }
        continuation.resume(with: result)
    }

    private func linkClosed() {
        for id in Array(pending.keys) { resolve(id, .failure(DeviceError.disconnected)) }
        eventSink.finish()
    }

    // MARK: - Sending

    private func acquire() async {
        if !busy {
            busy = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func release() {
        if waiters.isEmpty {
            busy = false
        } else {
            waiters.removeFirst().resume()
        }
    }

    /// Send one request and return the body of its response. Throws the device's error for a non-zero status.
    @discardableResult
    func call(_ op: Op, body: Data = Data(), timeout: TimeInterval = 10) async throws -> Data {
        await acquire()
        defer { release() }

        let id = nextId
        nextId = nextId >= 255 ? 1 : nextId + 1  // ids are one byte; 0 is reserved for unattributable errors

        let message = Wire.request(op, id: UInt8(id), body: body)
        guard message.count <= min(maxMessage, Framing.maxMessage) else {
            throw DeviceError(code: "too_large", message: "Request is too large for the device.")
        }
        let frames = Framing.fragments(for: message, maxFragment: max(transport.maxWriteLength, 20))

        let data: Data = try await withCheckedThrowingContinuation { continuation in
            pending[id] = continuation
            inFlight = id

            Task { [weak self] in
                guard let self else { return }
                do {
                    for frame in frames { try await self.transport.write(frame) }
                } catch {
                    self.resolve(id, .failure(error))
                }
            }
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                self?.resolve(id, .failure(DeviceError.timeout))
            }
        }

        guard case .response(_, let status, let responseBody)? = Wire.parse(data) else {
            throw DeviceError(code: "internal", message: "Malformed response.")
        }
        if status != 0 { throw Wire.deviceError(status: status, body: responseBody) }
        return responseBody
    }

    // MARK: - Session

    public func hello() async throws -> HelloResponse {
        let response = try Wire.hello(try await call(.hello))
        guard response.v == Int(protocolVersion) else {
            throw DeviceError(
                code: "unsupported",
                message: "This device speaks protocol v\(response.v); this app supports v\(protocolVersion). Update the app."
            )
        }
        maxMessage = response.maxMessage
        return response
    }

    /// Prove knowledge of `secret` and verify the device does too.
    public func authenticate(secret: Data, hello: HelloResponse) async throws {
        let clientNonce = AuthProof.randomBytes(16)
        let proof = AuthProof.client(secret: secret, nonce: hello.nonce, clientNonce: clientNonce)

        var w = ByteWriter()
        w.data(clientNonce)
        w.data(proof)
        let reply = try await call(.auth, body: Data(w.bytes))

        let expected = AuthProof.server(secret: secret, nonce: hello.nonce, clientNonce: clientNonce)
        guard reply.count >= 32, reply.prefix(32) == expected else {
            throw DeviceError(code: "unauthorized", message: "The device could not prove it holds the pairing key.")
        }
    }

    /// Store a new shared secret on the device. Only works during the pairing window.
    public func pair(secret: Data) async throws {
        try await call(.authPair, body: secret)
    }

    // MARK: - Operations

    public func config() async throws -> CameraConfig {
        try Wire.decodeConfig(try await call(.configGet))
    }

    /// Replace the device config. Returns the new `configRev`.
    @discardableResult
    public func setConfig(_ config: CameraConfig) async throws -> Int {
        var r = ByteReader(try await call(.configSet, body: try Wire.encodeConfig(config)))
        return Int(r.u32())
    }

    /// The state of each attempt in `config`, in order. The launch times come from the config.
    public func jobs(for config: CameraConfig) async throws -> [Job] {
        let states = try Wire.jobStates(try await call(.jobsGet))
        return zip(config.attempts.indices, states).map { index, state in
            Job(index: index, name: config.attempts[index].name, launchDate: config.attempts[index].date, state: state)
        }
    }

    public func camera(_ action: CameraAction) async throws {
        try await call(action.op, timeout: 20)
    }

    public func runTest() async throws {
        try await call(.testRun)
    }

    public func logs(after: Int = 0, limit: Int = 20) async throws -> (entries: [LogEntry], last: Int, more: Bool) {
        var w = ByteWriter()
        w.u32(UInt32(max(0, after)))
        w.u8(UInt8(min(max(limit, 1), 100)))
        return try Wire.logs(try await call(.logsGet, body: Data(w.bytes)))
    }

    /// Start (or stop) streaming log lines as `.log` events, replaying anything after `after`.
    public func subscribeLogs(_ enabled: Bool, after: Int = 0) async throws {
        var w = ByteWriter()
        w.u8(enabled ? 1 : 0)
        w.u32(UInt32(max(0, after)))
        try await call(.logsSubscribe, body: Data(w.bytes))
    }

    public func deviceTime() async throws -> Date {
        Date(timeIntervalSince1970: TimeInterval(try Wire.timeGet(try await call(.timeGet))) / 1000)
    }

    /// Compare clocks (compensating for round-trip delay) and set the device
    /// clock to this phone's if they differ by more than `threshold` seconds.
    /// The phone is the time authority. A busy device is left alone.
    public func syncClock(threshold: TimeInterval = 0.25) async throws -> ClockSync {
        let sent = Date()
        let device = try await deviceTime()
        let received = Date()
        let halfTrip = received.timeIntervalSince(sent) / 2
        let offset = device.timeIntervalSince(sent.addingTimeInterval(halfTrip))

        guard abs(offset) > threshold else { return ClockSync(offset: offset, applied: false) }

        do {
            // the write takes about half a round trip to arrive
            let target = Date().addingTimeInterval(halfTrip)
            var w = ByteWriter()
            w.u64(UInt64(max(0, target.timeIntervalSince1970 * 1000)))
            try await call(.timeSet, body: Data(w.bytes))
            return ClockSync(offset: offset, applied: true)
        } catch let error as DeviceError where error.code == "busy" {
            return ClockSync(offset: offset, applied: false)
        }
    }
}
