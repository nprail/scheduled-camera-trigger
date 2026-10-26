import SwiftUI
import KairosKit

struct ConfigEditorView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var draft: CameraConfig?
    @State private var saving = false
    @State private var errorText: String?

    private var busy: Bool {
        let phase = model.live?.phase ?? .idle
        return phase != .idle
    }

    var body: some View {
        Group {
            if draft != nil {
                form
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Schedule & Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") { save() }
                    .disabled(!isDirty || saving || busy)
            }
        }
        .onAppear { if draft == nil { draft = model.config } }
        .alert("Couldn't save", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorText ?? "")
        }
    }

    private var isDirty: Bool { draft != model.config }

    @ViewBuilder
    private var form: some View {
        if let binding = Binding($draft) {
            Form {
                if busy {
                    Section {
                        Label("The device is busy. Changes can be saved when it's idle.", systemImage: "lock.fill")
                            .foregroundStyle(Color.ink)
                            .kairosRow()
                    }
                }
                attemptsSection(binding)
                timingSection(binding)
                cameraSection(binding)
                generalSection(binding)
            }
            .kairosList()
            .disabled(saving)
        }
    }

    // MARK: Sections

    private func attemptsSection(_ config: Binding<CameraConfig>) -> some View {
        Section {
            ForEach(config.attempts) { $attempt in
                VStack(alignment: .leading, spacing: 6) {
                    TextField("Name", text: $attempt.name)
                        .font(BrandFont.display(17, relativeTo: .headline))
                    DatePicker(
                        "Launch (T-0)",
                        selection: $attempt.date,
                        displayedComponents: [.date, .hourAndMinute]
                    )
                }
                .kairosRow()
            }
            .onDelete { config.wrappedValue.attempts.remove(atOffsets: $0) }

            Button {
                addAttempt(to: config)
            } label: {
                Label("Add attempt", systemImage: "plus.circle")
            }
            .kairosRow()
        } header: {
            Text("Launch attempts").kairosLabel()
        } footer: {
            Text("Times are in your local time zone (\(TimeZone.current.abbreviation() ?? "local")). The device works in UTC. Up to 30 attempts.")
        }
    }

    private func timingSection(_ config: Binding<CameraConfig>) -> some View {
        Section {
            Stepper(value: config.wakeUpTimeout, in: 0 ... 600, step: 1) {
                timing("Wake camera", "\(Int(config.wrappedValue.wakeUpTimeout)) s before recording")
            }
            .kairosRow()
            Stepper(value: config.startBefore, in: 0 ... 600, step: 1) {
                timing("Start recording", "\(Int(config.wrappedValue.startBefore)) s before T-0")
            }
            .kairosRow()
            Stepper(value: config.endAfter, in: 0 ... 600, step: 1) {
                timing("Stop recording", "\(Int(config.wrappedValue.endAfter)) s after T-0")
            }
            .kairosRow()
        } header: {
            Text("Timing").kairosLabel()
        }
    }

    private func cameraSection(_ config: Binding<CameraConfig>) -> some View {
        Section {
            Picker("Type", selection: config.cameraType) {
                Text("Remote trigger (GPIO)").tag("generic")
                Text("Z CAM (network)").tag("zcam")
            }
            .kairosRow()
            if config.wrappedValue.cameraType == "generic" {
                LabeledContent("Release pin") {
                    TextField("GPIO", text: intText(config.generic.releaseGpioPin))
                        .font(BrandFont.mono(15, relativeTo: .body))
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                }
                .kairosRow()
                LabeledContent("Focus pin") {
                    TextField("Optional", text: intText(config.generic.focusGpioPin))
                        .font(BrandFont.mono(15, relativeTo: .body))
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                }
                .kairosRow()
            } else {
                LabeledContent("Camera IP") {
                    TextField("192.168.1.50", text: optionalText(config.zcam.cameraIp))
                        .font(BrandFont.mono(15, relativeTo: .body))
                        .keyboardType(.numbersAndPunctuation)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .multilineTextAlignment(.trailing)
                }
                .kairosRow()
            }
        } header: {
            Text("Camera").kairosLabel()
        }
    }

    private func generalSection(_ config: Binding<CameraConfig>) -> some View {
        Section {
            LabeledContent("Name") {
                TextField("Optional", text: optionalText(config.name))
                    .multilineTextAlignment(.trailing)
            }
            .kairosRow()
            LabeledContent("Button pin") {
                TextField("Optional", text: intText(config.buttonGpioPort))
                    .font(BrandFont.mono(15, relativeTo: .body))
                    .keyboardType(.numberPad)
                    .multilineTextAlignment(.trailing)
            }
            .kairosRow()
        } header: {
            Text("Device").kairosLabel()
        }
    }

    private func timing(_ title: String, _ value: String) -> some View {
        LabeledContent(title) {
            Text(value).font(BrandFont.mono(13, relativeTo: .footnote))
        }
    }

    // MARK: Actions

    private func addAttempt(to config: Binding<CameraConfig>) {
        // default: the next full hour from now (or after the last attempt)
        let base = max(Date(), config.wrappedValue.attempts.map(\.date).max() ?? Date())
        let hour = Calendar.current.dateInterval(of: .hour, for: base)?.end ?? base.addingTimeInterval(3600)
        let n = config.wrappedValue.attempts.count + 1
        config.wrappedValue.attempts.append(.init(name: "Attempt \(n)", time: hour))
    }

    private func save() {
        guard let draft else { return }
        saving = true
        Task {
            let error = await model.saveConfig(draft)
            saving = false
            if let error { errorText = error } else { dismiss() }
        }
    }

    // MARK: Text bindings

    private func intText(_ binding: Binding<Int?>) -> Binding<String> {
        Binding(
            get: { binding.wrappedValue.map(String.init) ?? "" },
            set: { binding.wrappedValue = Int($0.trimmingCharacters(in: .whitespaces)) }
        )
    }

    private func optionalText(_ binding: Binding<String?>) -> Binding<String> {
        Binding(
            get: { binding.wrappedValue ?? "" },
            set: { binding.wrappedValue = $0.isEmpty ? nil : $0 }
        )
    }
}
