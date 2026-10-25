import SwiftUI
import CoreBluetooth
import KairosKit

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
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.ground)
                    #if DEBUG
                    .toolbar {
                        // a demo "connecting" scene has nothing to finish, so give it a way out
                        if model.demo {
                            ToolbarItem(placement: .topBarLeading) {
                                Button("Cancel") { model.disconnect() }
                            }
                        }
                    }
                    #endif
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
            .foregroundStyle(Color.ground)
            .padding(12)
            .background(Color.ink, in: RoundedRectangle(cornerRadius: Radius.md))
            .padding(.horizontal)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }
}

struct ScanView: View {
    @Environment(AppModel.self) private var model
    #if DEBUG
    @State private var footerTaps = 0
    @State private var showingDemoMenu = false
    #endif

    var body: some View {
        List {
            switch model.bluetooth {
            case .poweredOn:
                Section {
                    if model.devices.isEmpty {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Looking for Kairos devices…")
                                .foregroundStyle(Color.muted)
                        }
                        .kairosRow()
                    }
                    ForEach(model.devices) { device in
                        Button {
                            model.connect(device)
                        } label: {
                            DeviceRow(device: device, paired: model.isPaired(device))
                        }
                        .tint(.primary)
                        .kairosRow()
                    }
                } footer: {
                    Text("Stay within a few metres. The first time, you'll pair with the button on the device.")
                        #if DEBUG
                        // debug builds: tap this hint 7 times for the demo menu (fixtures instead of Bluetooth)
                        .onTapGesture {
                            footerTaps += 1
                            if footerTaps >= 7 {
                                footerTaps = 0
                                showingDemoMenu = true
                            }
                        }
                        #endif
                }
            case .unauthorized:
                ContentUnavailableView {
                    Label("Bluetooth is off for this app", systemImage: "hand.raised")
                } description: {
                    Text("Allow Bluetooth for Kairos in Settings.")
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
                    description: Text("Turn on Bluetooth to find your Kairos devices.")
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
        .kairosList()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { KairosLockup(height: 28) }
        }
        .onAppear { model.startScanning() }
        #if DEBUG
        .sheet(isPresented: $showingDemoMenu) { DemoMenu() }
        #endif
    }
}

#if DEBUG
/// Debug builds only: pick a demo scene (sample data instead of Bluetooth), or leave demo mode.
struct DemoMenu: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(AppModel.scenes, id: \.self) { scene in
                        Button(scene) {
                            dismiss()
                            model.applyDemo(scene)
                        }
                        .tint(.primary)
                    }
                } footer: {
                    Text("Show a screen with sample data instead of using Bluetooth. Debug builds only.")
                }
                if model.demo {
                    Section {
                        Button("Exit demo", role: .destructive) {
                            dismiss()
                            model.exitDemo()
                        }
                    }
                }
            }
            .navigationTitle("Demo mode")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } }
            }
        }
    }
}
#endif

struct DeviceRow: View {
    let device: DiscoveredDevice
    let paired: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name).font(BrandFont.display(17, relativeTo: .headline))
                Text(paired ? "Paired" : "Not paired")
                    .font(.caption)
                    .foregroundStyle(Color.muted)
            }
            Spacer()
            Image(systemName: paired ? "lock.fill" : "lock.open")
                .foregroundStyle(Color.muted)
            Image(systemName: "wifi", variableValue: signalStrength)
                .foregroundStyle(Color.muted)
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
                .foregroundStyle(Color.ink)
            Text("Pair with this device")
                .font(BrandFont.display(22, relativeTo: .title2))
            Text("Press the button on the Kairos device, then tap Pair within two minutes. This phone will then be the only one that can control it.")
                .multilineTextAlignment(.center)
                .foregroundStyle(Color.muted)

            if let reason {
                Label(reason, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Color.ink)
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
            .buttonStyle(SignalButtonStyle())
            .disabled(model.working)

            Button("Cancel", role: .cancel) { model.disconnect() }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.ground)
        .navigationTitle(model.info?.name ?? "Pair")
        .navigationBarTitleDisplayMode(.inline)
    }
}
