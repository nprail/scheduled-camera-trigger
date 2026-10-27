import SwiftUI

@main
struct KairosApp: App {
    @State private var model = AppModel()

    init() {
        configureBrandAppearance()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(Color.ink)
                .background(Color.ground)
        }
    }
}
