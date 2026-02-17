import SwiftUI
import UserNotifications

@main
struct SmsRelayMenuBarApp: App {
    @StateObject private var appState = AppState()

    init() {
        guard Bundle.main.bundleURL.pathExtension == "app" else {
            return
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    var body: some Scene {
        MenuBarExtra("Tang!", systemImage: "message.badge") {
            MenuContentView(appState: appState)
        }
        .menuBarExtraStyle(.window)

        Window("Pair device", id: "pair-device") {
            PairingView(appState: appState)
        }
        .windowResizability(.contentSize)

    }
}
