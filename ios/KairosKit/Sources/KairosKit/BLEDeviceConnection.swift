import Foundation
import CoreBluetooth

/// One connected peripheral: the GATT characteristics behind a `DeviceTransport`.
public final class BLEDeviceConnection: NSObject, DeviceTransport, CBPeripheralDelegate {
    public let peripheral: CBPeripheral

    /// Response characteristic notifications (framed protocol messages).
    public let notifications: AsyncStream<Data>
    /// Status characteristic notifications (12-byte values).
    public let statusUpdates: AsyncStream<Data>

    private let notificationSink: AsyncStream<Data>.Continuation
    private let statusSink: AsyncStream<Data>.Continuation

    private var infoChar: CBCharacteristic?
    private var statusChar: CBCharacteristic?
    private var commandChar: CBCharacteristic?
    private var responseChar: CBCharacteristic?

    private var stepContinuation: CheckedContinuation<Void, Error>?
    private var writeContinuation: CheckedContinuation<Void, Error>?
    private var readContinuations: [CBUUID: CheckedContinuation<Data, Error>] = [:]
    private var closed = false

    init(peripheral: CBPeripheral) {
        self.peripheral = peripheral
        let notifications = AsyncStream.makeStream(of: Data.self)
        self.notifications = notifications.stream
        notificationSink = notifications.continuation
        let status = AsyncStream.makeStream(of: Data.self)
        statusUpdates = status.stream
        statusSink = status.continuation
        super.init()
    }

    /// Largest write that fits a single ATT packet (MTU - 3).
    public var maxWriteLength: Int {
        max(peripheral.maximumWriteValueLength(for: .withoutResponse), 20)
    }

    // MARK: Setup

    @MainActor
    func prepare() async throws {
        peripheral.delegate = self

        try await step { peripheral.discoverServices([BLEUUID.service]) }
        guard let service = peripheral.services?.first(where: { $0.uuid == BLEUUID.service }) else {
            throw DeviceError(code: "not_found", message: "This isn't a Kairos device (service not found).")
        }

        let wanted = [BLEUUID.deviceInfo, BLEUUID.status, BLEUUID.command, BLEUUID.response]
        try await step { peripheral.discoverCharacteristics(wanted, for: service) }

        for c in service.characteristics ?? [] {
            switch c.uuid {
            case BLEUUID.deviceInfo: infoChar = c
            case BLEUUID.status: statusChar = c
            case BLEUUID.command: commandChar = c
            case BLEUUID.response: responseChar = c
            default: break
            }
        }
        guard let statusChar, let responseChar, infoChar != nil, commandChar != nil else {
            throw DeviceError(code: "not_found", message: "The device is missing expected characteristics.")
        }

        try await step { peripheral.setNotifyValue(true, for: responseChar) }
        try await step { peripheral.setNotifyValue(true, for: statusChar) }
    }

    @MainActor
    private func step(_ start: () -> Void) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            stepContinuation = c
            start()
        }
    }

    private func finishStep(_ error: Error?) {
        let c = stepContinuation
        stepContinuation = nil
        if let error { c?.resume(throwing: error) } else { c?.resume() }
    }

    // MARK: Reads

    @MainActor
    private func read(_ characteristic: CBCharacteristic?) async throws -> Data {
        guard let characteristic, !closed else { throw DeviceError.disconnected }
        return try await withCheckedThrowingContinuation { (c: CheckedContinuation<Data, Error>) in
            readContinuations[characteristic.uuid] = c
            peripheral.readValue(for: characteristic)
        }
    }

    @MainActor
    public func readDeviceInfo() async throws -> DeviceInfo {
        let data = try await read(infoChar)
        guard let info = DeviceInfo(data: data) else {
            throw DeviceError(code: "bad_request", message: "The device sent unreadable identity data.")
        }
        return info
    }

    @MainActor
    public func readStatus() async throws -> Data {
        try await read(statusChar)
    }

    // MARK: DeviceTransport

    @MainActor
    public func write(_ data: Data) async throws {
        guard let commandChar, !closed else { throw DeviceError.disconnected }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            writeContinuation = c
            peripheral.writeValue(data, for: commandChar, type: .withResponse)
        }
    }

    // MARK: Teardown

    func handleDisconnect() {
        closed = true
        finishStep(DeviceError.disconnected)
        let write = writeContinuation
        writeContinuation = nil
        write?.resume(throwing: DeviceError.disconnected)
        let reads = readContinuations
        readContinuations.removeAll()
        for (_, c) in reads { c.resume(throwing: DeviceError.disconnected) }
        notificationSink.finish()
        statusSink.finish()
    }

    // MARK: CBPeripheralDelegate

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        finishStep(error.map(Self.friendly))
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        finishStep(error.map(Self.friendly))
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        finishStep(error.map(Self.friendly))
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        let c = writeContinuation
        writeContinuation = nil
        if let error { c?.resume(throwing: Self.friendly(error)) } else { c?.resume() }
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        // a pending explicit read takes the value; otherwise it's a notification
        if let c = readContinuations.removeValue(forKey: characteristic.uuid) {
            if let error {
                c.resume(throwing: Self.friendly(error))
            } else {
                c.resume(returning: characteristic.value ?? Data())
            }
            return
        }
        guard error == nil, let value = characteristic.value else { return }

        switch characteristic.uuid {
        case BLEUUID.response: notificationSink.yield(value)
        case BLEUUID.status: statusSink.yield(value)
        default: break
        }
    }

    /// Turn CoreBluetooth's pairing failures into something actionable.
    private static func friendly(_ error: Error) -> Error {
        let ns = error as NSError
        if ns.domain == CBATTErrorDomain,
           ns.code == CBATTError.insufficientEncryption.rawValue
            || ns.code == CBATTError.insufficientAuthentication.rawValue
        {
            return DeviceError(
                code: "encryption",
                message: "iOS couldn't pair with the device. If it was paired before, remove it under Settings › Bluetooth, then try again."
            )
        }
        return error
    }
}
