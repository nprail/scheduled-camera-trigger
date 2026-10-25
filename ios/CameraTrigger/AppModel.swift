import SwiftUI
import Observation
import CoreBluetooth
import CameraTriggerKit

@Observable
@MainActor
final class AppModel {
    enum Link: Equatable {
        case disconnected
        case connecting(String)
        case needsPairing(reason: String?)
        case ready
    }

    // MARK: Observable state

    var link: Link = .disconnected
    var bluetooth: CBManagerState = .unknown
    var devices: [DiscoveredDevice] = []

    var info: DeviceInfo?
    var live: StatusValue?
    var status: StatusInfo?
    var config: CameraConfig?
    var jobs: [Job] = []
    var logs: [LogEntry] = []
    var clock: ClockSync?

    var testSteps: [String] = []
    var testRunning = false
    var working = false
    /// Last error to show the user.
    var banner: String?

    // MARK: Plumbing

    @ObservationIgnored private let central = BLECentral()
    @ObservationIgnored private var connection: BLEDeviceConnection?
    @ObservationIgnored private var client: DeviceClient?
    @ObservationIgnored private var currentId: UUID?
    @ObservationIgnored private var listeners: [Task<Void, Never>] = []

    init() {
        central.onStateChange = { [weak self] state in
            guard let self else { return }
            self.bluetooth = state
            if state == .poweredOn, self.link == .disconnected { self.central.startScan() }
        }
        central.onDevices = { [weak self] devices in self?.devices = devices }
        central.onDisconnect = { [weak self] id, _ in
            guard let self, id == self.currentId else { return }
            self.connectionLost()
        }
    }

    func isPaired(_ device: DiscoveredDevice) -> Bool {
        guard let id = device.deviceId else { return false }
        return SecretStore.load(deviceId: id) != nil
    }

    // MARK: Scanning and connecting

    func startScanning() {
        if bluetooth == .poweredOn { central.startScan() }
    }

    func connect(_ device: DiscoveredDevice) {
        guard link == .disconnected else { return }
        link = .connecting(device.name)
        banner = nil
        central.stopScan()

        Task { [self] in
            do {
                let connection = try await central.connect(device.id)
                self.connection = connection
                self.currentId = device.id

                let info = try await connection.readDeviceInfo()
                guard info.v == 1 else {
                    throw DeviceError(
                        code: "unsupported",
                        message: "This device uses protocol v\(info.v). Update the app."
                    )
                }
                self.info = info

                let client = DeviceClient(transport: connection)
                self.client = client
                listen(client: client, connection: connection)

                let hello = try await client.hello()
                if hello.paired, let secret = SecretStore.load(deviceId: info.id) {
                    do {
                        try await client.authenticate(secret: secret, hello: hello)
                        try await becomeReady()
                    } catch let error as DeviceError where error.code == "unauthorized" {
                        link = .needsPairing(reason: error.localizedDescription)
                    }
                } else {
                    link = .needsPairing(
                        reason: hello.paired ? nil : "This device hasn't been paired with a phone yet."
                    )
                }
            } catch {
                fail(error)
            }
        }
    }

