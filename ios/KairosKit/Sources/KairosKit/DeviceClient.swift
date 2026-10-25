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

public enum CameraAction: String {
    case wake = "camera.wake"
    case start = "camera.start"
    case stop = "camera.stop"
    case sleep = "camera.sleep"
}

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

private struct Request<P: Encodable>: Encodable {
    let id: Int
    let op: String
    let d: P
}

private struct Head: Decodable {
    let id: Int?
    let ok: Bool?
    let ev: String?
    let err: ErrorBody?
}

private struct ResponseEnvelope<T: Decodable>: Decodable { let d: T }
private struct EventEnvelope<T: Decodable>: Decodable { let d: T }

/// Speaks the request/response protocol (docs/BLE_PROTOCOL.md §4) over a
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
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

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
        guard let head = try? decoder.decode(Head.self, from: data) else { return }

        if let ev = head.ev {
            if let event = decodeEvent(named: ev, from: data) { eventSink.yield(event) }
            return
        }

        if let id = head.id {
            resolve(id, .success(data))
        } else if let id = inFlight {
            // error response to a frame the device couldn't attribute (id: null)
            resolve(id, .success(data))
        }
    }

    private func decodeEvent(named name: String, from data: Data) -> DeviceEvent? {
        switch name {
        case "job":
            return (try? decoder.decode(EventEnvelope<JobEvent>.self, from: data)).map { .job($0.d) }
        case "test":
            return (try? decoder.decode(EventEnvelope<TestEvent>.self, from: data)).map { .test($0.d) }
        case "log":
            return (try? decoder.decode(EventEnvelope<LogEntry>.self, from: data)).map { .log($0.d) }
        case "config":
            return (try? decoder.decode(EventEnvelope<ConfigEvent>.self, from: data))
                .map { .config(revision: $0.d.configRev) }
        default:
            return nil
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

    /// Send one request and decode its `d` payload.
    func call<P: Encodable, R: Decodable>(
        _ op: String,
        _ params: P,
        as type: R.Type = R.self,
        timeout: TimeInterval = 10
    ) async throws -> R {
        await acquire()
        defer { release() }

        let id = nextId
        nextId += 1

        let body = try encoder.encode(Request(id: id, op: op, d: params))
        guard body.count <= min(maxMessage, Framing.maxMessage) else {
            throw DeviceError(code: "too_large", message: "Request is too large for the device.")
        }
        let frames = Framing.fragments(for: body, maxFragment: max(transport.maxWriteLength, 20))

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

        let head = try decoder.decode(Head.self, from: data)
        guard head.ok == true else {
            throw head.err?.asError ?? DeviceError(code: "internal", message: "Malformed response.")
        }
        return try decoder.decode(ResponseEnvelope<R>.self, from: data).d
    }

    func call<R: Decodable>(
        _ op: String,
        as type: R.Type = R.self,
        timeout: TimeInterval = 10
    ) async throws -> R {
        try await call(op, Empty(), as: type, timeout: timeout)
    }

    // MARK: - Session

    public func hello() async throws -> HelloResponse {
        let response: HelloResponse = try await call("hello")
        guard response.v == 1 else {
            throw DeviceError(code: "unsupported", message: "This device speaks protocol v\(response.v); this app supports v1.")
        }
        maxMessage = response.maxMessage
        return response
    }

    /// Prove knowledge of `secret` and verify the device does too.
    public func authenticate(secret: Data, hello: HelloResponse) async throws {
        guard let nonce = Data(base64Encoded: hello.nonce) else {
            throw DeviceError(code: "bad_request", message: "Bad nonce from device.")
        }
        let clientNonce = AuthProof.randomBytes(16)
        let proof = AuthProof.client(secret: secret, nonce: nonce, clientNonce: clientNonce)

        struct Params: Encodable { let clientNonce: String; let proof: String }
        let reply: AuthResponse = try await call(
            "auth",
            Params(clientNonce: clientNonce.base64EncodedString(), proof: proof.base64EncodedString())
        )

        let expected = AuthProof.server(secret: secret, nonce: nonce, clientNonce: clientNonce)
        guard Data(base64Encoded: reply.proof) == expected else {
            throw DeviceError(code: "unauthorized", message: "The device could not prove it holds the pairing key.")
        }
    }

    /// Store a new shared secret on the device. Only works during the pairing window.
    public func pair(secret: Data) async throws {
        struct Params: Encodable { let secret: String }
        let _: Empty = try await call("auth.pair", Params(secret: secret.base64EncodedString()))
    }

    // MARK: - Operations

    public func status() async throws -> StatusInfo { try await call("status.get") }

    public func config() async throws -> CameraConfig {
        let envelope: ConfigEnvelope = try await call("config.get")
        return envelope.config
    }

    /// Replace the device config. Returns the new `configRev`.
    @discardableResult
    public func setConfig(_ config: CameraConfig) async throws -> Int {
        let reply: ConfigSetResponse = try await call("config.set", ConfigEnvelope(config: config))
        return reply.configRev
    }

    public func jobs() async throws -> [Job] {
        struct Params: Encodable { let offset: Int; let limit: Int }
        var all: [Job] = []
        while true {
            let page: JobsPage = try await call("jobs.get", Params(offset: all.count, limit: 10))
            all.append(contentsOf: page.jobs)
            if !page.more || page.jobs.isEmpty { break }
        }
        return all
    }

    public func camera(_ action: CameraAction) async throws {
        let _: CameraResponse = try await call(action.rawValue, timeout: 20)
    }

    public func runTest() async throws {
        let _: StartedResponse = try await call("test.run")
    }

    public func logs(after: Int = 0, limit: Int = 20) async throws -> (entries: [LogEntry], last: Int, more: Bool) {
        struct Params: Encodable { let after: Int; let limit: Int }
        let page: LogsPage = try await call("logs.get", Params(after: after, limit: limit))
        return (page.logs, page.last, page.more)
    }

    /// Start (or stop) streaming log lines as `.log` events, replaying anything after `after`.
    public func subscribeLogs(_ enabled: Bool, after: Int? = nil) async throws {
        struct Params: Encodable { let enabled: Bool; let after: Int? }
        let _: Empty = try await call("logs.subscribe", Params(enabled: enabled, after: after))
    }

    public func deviceTime() async throws -> Date {
        let reply: TimeResponse = try await call("time.get")
        return DeviceDate.parse(reply.now) ?? Date()
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

        struct Params: Encodable { let now: String }
        do {
            // the write takes about half a round trip to arrive
            let target = Date().addingTimeInterval(halfTrip)
            let _: TimeResponse = try await call("time.set", Params(now: DeviceDate.string(target)))
            return ClockSync(offset: offset, applied: true)
        } catch let error as DeviceError where error.code == "busy" {
            return ClockSync(offset: offset, applied: false)
        }
    }
}
