import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        configureRaylEnvironment()
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
