import SwiftUI
import CameraTriggerKit

struct LogsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollViewReader { proxy in
            List(model.logs) { entry in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(entry.ns)
                            .font(.caption2.bold())
                            .foregroundStyle(.secondary)
                        Spacer()
                        if let date = entry.date {
                            Text(date.formatted(date: .omitted, time: .standard))
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                    Text(entry.msg)
                        .font(.footnote.monospaced())
                }
                .id(entry.seq)
            }
            .listStyle(.plain)
            .overlay {
                if model.logs.isEmpty {
                    ContentUnavailableView("No log lines yet", systemImage: "text.alignleft")
                }
            }
            .onChange(of: model.logs.last?.seq) { _, last in
                if let last { withAnimation { proxy.scrollTo(last, anchor: .bottom) } }
            }
            .onAppear {
                if let last = model.logs.last?.seq { proxy.scrollTo(last, anchor: .bottom) }
            }
        }
        .navigationTitle("Logs")
        .navigationBarTitleDisplayMode(.inline)
    }
}
