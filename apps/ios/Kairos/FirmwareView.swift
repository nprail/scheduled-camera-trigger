import SwiftUI
import UniformTypeIdentifiers
import KairosKit

/// Firmware updates (docs/ota-plan.md). Nothing here starts on its own: every download and install is a tap.
struct FirmwareView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmingInstall = false
    @State private var choosingFile = false
    @State private var choosingVersion = false

    var body: some View {
        List {
            currentSection
            switch model.firmware.stage {
            case .idle, .checking:
                updateSection
                fileSection
                advancedSection
            case .downloading:
                progressSection("Downloading…", value: nil, cancellable: false)
            case .sending(let fraction):
                progressSection("Sending…", value: fraction, cancellable: true)
            case .restarting:
                progressSection("Restarting the device…", value: nil, cancellable: false)
            case .reconnecting:
                progressSection("Reconnecting…", value: nil, cancellable: false)
            case .done(let version):
                resultSection(icon: "checkmark.circle", title: "Updated to \(version).", detail: "Pairing and the schedule are unchanged.")
            case .rolledBack(let version):
                resultSection(
                    icon: "arrow.uturn.backward.circle", title: "The update was rolled back.",
                    detail: "The device restarted on its previous firmware (\(version)). Its log may say why.")
            case .failed(let message):
                resultSection(icon: "exclamationmark.triangle.fill", title: "The update didn't finish.", detail: message)
            }
            if let message = model.firmware.message, !model.firmwareBusy {
                Section {
                    Text(message).font(.footnote).foregroundStyle(Color.ink).kairosRow()
                }
            }
        }
        .kairosList()
        .navigationTitle("Firmware")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(model.firmwareBusy)
        .fileImporter(isPresented: $choosingFile, allowedContentTypes: [.data], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { model.importFirmwareFiles(urls) }
        }
        .sheet(isPresented: $choosingVersion) { VersionList() }
        .confirmationDialog(installTitle, isPresented: $confirmingInstall, titleVisibility: .visible) {
            Button("Install", role: .destructive) { model.install() }
        } message: {
            Text(installMessage)
        }
    }

    // MARK: Sections

    private var currentSection: some View {
        Section {
            LabeledContent("Version") { Text(model.info?.fw ?? "").font(BrandFont.mono(13, relativeTo: .footnote)) }.kairosRow()
            LabeledContent("Chip") { Text(model.info?.chip?.title ?? "").font(BrandFont.mono(13, relativeTo: .footnote)) }.kairosRow()
        } header: {
            Text("This device").kairosLabel()
        }
    }

    private var updateSection: some View {
        Section {
            if let offer = model.firmware.offer {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Version \(offer.version) is available").font(BrandFont.display(17, relativeTo: .headline))
                    if let notes = offer.notes, !notes.isEmpty {
                        Text(notes).font(.footnote).foregroundStyle(Color.muted)
                    }
                }
                .kairosRow()
            }
            if let prepared = model.firmware.prepared {
                preparedRows(prepared)
            } else if model.firmware.offer != nil {
                Button("Download") { model.downloadOffer() }.kairosRow()
            }
            Button {
                model.checkForUpdates()
            } label: {
                HStack {
                    Text("Check for updates")
                    if model.firmware.stage == .checking { Spacer(); ProgressView() }
                }
            }
            .disabled(model.firmware.stage == .checking)
            .kairosRow()
        } header: {
            Text("Updates").kairosLabel()
        } footer: {
            Text("Updates are always your choice. The device keeps its pairing and schedule.")
        }
    }

    @ViewBuilder
    private func preparedRows(_ prepared: PreparedFirmware) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(prepared.title).font(BrandFont.display(17, relativeTo: .headline))
            Text(detail(prepared)).font(BrandFont.mono(12, relativeTo: .caption1)).foregroundStyle(Color.muted)
            if !prepared.isSigned {
                Label("No signature. A normal device refuses unsigned firmware; developer builds accept it.", systemImage: "exclamationmark.triangle")
                    .font(.footnote)
            }
        }
        .kairosRow()
        if let blocker = model.installBlocker {
            Label(blocker, systemImage: "lock.fill").font(.footnote).foregroundStyle(Color.ink).kairosRow()
        }
        Button("Install…") {
            confirmingInstall = true
        }
        .disabled(model.installBlocker != nil)
        .kairosRow()
        if !prepared.isSigned && prepared.fileName != nil {
            Button("Add signature file…") { choosingFile = true }.kairosRow()
        }
        Button("Remove", role: .destructive) { model.discardPrepared() }
            .foregroundStyle(Color.ink)
            .kairosRow()
    }

    private var fileSection: some View {
        Section {
            Button("Choose a file…") { choosingFile = true }.kairosRow()
        } header: {
            Text("From a file").kairosLabel()
        } footer: {
            Text("Choose the firmware (ending in -app.bin). If it has a matching .sig file, choose both.")
        }
    }

    private var advancedSection: some View {
        @Bindable var model = model
        return Section {
            DisclosureGroup("Advanced") {
                Toggle("Check for updates when connecting", isOn: Binding(
                    get: { model.checkOnConnectEnabled }, set: { model.checkOnConnectEnabled = $0 }))
                Toggle("Include pre-releases", isOn: Binding(
                    get: { model.includePrereleases }, set: { model.includePrereleases = $0 }))
                Button("Choose a version…") {
                    model.loadReleases()
                    choosingVersion = true
                }
            }
            .kairosRow()
        }
    }

    private func progressSection(_ title: String, value: Double?, cancellable: Bool) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(BrandFont.display(17, relativeTo: .headline))
                if let value {
                    ProgressView(value: value)
                    Text("\(Int((value * 100).rounded()))%").font(BrandFont.mono(12, relativeTo: .caption1)).foregroundStyle(Color.muted)
                } else {
                    ProgressView()
                }
                Text("Keep this screen open. The device restarts when it's done.")
                    .font(.footnote).foregroundStyle(Color.muted)
            }
            .kairosRow()
            if cancellable {
                Button("Cancel", role: .cancel) { model.cancelInstall() }.kairosRow()
            }
        }
    }

    private func resultSection(icon: String, title: String, detail: String) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Label(title, systemImage: icon).font(BrandFont.display(17, relativeTo: .headline))
                Text(detail).font(.footnote).foregroundStyle(Color.muted)
            }
            .kairosRow()
            Button("Done") { model.dismissFirmwareResult() }.kairosRow()
        }
    }

    // MARK: Text

    private func detail(_ p: PreparedFirmware) -> String {
        let size = ByteCountFormatter.string(fromByteCount: Int64(p.data.count), countStyle: .file)
        return [p.fileName, size, p.isSigned ? "signed" : "unsigned", String(p.sha256.prefix(12))].compactMap { $0 }.joined(separator: " · ")
    }

    private var isOlder: Bool {
        guard let v = model.firmware.prepared?.version, let fw = model.info?.fw else { return false }
        return FirmwareVersion.compare(v, fw) == -1
    }

    private var installTitle: String { "Install \(model.firmware.prepared?.title ?? "this firmware")?" }

    private var installMessage: String {
        var text = "The device will restart. Keep the app open until it finishes."
        if isOlder { text = "This is older than the version on the device. " + text }
        if model.firmware.prepared?.fileName != nil, model.firmware.prepared?.version != nil, model.firmware.prepared?.title.hasPrefix("Version") == false {
            text = "This file didn't come from a Kairos release download. " + text
        }
        return text
    }
}

/// Older or other releases, for the Advanced section.
struct VersionList: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if model.firmware.loadingReleases {
                    ProgressView().frame(maxWidth: .infinity)
                } else if let releases = model.firmware.releases {
                    ForEach(releases) { release in
                        Button {
                            dismiss()
                            model.chooseRelease(release)
                        } label: {
                            HStack {
                                Text(release.version).font(BrandFont.mono(15, relativeTo: .body))
                                Spacer()
                                if release.prerelease { StateTag(text: "Pre-release", active: false) }
                            }
                        }
                        .tint(.primary)
                    }
                    if releases.isEmpty { Text("No releases found").foregroundStyle(Color.muted) }
                } else if let message = model.firmware.message {
                    Text(message).foregroundStyle(Color.muted)
                }
            }
            .navigationTitle("Choose a version")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } } }
        }
    }
}
