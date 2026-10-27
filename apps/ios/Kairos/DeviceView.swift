import SwiftUI
import KairosKit

struct DeviceView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmingForget = false
    @State private var confirmingCamera: CameraAction?
    #if DEBUG
    @State private var demoDestination: String?
    #endif

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
        .kairosList()
        .navigationTitle(model.info?.name.isEmpty == false ? model.info!.name : "Kairos")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Disconnect") { model.disconnect() }
            }
        }
        #if DEBUG
        .navigationDestination(item: $demoDestination) { screen in
            switch screen {
            case "config": ConfigEditorView()
            case "logs": LogsView()
            default: FirmwareView()
            }
        }
        .onAppear {
            if let screen = model.demoScreen {
                model.demoScreen = nil
                demoDestination = screen
            }
        }
        #endif
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
                Label {
                    Text(phaseTitle).kairosLabel()
                } icon: {
                    Image(systemName: phaseSymbol).foregroundStyle(phaseColor)
                }
                .foregroundStyle(Color.ink)
                Spacer()
                if model.live?.flags.contains(.recording) == true {
                    // the record dot: the one place signal is spent on this card
                    HStack(spacing: 6) {
                        Circle().fill(Color.signal).frame(width: 10, height: 10)
                        Text("REC").kairosLabel()
                    }
                    .foregroundStyle(Color.ink)
                    .accessibilityElement(children: .combine)
                }
            }
            .kairosRow()
            if model.live?.flags.contains(.cameraReady) == false {
                Label("Camera not ready. Check the wiring or camera connection.", systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Color.ink)
                    .kairosRow()
            }
        }
    }

    private var nextSection: some View {
        Section {
            if let date = model.live?.nextEventDate, let type = model.live?.nextEventType {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(eventTitle(type)).font(BrandFont.display(17, relativeTo: .headline))
                            Text(model.nextAttemptName ?? "")
                                .font(.caption)
                                .foregroundStyle(Color.muted)
                        }
                        Spacer()
                        Text(countdown(to: date, now: context.date))
                            .font(BrandFont.mono(24, relativeTo: .title2))
                    }
                }
                .kairosRow()
                LabeledContent("At") {
                    Text(date.formatted(date: .abbreviated, time: .standard)).font(BrandFont.mono(13, relativeTo: .footnote))
                }
                .kairosRow()
            } else {
                Text("Nothing scheduled").foregroundStyle(Color.muted).kairosRow()
            }
        } header: {
            Text("Next").kairosLabel()
        }
    }

    private var controlSection: some View {
        Section {
            Button {
                model.runTest()
            } label: {
                HStack {
                    Label("Run test", systemImage: "checkmark.seal")
                    if model.testRunning { Spacer(); ProgressView() }
                }
            }
            .disabled(phase != .idle || model.testRunning)
            .kairosRow()

            if !model.testSteps.isEmpty {
                Text(model.testSteps.joined(separator: " → "))
                    .font(BrandFont.mono(12, relativeTo: .footnote))
                    .foregroundStyle(Color.muted)
                    .kairosRow()
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
            .kairosRow()
        } header: {
            Text("Camera").kairosLabel()
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
        Section {
            if model.jobs.isEmpty {
                Text("No attempts scheduled").foregroundStyle(Color.muted).kairosRow()
            }
            ForEach(model.jobs) { job in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(job.name)
                        Text(job.launchDate.formatted(date: .abbreviated, time: .standard))
                            .font(BrandFont.mono(12, relativeTo: .caption1))
                            .foregroundStyle(Color.muted)
                    }
                    Spacer()
                    StateTag(text: job.state.capitalized, active: job.state == "active")
                }
                .kairosRow()
            }
            NavigationLink("Edit schedule and settings") {
                ConfigEditorView()
            }
            .kairosRow()
        } header: {
            Text("Schedule").kairosLabel()
        }
    }

    private var deviceSection: some View {
        Section {
            NavigationLink("Logs") { LogsView() }.kairosRow()

            if model.canUpdate {
                NavigationLink {
                    FirmwareView()
                } label: {
                    HStack {
                        Text("Firmware")
                        Spacer()
                        if let offer = model.firmware.offer {
                            // a quiet badge: an update is the user's choice, never a prompt
                            StateTag(text: "Update \(offer.version)", active: false)
                        }
                    }
                }
                .kairosRow()
            }

            HStack {
                VStack(alignment: .leading) {
                    Text("Clock")
                    Text(clockText).font(BrandFont.mono(12, relativeTo: .caption1)).foregroundStyle(Color.muted)
                }
                Spacer()
                Button("Sync now") { model.syncClock() }
                    .buttonStyle(.bordered)
                    .disabled(phase != .idle)
            }
            .kairosRow()

            if let info = model.info {
                mono("ID", info.id)
                mono("Camera", info.cam)
                mono("Software", info.fw)
            }

            Button("Forget pairing", role: .destructive) { confirmingForget = true }
                .foregroundStyle(Color.ink)
                .kairosRow()
        } header: {
            Text("Device").kairosLabel()
        }
    }

    // MARK: Helpers

    private func mono(_ title: String, _ value: String) -> some View {
        LabeledContent(title) {
            Text(value).font(BrandFont.mono(13, relativeTo: .footnote))
        }
        .kairosRow()
    }

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

    /// Icon color only. The state is always in the text too. `signal` is spent once, on the REC dot.
    private var phaseColor: Color {
        switch phase {
        case .idle: return Color.muted
        case .awake, .test, .error, .recording: return Color.ink
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
        // T-minus notation, as in the brand book
        return String(format: "T-%02d:%02d:%02d", h, m, s)
    }

    private var clockText: String {
        guard let clock = model.clock else { return "Not checked" }
        let ms = Int((clock.offset * 1000).rounded())
        if clock.applied { return "Set from this phone (was \(ms) ms off)" }
        return "In sync (\(ms) ms)"
    }
}
