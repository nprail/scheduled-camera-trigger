import SwiftUI
import KairosKit

// Updating the device's firmware over Bluetooth (docs/ota-plan.md). An update is always the user's choice: the
// app may look for a newer release when it connects, but that only ever shows a badge.

enum FirmwareStage: Equatable {
    case idle
    case checking
    case downloading
    case sending(Double)
    case restarting
    case reconnecting
    case done(String)
    case rolledBack(String)
    case failed(String)
}

/// A release the check found.
struct FirmwareOffer: Equatable {
    var version: String
    var notes: String?
    var image: ReleaseManifest.Image
}

/// An image ready to send.
struct PreparedFirmware: Equatable {
    var title: String
    /// The version in the image's tag, when it has one.
    var version: String?
    var fileName: String?
    var data: Data
    /// 64 bytes, or empty when unsigned.
    var signature: Data
    var sha256: String

    var isSigned: Bool { signature.count == 64 }
}

struct FirmwareState: Equatable {
    var stage: FirmwareStage = .idle
    var offer: FirmwareOffer?
    /// What the last check said, for the Firmware screen.
    var message: String?
    var prepared: PreparedFirmware?
    var releases: [Release]?
    var loadingReleases = false
}

enum InstallPhase { case none, transferring, restarting }

extension AppModel {
    // MARK: State

    var canUpdate: Bool { info?.canUpdate == true }

    var firmwareBusy: Bool {
        switch firmware.stage {
        case .idle, .done, .rolledBack, .failed: return false
        default: return true
        }
    }

    /// Why an install cannot start now, if it cannot (the device refuses too, but say it before sending).
    var installBlocker: String? {
        if (live?.phase ?? .idle) != .idle { return "The device must be idle to take an update." }
        if let at = live?.nextEventDate, at.timeIntervalSinceNow < 15 * 60 {
            return "An event is scheduled within 15 minutes. Try again after it."
        }
        return nil
    }

    var checkOnConnectEnabled: Bool {
        get { updateStore.checkOnConnect }
        set { updateStore.checkOnConnect = newValue }
    }

    var includePrereleases: Bool {
        get { updateStore.includePrereleases }
        set {
            updateStore.includePrereleases = newValue
            firmware.releases = nil
        }
    }

    // MARK: Looking for updates

    /// Called when the device becomes ready. It never blocks, never shows an error, and only sets the offer.
    func checkForUpdatesQuietly() {
        guard canUpdate, checkOnConnectEnabled else { return }
        Task { [self] in await runCheck(force: false, report: false) }
    }

    /// The Check for updates button: ignores the 10-minute window and says what it found.
    func checkForUpdates() {
        guard canUpdate, !firmwareBusy else { return }
        Task { [self] in await runCheck(force: true, report: true) }
    }

    private func runCheck(force: Bool, report: Bool) async {
        guard let chip = info?.chip, let fw = info?.fw else { return }
        if report {
            firmware.stage = .checking
            firmware.message = nil
        }
        do {
            let (manifest, _) = try await updateService.latest(force: force)
            let result = manifest.assess(chip: chip, deviceFw: fw, appProtocol: Int(protocolVersion))
            guard info?.fw == fw else { return }  // the device changed while we were looking
            if result.status == .available, let image = result.image, let version = manifest.version {
                firmware.offer = FirmwareOffer(version: version, notes: manifest.notes, image: image)
            } else {
                firmware.offer = nil
            }
            if report { firmware.message = Self.checkText(result.status, manifest: manifest, deviceFw: fw, chip: chip) }
        } catch {
            if report { firmware.message = describe(error) }
        }
        if report, firmware.stage == .checking { firmware.stage = .idle }
    }

