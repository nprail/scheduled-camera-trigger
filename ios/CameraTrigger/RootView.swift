import SwiftUI
import CoreBluetooth

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationStack {
            switch model.link {
            case .disconnected:
                ScanView()
            case .connecting(let name):
                ProgressView("Connecting to \(name)…")
                    .controlSize(.large)
            case .needsPairing(let reason):
                PairingView(reason: reason)
            case .ready:
                DeviceView()
            }
        }
        .overlay(alignment: .top) { BannerView() }
        .animation(.default, value: model.banner)
    }
}

struct BannerView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let message = model.banner {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                Text(message)
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    model.banner = nil
                } label: {
                    Image(systemName: "xmark")
                }
                .accessibilityLabel("Dismiss")
            }
            .foregroundStyle(.white)
            .padding(12)
            .background(Color.red.gradient, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }
}

struct ScanView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            switch model.bluetooth {
            case .poweredOn:
                Section {
                    if model.devices.isEmpty {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Looking for camera triggers…")
                                .foregroundStyle(.secondary)
                        }
                    }
                    ForEach(model.devices) { device in
                        Button {
                            model.connect(device)
                        } label: {
                            DeviceRow(device: device, paired: model.isPaired(device))
                        }
                        .tint(.primary)
                    }
                } footer: {
                    Text("Stay within a few metres. The first time, you'll pair with the button on the device.")
                }
            case .unauthorized:
                ContentUnavailableView {
                    Label("Bluetooth is off for this app", systemImage: "hand.raised")
                } description: {
                    Text("Allow Bluetooth for Camera Trigger in Settings.")
                } actions: {
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                }
            case .poweredOff:
                ContentUnavailableView(
                    "Bluetooth is off",
                    systemImage: "antenna.radiowaves.left.and.right.slash",
                    description: Text("Turn on Bluetooth to find your camera triggers.")
                )
            case .unsupported:
                ContentUnavailableView(
                    "Bluetooth isn't available",
                    systemImage: "xmark.circle",
                    description: Text("This device doesn't support Bluetooth Low Energy.")
                )
            default:
                ProgressView()
            }
        }
        .navigationTitle("Camera Triggers")
        .onAppear { model.startScanning() }
    }
}

struct DeviceRow: View {
    let device: DiscoveredDevice
    let paired: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name).font(.headline)
                Text(paired ? "Paired" : "Not paired")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: paired ? "lock.fill" : "lock.open")
                .foregroundStyle(.secondary)
            Image(systemName: "wifi", variableValue: signalStrength)
                .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
    }

    /// Map RSSI (about -95 ... -45 dBm) onto 0...1.
    private var signalStrength: Double {
        min(max(Double(device.rssi + 95) / 50, 0), 1)
    }
}

struct PairingView: View {
    @Environment(AppModel.self) private var model
    let reason: String?

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "button.programmable")
                .font(.system(size: 56))
                .foregroundStyle(.tint)
            Text("Pair with this device")
                .font(.title2.bold())
            Text("Press the button on the camera trigger, then tap Pair within two minutes. This phone will then be the only one that can control it.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)

            if let reason {
                Text(reason)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }

            Button {
                model.pair()
            } label: {
                if model.working {
                    ProgressView()
                } else {
                    Text("Pair").frame(maxWidth: 220)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(model.working)

            Button("Cancel", role: .cancel) { model.disconnect() }
        }
        .padding(24)
        .navigationTitle(model.info?.name ?? "Pair")
        .navigationBarTitleDisplayMode(.inline)
    }
}
