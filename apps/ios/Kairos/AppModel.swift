import SwiftUI
import Observation
import CoreBluetooth
import KairosKit

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
    var config: CameraConfig?
    var jobs: [Job] = []
    var logs: [LogEntry] = []
    var clock: ClockSync?
    /// The Status revision of the config this model last fetched.
    @ObservationIgnored private var configRev: UInt32?

    var firmware = FirmwareState()

    var testSteps: [String] = []
    var testRunning = false
    var working = false
    /// Last error to show the user.
    var banner: String?

    // MARK: Plumbing

    @ObservationIgnored let central = BLECentral()
    @ObservationIgnored var connection: BLEDeviceConnection?
    @ObservationIgnored var client: DeviceClient?
    @ObservationIgnored var currentId: UUID?
    @ObservationIgnored var listeners: [Task<Void, Never>] = []

    // Firmware updates (AppModel+Firmware.swift)
    @ObservationIgnored let updateStore = UserDefaultsUpdateStore()
    @ObservationIgnored lazy var updateService = UpdateService(store: updateStore)
    @ObservationIgnored lazy var firmwareCache = FirmwareCache(
        directory: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Firmware"))
    @ObservationIgnored var installTask: Task<Void, Never>?
    @ObservationIgnored var reconnectTask: Task<Void, Never>?
    @ObservationIgnored var installPhase: InstallPhase = .none
    @ObservationIgnored var oldVersion: String?

    #if DEBUG
    /// True when the debug demo fixtures are driving the screens (no Bluetooth).
    var demo = false
    /// `-screen config|logs|firmware`: open that screen on the device screen (for screenshots).
    var demoScreen: String?
    #endif

    init() {
        attachCentral()
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-demo"), i + 1 < args.count {
            applyDemo(args[i + 1])
            if let s = args.firstIndex(of: "-screen"), s + 1 < args.count { demoScreen = args[s + 1] }
        }
        #endif
    }

    private func attachCentral() {
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
        #if DEBUG
        if demo { return }
        #endif
        if bluetooth == .poweredOn { central.startScan() }
    }

    #if DEBUG
    /// Leave the debug demo and go back to the real Bluetooth stack.
    func exitDemo() {
        guard demo else { return }
        demo = false
        teardown()
        banner = nil
        devices = []
        attachCentral()
        bluetooth = central.state
        startScanning()
    }

    /// Detach the real Bluetooth stack so fixtures can drive the screens.
    func enterDemo() {
        demo = true
        central.stopScan()
        central.onStateChange = nil
        central.onDevices = nil
        central.onDisconnect = nil
        teardown()
        devices = []
    }
    #endif

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
                guard info.v == Int(protocolVersion) else {
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
        #if DEBUG
        if demo { return }
        #endif
        central.startScan()
    }

    /// Forget the stored key. The device's button re-opens pairing.
    func forgetPairing() {
        if let id = info?.id { SecretStore.delete(deviceId: id) }
        disconnect()
    }

    func becomeReady() async throws {
        guard let client, let connection else { return }

        clock = try? await client.syncClock()
        if let data = try? await connection.readStatus() { live = StatusValue(data: data) }
        let config = try await client.config()
        self.config = config
        configRev = live?.configRev
        jobs = try await client.jobs(for: config)
        link = .ready

        checkForUpdatesQuietly()
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

    func listen(client: DeviceClient, connection: BLEDeviceConnection) {
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
        live = value

        // Status carries everything the screens show; only a new config revision needs a fetch.
        guard link == .ready, let known = configRev, known != value.configRev else { return }
        Task { [self] in await refreshSchedule() }
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
                testSteps.append(t.ok ? "Done" : "Failed: \(t.error ?? "unknown error")")
                if !t.ok { banner = t.error ?? "The test failed." }
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
        guard let config, let jobs = try? await client?.jobs(for: config) else { return }
        self.jobs = jobs
    }

    private func refreshSchedule() async {
        if let config = try? await client?.config() {
            self.config = config
            configRev = live?.configRev
        }
        await refreshJobs()
    }

    /// The name of the attempt that owns the next event.
    var nextAttemptName: String? {
        guard let i = live?.nextAttempt, let attempts = config?.attempts, attempts.indices.contains(i) else { return nil }
        return attempts[i].name
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
            if let data = try? await connection?.readStatus() { live = StatusValue(data: data) }
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

    func fail(_ error: Error) {
        let message = describe(error)
        if let id = currentId { central.disconnect(id) }
        teardown()
        banner = message
        central.startScan()
    }

    private func connectionLost() {
        if installPhase == .restarting {
            reconnectAfterUpdate()  // expected: the device restarts to run the new firmware
            return
        }
        let duringUpdate = installPhase == .transferring
        teardown()
        banner = duringUpdate
            ? "The connection was lost during the update. The device keeps what it received for 2 minutes: connect again and install the same firmware to continue."
            : "The connection to the device was lost."
        central.startScan()
    }

    func teardown() {
        installTask?.cancel()
        installTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        installPhase = .none
        keepScreenAwake(false)
        firmware = FirmwareState()
        listeners.forEach { $0.cancel() }
        listeners = []
        client = nil
        connection = nil
        currentId = nil
        info = nil
        live = nil
        config = nil
        configRev = nil
        jobs = []
        logs = []
        clock = nil
        testSteps = []
        testRunning = false
        link = .disconnected
    }
}