    static func checkText(_ status: ReleaseManifest.Status, manifest: ReleaseManifest, deviceFw: String, chip: Chip) -> String {
        let v = manifest.version ?? "?"
        switch status {
        case .available: return "Version \(v) is available."
        case .upToDate: return "Up to date (\(deviceFw))."
        case .newerInstalled: return "This device runs a newer version (\(deviceFw)) than the latest release (\(v))."
        case .unknownCurrent: return "This device runs a development build (\(deviceFw)), so it can't be compared with the latest release (\(v))."
        case .appTooOld: return "Version \(v) needs a newer version of this app. Update the app first."
        case .needsUsb: return "Version \(v) can't be installed over Bluetooth on this device. It needs a USB flash."
        case .unavailable: return "The latest release has no firmware for this device (\(chip.title))."
        case .invalid: return "The release information from GitHub could not be read."
        }
    }

    // MARK: Getting an image

    /// Download the offered release (or take it from the cache) and make it ready to install.
    func downloadOffer() {
        guard let offer = firmware.offer, !firmwareBusy else { return }
        Task { [self] in await prepare(version: offer.version, image: offer.image) }
    }

    private func prepare(version: String, image: ReleaseManifest.Image) async {
        guard let sha = image.sha256, let size = image.size, let signature = image.signature else { return }
        firmware.message = nil
        do {
            var data = firmwareCache.image(sha256: sha, size: size)
            if data == nil {
                firmware.stage = .downloading
                data = try await updateService.download(image, version: version)
                firmwareCache.store(data!, sha256: sha)
            }
            if let slot = info?.slot, data!.count > slot { throw DeviceError(code: "too_large", message: "That firmware is too large for this device.") }
            firmware.prepared = PreparedFirmware(
                title: "Version \(version)", version: version, fileName: image.file, data: data!, signature: signature, sha256: sha)
            firmware.stage = .idle
        } catch {
            firmware.stage = .idle
            firmware.message = describe(error)
        }
    }

    /// Releases for the Advanced list.
    func loadReleases() {
        guard firmware.releases == nil, !firmware.loadingReleases else { return }
        firmware.loadingReleases = true
        Task { [self] in
            defer { firmware.loadingReleases = false }
            do {
                firmware.releases = try await updateService.releases(includePrereleases: includePrereleases)
            } catch {
                firmware.message = describe(error)
            }
        }
    }

    /// Pick an older or other release from the list, check it against this device, and download it.
    func chooseRelease(_ release: Release) {
        guard let chip = info?.chip, let fw = info?.fw, !firmwareBusy else { return }
        firmware.stage = .checking
        firmware.message = nil
        Task { [self] in
            do {
                let manifest = try await updateService.manifest(for: release)
                let result = manifest.assess(chip: chip, deviceFw: fw, appProtocol: Int(protocolVersion))
                switch result.status {
                case .invalid, .unavailable, .appTooOld, .needsUsb:
                    firmware.message = Self.checkText(result.status, manifest: manifest, deviceFw: fw, chip: chip)
                    firmware.stage = .idle
                default:
                    guard let image = result.image, let version = manifest.version else { firmware.stage = .idle; return }
                    await prepare(version: version, image: image)
                }
            } catch {
                firmware.stage = .idle
                firmware.message = describe(error)
            }
        }
    }

