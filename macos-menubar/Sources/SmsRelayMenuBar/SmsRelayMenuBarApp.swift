import AppKit
import SwiftUI
import UserNotifications

@main
struct SmsRelayMenuBarApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        Settings {
            EmptyView()
        }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private let appState = AppState()
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let popover = NSPopover()
    private var aboutWindow: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        if Bundle.main.bundleURL.pathExtension == "app" {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }

        if let button = statusItem.button {
            button.image = NSImage(systemSymbolName: "message.badge", accessibilityDescription: "Tang!")
            button.target = self
            button.action = #selector(handleStatusItemClick(_:))
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }

        popover.behavior = .transient
        popover.contentSize = NSSize(width: 360, height: 420)
        popover.contentViewController = NSHostingController(rootView: MenuContentView(appState: appState))
        _ = NSApp.setActivationPolicy(.accessory)
    }

    @objc private func handleStatusItemClick(_ sender: NSStatusBarButton) {
        guard let event = NSApp.currentEvent else {
            togglePopover(sender)
            return
        }

        if event.type == .rightMouseUp {
            showContextMenu(with: event)
        } else {
            togglePopover(sender)
        }
    }

    private func togglePopover(_ button: NSStatusBarButton) {
        if popover.isShown {
            popover.performClose(nil)
            return
        }

        popover.contentViewController = NSHostingController(rootView: MenuContentView(appState: appState))
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
    }

    private func showContextMenu(with event: NSEvent) {
        popover.performClose(nil)

        let menu = NSMenu()

        let pairItem = NSMenuItem(title: "Pair device", action: #selector(openPairDeviceFromMenu), keyEquivalent: "")
        pairItem.target = self
        menu.addItem(pairItem)

        let aboutItem = NSMenuItem(title: "About", action: #selector(openAboutFromMenu), keyEquivalent: "")
        aboutItem.target = self
        menu.addItem(aboutItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(title: "Quit", action: #selector(quitFromMenu), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        guard let button = statusItem.button else {
            return
        }

        NSMenu.popUpContextMenu(menu, with: event, for: button)
    }

    @objc private func openPairDeviceFromMenu() {
        appState.openPairDeviceWindow()
    }

    @objc private func openAboutFromMenu() {
        let window = ensureAboutWindow()
        _ = NSApp.setActivationPolicy(.regular)
        let app = NSRunningApplication.current
        app.activate(options: [.activateAllWindows, .activateIgnoringOtherApps])
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
    }

    @objc private func quitFromMenu() {
        NSApplication.shared.terminate(nil)
    }

    private func ensureAboutWindow() -> NSWindow {
        if let window = aboutWindow {
            return window
        }

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 360, height: 220),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "About Tang"
        window.level = .normal
        window.isReleasedWhenClosed = false
        window.center()
        window.contentView = NSHostingView(rootView: AboutView())
        aboutWindow = window
        return window
    }
}

private struct AboutView: View {
    private var versionText: String {
        let shortVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "-"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "-"
        return "v\(shortVersion) (\(build))"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Tang")
                .font(.title2)
                .bold()

            Text(versionText)
                .font(.caption)
                .foregroundStyle(.secondary)

            Link("Buy me a coffee", destination: URL(string: "https://buymeacoffee.com/andyyang")!)
            Link("GitHub", destination: URL(string: "https://github.com/yangtaengg/Tang")!)

            Spacer()
        }
        .padding(16)
        .frame(width: 360, height: 220)
    }
}
