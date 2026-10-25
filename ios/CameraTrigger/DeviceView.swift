import SwiftUI
import CameraTriggerKit

struct DeviceView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmingDisconnectWifi = false
    @State private var confirmingForget = false
    @State private var confirmingCamera: CameraAction?

    private var phase: StatusValue.Phase { model.live?.phase ?? .idle }

    /// Manual camera control is refused by the device during an attempt or test.
    private var canControl: Bool { phase == .idle || phase == .recording }

    var body: some View {
        List {
            statusSection
            nextSection
            controlSection
            scheduleSection
            deviceSection
        }
        .navigationTitle(model.info?.name.isEmpty == false ? model.info!.name : "Camera Trigger")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Disconnect") { model.disconnect() }
            }
        }
        .confirmationDialog(
            "Turn Wi-Fi off?",
            isPresented: $confirmingDisconnectWifi,
            titleVisibility: .visible
        ) {
            Button("Turn Off Wi-Fi", role: .destructive) { model.setWifi(false) }
        } message: {
            Text("You can turn it back on here over Bluetooth, or with the button on the device.")
        }
        .confirmationDialog(
            "Forget this device?",
            isPresented: $confirmingForget,
            titleVisibility: .visible
        ) {
            Button("Forget Pairing", role: .destructive) { model.forgetPairing() }
        } message: {
            Text("To pair again, press the button on the device.")
        }
        .confirmationDialog(
            confirmingCamera == .start ? "Start recording now?" : "Stop recording now?",
            isPresented: Binding(get: { confirmingCamera != nil }, set: { if !$0 { confirmingCamera = nil } }),
            titleVisibility: .visible
        ) {
            if let action = confirmingCamera {
                Button(action == .start ? "Start Recording" : "Stop Recording") { model.camera(action) }
            }
        }
    }

    // MARK: Sections

    private var statusSection: some View {
        Section {
            HStack {
                Label(phaseTitle, systemImage: phaseSymbol)
                    .foregroundStyle(phaseColor)
                    .font(.headline)
                Spacer()
                if model.live?.flags.contains(.recording) == true {
                    Label("REC", systemImage: "circle.fill")
                        .font(.caption.bold())
                        .foregroundStyle(.red)
                }
            }
            if model.live?.flags.contains(.cameraReady) == false {
                Label("Camera not ready. Check the wiring or camera connection.", systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }
    }

    private var nextSection: some View {
        Section("Next") {
            if let date = model.live?.nextEventDate, let type = model.live?.nextEventType {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(eventTitle(type)).font(.headline)
                            Text(model.status?.next?.attempt ?? "")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(countdown(to: date, now: context.date))
                            .font(.title3.monospacedDigit())
                    }
                }
                LabeledContent("At", value: date.formatted(date: .abbreviated, time: .standard))
            } else {
                Text("Nothing scheduled").foregroundStyle(.secondary)
            }
        }
    }

    private var controlSection: some View {
        Section("Camera") {
            Button {
                model.runTest()
            } label: {
                HStack {
                    Label("Run test", systemImage: "checkmark.seal")
                    if model.testRunning { Spacer(); ProgressView() }
                }
            }
            .disabled(phase != .idle || model.testRunning)

            if !model.testSteps.isEmpty {
                Text(model.testSteps.joined(separator: " → "))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Grid(horizontalSpacing: 10, verticalSpacing: 10) {
                GridRow {
                    controlButton("Wake", "sun.max", .wake)
                    controlButton("Sleep", "moon", .sleep)
                }
                GridRow {
                    controlButton("Start", "record.circle", .start, confirm: true)
                    controlButton("Stop", "stop.circle", .stop, confirm: true)
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func controlButton(
        _ title: String,
        _ symbol: String,
        _ action: CameraAction,
        confirm: Bool = false
    ) -> some View {
        Button {
            if confirm { confirmingCamera = action } else { model.camera(action) }
        } label: {
            Label(title, systemImage: symbol).frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .disabled(!canControl)
    }

    private var scheduleSection: some View {
        Section("Schedule") {
            if model.jobs.isEmpty {
                Text("No attempts scheduled").foregroundStyle(.secondary)
            }
            ForEach(model.jobs) { job in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(job.name)
                        if let date = job.launchDate {
                            Text(date.formatted(date: .abbreviated, time: .standard))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    Text(job.state.capitalized)
                        .font(.caption.bold())
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(jobColor(job.state).opacity(0.15), in: Capsule())
                        .foregroundStyle(jobColor(job.state))
                }
            }
            NavigationLink("Edit schedule and settings") {
                ConfigEditorView()
            }
        }
    }

    private var deviceSection: some View {
        Section("Device") {
            NavigationLink("Logs") { LogsView() }

            Toggle("Wi-Fi", isOn: Binding(
                get: { model.live?.flags.contains(.wifi) ?? true },
                set: { on in
                    if on { model.setWifi(true) } else { confirmingDisconnectWifi = true }
                }
            ))

            HStack {
                VStack(alignment: .leading) {
                    Text("Clock")
                    Text(clockText).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button("Sync now") { model.syncClock() }
                    .buttonStyle(.bordered)
                    .disabled(phase != .idle)
            }

            if let info = model.info {
                LabeledContent("ID", value: info.id)
                LabeledContent("Camera", value: info.cam)
                LabeledContent("Software", value: info.fw)
            }

            Button("Forget pairing", role: .destructive) { confirmingForget = true }
        }
    }

    // MARK: Helpers

    private var phaseTitle: String {
        switch phase {
        case .idle: return "Idle"
        case .awake: return "Camera awake"
        case .recording: return "Recording"
        case .test: return "Running test"
        case .error: return "Needs attention"
        }
    }

    private var phaseSymbol: String {
        switch phase {
        case .idle: return "pause.circle"
        case .awake: return "sun.max.circle"
        case .recording: return "record.circle.fill"
        case .test: return "checkmark.seal"
        case .error: return "exclamationmark.triangle.fill"
        }
    }

    private var phaseColor: Color {
        switch phase {
        case .idle: return .secondary
        case .awake: return .orange
        case .recording: return .red
        case .test: return .blue
        case .error: return .orange
        }
    }

    private func eventTitle(_ type: StatusValue.EventType) -> String {
        switch type {
        case .wake: return "Wake the camera"
        case .start: return "Start recording"
        case .stop: return "Stop recording"
        case .none: return "Nothing"
        }
    }

    private func countdown(to date: Date, now: Date) -> String {
        let seconds = max(0, Int(date.timeIntervalSince(now).rounded(.up)))
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        let s = seconds % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, s)
            : String(format: "%02d:%02d", m, s)
    }

    private func jobColor(_ state: String) -> Color {
        switch state {
        case "active": return .red
        case "complete": return .green
        case "missed": return .orange
        default: return .blue
        }
    }

    private var clockText: String {
        guard let clock = model.clock else { return "Not checked" }
        let ms = Int((clock.offset * 1000).rounded())
        if clock.applied { return "Set from this phone (was \(ms) ms off)" }
        return "In sync (\(ms) ms)"
    }
}