    /// Create a pairing key and store it on the device (button must have been pressed).
    func pair() {
        guard let client, let info else { return }
        working = true
        Task { [self] in
            defer { working = false }
            do {
                let secret = AuthProof.randomBytes(16)
                try await client.pair(secret: secret)
                try SecretStore.save(secret, deviceId: info.id)
                try await becomeReady()
            } catch {
                link = .needsPairing(reason: (error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
            }
        }
    }

    func disconnect() {
        if let id = currentId { central.disconnect(id) }
        teardown()
        central.startScan()
    }

    /// Forget the stored key. The device's button re-opens pairing.
    func forgetPairing() {
        if let id = info?.id { SecretStore.delete(deviceId: id) }
        disconnect()
    }

    private func becomeReady() async throws {
        guard let client, let connection else { return }

        clock = try? await client.syncClock()
        config = try await client.config()
        status = try await client.status()
        jobs = try await client.jobs()
        if let data = try? await connection.readStatus() { live = StatusValue(data: data) }
        link = .ready

        await loadLogs()
    }

    private func loadLogs() async {
        guard let client else { return }
        var all: [LogEntry] = []
        var after = 0
        for _ in 0 ..< 12 {
            guard let page = try? await client.logs(after: after, limit: 100) else { break }
            all.append(contentsOf: page.entries)
            after = page.last
            if !page.more { break }
        }
        logs = Array(all.suffix(500))
        try? await client.subscribeLogs(true, after: logs.last?.seq ?? after)
    }

    // MARK: Live updates

    private func listen(client: DeviceClient, connection: BLEDeviceConnection) {
        listeners.forEach { $0.cancel() }
        listeners = [
            Task { [weak self] in
                for await event in client.events { self?.handle(event) }
            },
            Task { [weak self] in
                for await data in connection.statusUpdates { self?.handleStatus(data) }
            },
        ]
    }

    private func handleStatus(_ data: Data) {
        guard let value = StatusValue(data: data) else { return }
        let previous = live
        let configChanged = config?.configRev.map { UInt32($0) != value.configRev } ?? false
        live = value

        // Status notifications can arrive every second while recording; only
        // re-fetch the richer JSON status when something meaningful changed.
        let changed = previous.map {
            $0.phase != value.phase || $0.flags != value.flags || $0.nextEventAt != value.nextEventAt
        } ?? true
        guard link == .ready, changed || configChanged else { return }

        Task { [self] in
            status = try? await client?.status()
            if configChanged { await refreshSchedule() }
        }
    }

    private func handle(_ event: DeviceEvent) {
        switch event {
        case .job:
            Task { [self] in await refreshJobs() }
        case .config:
            Task { [self] in await refreshSchedule() }
        case .test(let t):
            if t.step == "done" {
                testRunning = false
                testSteps.append(t.ok == true ? "Done" : "Failed: \(t.error ?? "unknown error")")
                if t.ok != true { banner = t.error ?? "The test failed." }
            } else {
                testSteps.append(t.step.capitalized)
            }
        case .log(let entry):
            guard !logs.contains(where: { $0.seq == entry.seq }) else { return }
            logs.append(entry)
            if logs.count > 500 { logs.removeFirst(logs.count - 500) }
        }
    }

    private func refreshJobs() async {
        if let jobs = try? await client?.jobs() { self.jobs = jobs }
        status = try? await client?.status()
    }

    private func refreshSchedule() async {
        if let config = try? await client?.config() { self.config = config }
        await refreshJobs()
    }

    // MARK: Actions

    private func perform(_ work: @escaping (DeviceClient) async throws -> Void) {
        guard let client else { return }
        Task { [self] in
            do { try await work(client) } catch { banner = describe(error) }
        }
    }

    func runTest() {
        testSteps = []
        testRunning = true
        perform { [self] client in
            do { try await client.runTest() } catch {
                testRunning = false
                throw error
            }
        }
    }

    func camera(_ action: CameraAction) {
        perform { [self] client in
            try await client.camera(action)
            status = try? await client.status()
        }
    }

    func setWifi(_ enabled: Bool) {
        perform { [self] client in
            try await client.setWifi(enabled)
            status = try? await client.status()
        }
    }

    func syncClock() {
        perform { [self] client in
            clock = try await client.syncClock(threshold: 0)
        }
    }

    /// Returns nil on success, or an error message to show in the editor.
    func saveConfig(_ draft: CameraConfig) async -> String? {
        guard let client else { return "Not connected." }
        do {
            try await client.setConfig(draft)
            await refreshSchedule()
            return nil
        } catch {
            return describe(error)
        }
    }

    // MARK: Errors and teardown

    func describe(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }

    private func fail(_ error: Error) {
        let message = describe(error)
        if let id = currentId { central.disconnect(id) }
        teardown()
        banner = message
        central.startScan()
    }

    private func connectionLost() {
        teardown()
        banner = "The connection to the device was lost."
        central.startScan()
    }

    private func teardown() {
        listeners.forEach { $0.cancel() }
        listeners = []
        client = nil
        connection = nil
        currentId = nil
        info = nil
        live = nil
        status = nil
        config = nil
        jobs = []
        logs = []
        clock = nil
        testSteps = []
        testRunning = false
        link = .disconnected
    }
}
