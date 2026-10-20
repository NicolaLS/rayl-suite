import SwiftUI
import Shared

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    RaylDeepLinks.shared.emit(uri: url.absoluteString)
                }
        }
    }
}
