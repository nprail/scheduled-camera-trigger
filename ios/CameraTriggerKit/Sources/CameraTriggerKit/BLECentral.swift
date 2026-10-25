import Foundation
import CoreBluetooth

public enum BLEUUID {
    public static let service = CBUUID(string: "7A3C0001-5E9B-4D2A-8F61-C0A7B2D4E9F3")
    public static let deviceInfo = CBUUID(string: "7A3C0002-5E9B-4D2A-8F61-C0A7B2D4E9F3")
    public static let status = CBUUID(string: "7A3C0003-5E9B-4D2A-8F61-C0A7B2D4E9F3")
    public static let command = CBUUID(string: "7A3C0004-5E9B-4D2A-8F61-C0A7B2D4E9F3")
    public static let response = CBUUID(string: "7A3C0005-5E9B-4D2A-8F61-C0A7B2D4E9F3")
}

public struct DiscoveredDevice: Identifiable, Equatable {
    /// CoreBluetooth's peripheral identifier (stable per phone, not the MAC).
    public let id: UUID
    public var name: String
    public var rssi: Int
    public var lastSeen: Date

    /// The device ID from an advertised name like "Pad Cam-ABC123".
    public var deviceId: String? {
        let tail = name.split(separator: "-").last.map(String.init) ?? name
        let isId = tail.count == 6 && tail.allSatisfy { $0.isHexDigit }
        return isId ? tail.uppercased() : nil
    }
}

/// Scans for camera triggers and opens connections. Callbacks arrive on the main queue.
public final class BLECentral: NSObject, CBCentralManagerDelegate {
    public var onStateChange: ((CBManagerState) -> Void)?
    public var onDevices: (([DiscoveredDevice]) -> Void)?
    /// Called when an established connection drops (not for ones the app ended itself).
    public var onDisconnect: ((UUID, Error?) -> Void)?

    public private(set) var state: CBManagerState = .unknown

    private var manager: CBCentralManager!
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var devices: [UUID: DiscoveredDevice] = [:]
    private var connecting: [UUID: CheckedContinuation<Void, Error>] = [:]
    private var connections: [UUID: BLEDeviceConnection] = [:]
    private var pruneTimer: Timer?

    public override init() {
        super.init()
        manager = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: Scanning

    public func startScan() {
        guard manager.state == .poweredOn, !manager.isScanning else { return }
        manager.scanForPeripherals(
            withServices: [BLEUUID.service],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        pruneTimer?.invalidate()
        pruneTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            self?.prune()
        }
    }

    public func stopScan() {
        if manager.isScanning { manager.stopScan() }
        pruneTimer?.invalidate()
        pruneTimer = nil
    }

    private func prune() {
        let cutoff = Date().addingTimeInterval(-8)
        let stale = devices.filter { $0.value.lastSeen < cutoff }.map(\.key)
        guard !stale.isEmpty else { return }
        for id in stale { devices.removeValue(forKey: id) }
        publish()
    }

    private func publish() {
        onDevices?(devices.values.sorted { $0.rssi > $1.rssi })
    }

    // MARK: Connecting

    /// Connect, discover the service and subscribe, ready for a `DeviceClient`.
    @MainActor
    public func connect(_ id: UUID, timeout: TimeInterval = 15) async throws -> BLEDeviceConnection {
        guard let peripheral = peripherals[id] ?? manager.retrievePeripherals(withIdentifiers: [id]).first else {
            throw DeviceError(code: "not_found", message: "The device is no longer in range.")
        }
        let connection = BLEDeviceConnection(peripheral: peripheral)
        connections[id] = connection

        let timeoutWork = DispatchWorkItem { [weak self] in
            guard let self, self.connecting[id] != nil else { return }
            self.manager.cancelPeripheralConnection(peripheral)
            self.connecting.removeValue(forKey: id)?.resume(
                throwing: DeviceError(code: "timeout", message: "Could not connect to the device.")
            )
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout, execute: timeoutWork)

        defer { timeoutWork.cancel() }
        do {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                connecting[id] = c
                manager.connect(peripheral, options: nil)
            }
            try await connection.prepare()
        } catch {
            connections.removeValue(forKey: id)
            manager.cancelPeripheralConnection(peripheral)
            throw error
        }
        return connection
    }

    public func disconnect(_ id: UUID) {
        connections.removeValue(forKey: id)
        if let peripheral = peripherals[id] { manager.cancelPeripheralConnection(peripheral) }
    }

    // MARK: CBCentralManagerDelegate

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        state = central.state
        onStateChange?(state)
        if state != .poweredOn {
            devices.removeAll()
            publish()
        }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?? peripheral.name
            ?? "Camera Trigger"
        peripherals[peripheral.identifier] = peripheral
        devices[peripheral.identifier] = DiscoveredDevice(
            id: peripheral.identifier,
            name: name,
            rssi: RSSI.intValue,
            lastSeen: Date()
        )
        publish()
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connecting.removeValue(forKey: peripheral.identifier)?.resume()
    }

    public func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        let failure = error ?? DeviceError(code: "connect", message: "Could not connect to the device.")
        connecting.removeValue(forKey: peripheral.identifier)?.resume(throwing: failure)
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        let id = peripheral.identifier
        let known = connections.removeValue(forKey: id)
        known?.handleDisconnect()
        connecting.removeValue(forKey: id)?.resume(throwing: error ?? DeviceError.disconnected)
        if known != nil { onDisconnect?(id, error) }
    }
}
