import SwiftUI
import Shared

@main
struct E2EApp: App {
    init() {
        configureRaylSpark()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    RaylDeepLinks.shared.emit(uri: url.absoluteString)
                }
        }
    }
}