    /// Files from the picker: a firmware `.bin`, and optionally its `.sig`.
    func importFirmwareFiles(_ urls: [URL]) {
        guard let chip = info?.chip, !firmwareBusy else { return }
        firmware.message = nil
        var image: (name: String, data: Data)?
        var signature: Data?
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else {
                firmware.message = "Could not read \(url.lastPathComponent)."
                return
            }
            if url.pathExtension.lowercased() == "sig" || data.count <= 200 {
                signature = FirmwareImage.signature(from: data)
                if signature == nil { firmware.message = "\(url.lastPathComponent) isn't a valid signature file."; return }
            } else {
                image = (url.lastPathComponent, data)
            }
        }
        if let image {
            let check = FirmwareImage.check(image.data, for: chip)
            guard case .ok(let version) = check else { firmware.message = check.message; return }
            if let slot = info?.slot, image.data.count > slot {
                firmware.message = "That firmware is too large for this device."
                return
            }
            firmware.prepared = PreparedFirmware(
                title: image.name, version: version, fileName: image.name, data: image.data,
                signature: signature ?? Data(), sha256: UpdateService.sha256Hex(image.data))
        } else if let signature, var prepared = firmware.prepared {
            prepared.signature = signature  // a signature added to the file already chosen
            firmware.prepared = prepared
        } else {
            firmware.message = "Choose the firmware file (ending in -app.bin)."
        }
    }

    func discardPrepared() {
        guard !firmwareBusy else { return }
        firmware.prepared = nil
    }

    func dismissFirmwareResult() {
        switch firmware.stage {
        case .done, .rolledBack, .failed: firmware.stage = .idle
        default: break
        }
    }

    // MARK: Installing

    func install() {
        guard let client, let connection, let prepared = firmware.prepared, installTask == nil, !firmwareBusy, installBlocker == nil else { return }
        oldVersion = info?.fw
        firmware.stage = .sending(0)
        firmware.message = nil
        installPhase = .transferring
        keepScreenAwake(true)
        installTask = Task { [self] in
            defer { installTask = nil }
            do {
                let uploader = FirmwareUploader(client: client, transport: connection)
                _ = try await uploader.upload(prepared.data, signature: prepared.signature) { [self] progress in
                    firmware.stage = .sending(progress.fraction)
                }
                // the device now checks the image and restarts, which drops the link: connectionLost() carries on
                firmware.stage = .restarting
                installPhase = .restarting
                try await Task.sleep(nanoseconds: 20_000_000_000)
                if installPhase == .restarting {  // it did not restart
                    installPhase = .none
                    keepScreenAwake(false)
                    firmware.stage = .failed("The device accepted the update but did not restart.")
                }
            } catch is CancellationError {
                if installPhase == .transferring {
                    installPhase = .none
                    keepScreenAwake(false)
                    firmware.stage = .idle
                }
            } catch {
                guard installPhase == .transferring else { return }
                installPhase = .none
                keepScreenAwake(false)
                if (error as? DeviceError)?.code == "disconnected" { return }  // connectionLost() explains it
                firmware.stage = .failed(describe(error))
            }
        }
    }

    func cancelInstall() {
        switch firmware.stage {
        case .sending: installTask?.cancel()
        case .downloading, .checking: break
        default: break
        }
    }

    func keepScreenAwake(_ on: Bool) {
        UIApplication.shared.isIdleTimerDisabled = on
    }

    /// The link dropped after the device took the image: wait for it to come back on the new firmware.
    func reconnectAfterUpdate() {
        guard let id = info?.id else { return }
        installTask?.cancel()
        installPhase = .none
        let expected = firmware.prepared?.version
        let before = oldVersion
        listeners.forEach { $0.cancel() }
        listeners = []
        client = nil
        connection = nil
        currentId = nil
        firmware.stage = .reconnecting

        reconnectTask = Task { [self] in
            central.startScan()
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            let deadline = Date().addingTimeInterval(60)
            var found: DiscoveredDevice?
            while Date() < deadline, found == nil, !Task.isCancelled {
                found = devices.first { $0.deviceId == id }
                if found == nil { try? await Task.sleep(nanoseconds: 500_000_000) }
            }
            keepScreenAwake(false)
            guard let found else {
                fail(DeviceError(code: "timeout", message: "The device didn't come back after the update. Check it, then connect again."))
                return
            }
            central.stopScan()
            do {
                let connection = try await central.connect(found.id)
                self.connection = connection
                currentId = found.id
                let newInfo = try await connection.readDeviceInfo()
                info = newInfo
                let client = DeviceClient(transport: connection)
                self.client = client
                listen(client: client, connection: connection)
                let hello = try await client.hello()
                guard hello.paired, let secret = SecretStore.load(deviceId: newInfo.id) else {
                    throw DeviceError(code: "unauthorized", message: "The device rejected the pairing key. Re-pair using the device's button.")
                }
                try await client.authenticate(secret: secret, hello: hello)
                firmware.offer = nil
                try await becomeReady()
                let ok = expected.map { $0 == newInfo.fw } ?? (newInfo.fw != before)
                firmware.stage = ok ? .done(newInfo.fw) : .rolledBack(newInfo.fw)
                if ok { firmware.prepared = nil }
            } catch {
                fail(error)
            }
        }
    }
}
