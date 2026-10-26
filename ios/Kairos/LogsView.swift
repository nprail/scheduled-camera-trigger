import SwiftUI
import KairosKit

struct LogsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollViewReader { proxy in
            List(model.logs) { entry in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(entry.ns)
                            .kairosLabel()
                            .foregroundStyle(Color.muted)
                        Spacer()
                        if let date = entry.date {
                            Text(date.formatted(date: .omitted, time: .standard))
                                .font(BrandFont.mono(11, relativeTo: .caption2))
                                .foregroundStyle(Color.muted)
                        }
                    }
                    Text(entry.msg)
                        .font(BrandFont.mono(13, weight: 400, relativeTo: .footnote))
                }
                .id(entry.seq)
                .listRowBackground(Color.ground)
            }
            .listStyle(.plain)
            .kairosList()
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
