import AppKit
import Combine
import SwiftUI
import UserNotifications

@main
struct SmsRelayMenuBarApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        Settings {
            OnboardingSettingsView(appState: appDelegate.appState)
        }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let appState = AppState()
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let popover = NSPopover()
    private var aboutWindow: NSWindow?
    private var popoverGlobalClickMonitor: Any?
    private var popoverLocalClickMonitor: Any?
    private var messageObserver: AnyCancellable?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSWindow.allowsAutomaticWindowTabbing = false

        if Bundle.main.bundleURL.pathExtension == "app" {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }

        if let button = statusItem.button {
            updateStatusIcon(forMessageCount: appState.messageStore.messages.count)
            button.target = self
            button.action = #selector(handleStatusItemClick(_:))
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }

        messageObserver = appState.messageStore.$messages
            .receive(on: RunLoop.main)
            .sink { [weak self] messages in
                self?.updateStatusIcon(forMessageCount: messages.count)
            }

        popover.behavior = .transient
        popover.contentSize = NSSize(width: 360, height: 420)
        popover.contentViewController = NSHostingController(rootView: MenuContentView(appState: appState))
        _ = NSApp.setActivationPolicy(.accessory)
    }

    private func updateStatusIcon(forMessageCount count: Int) {
        guard let button = statusItem.button else {
            return
        }
        let symbolName = count > 0 ? "message.badge" : "message"
        button.image = NSImage(systemSymbolName: symbolName, accessibilityDescription: "Tang!")
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
            closePopover()
            return
        }

        popover.contentViewController = NSHostingController(rootView: MenuContentView(appState: appState))
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        installPopoverDismissMonitor()
    }

    private func showContextMenu(with event: NSEvent) {
        closePopover()

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

    private func closePopover() {
        popover.performClose(nil)
        removePopoverDismissMonitor()
    }

    private func installPopoverDismissMonitor() {
        removePopoverDismissMonitor()
        let mask: NSEvent.EventTypeMask = [.leftMouseDown, .rightMouseDown, .otherMouseDown]
        popoverGlobalClickMonitor = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] _ in
            Task { @MainActor in
                self?.dismissPopoverIfNeeded(at: NSEvent.mouseLocation)
            }
        }
        popoverLocalClickMonitor = NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] event in
            let point: NSPoint
            if let window = event.window {
                point = window.convertPoint(toScreen: event.locationInWindow)
            } else {
                point = NSEvent.mouseLocation
            }
            self?.dismissPopoverIfNeeded(at: point)
            return event
        }
    }

    private func removePopoverDismissMonitor() {
        if let monitor = popoverGlobalClickMonitor {
            NSEvent.removeMonitor(monitor)
            popoverGlobalClickMonitor = nil
        }
        if let monitor = popoverLocalClickMonitor {
            NSEvent.removeMonitor(monitor)
            popoverLocalClickMonitor = nil
        }
    }

    private func dismissPopoverIfNeeded(at screenPoint: NSPoint) {
        guard popover.isShown else {
            removePopoverDismissMonitor()
            return
        }
        guard let popoverWindow = popover.contentViewController?.view.window else {
            return
        }
        if popoverWindow.frame.contains(screenPoint) {
            return
        }
        closePopover()
    }

    @objc private func openPairDeviceFromMenu() {
        appState.openPairDeviceWindow()
    }

    @objc private func openAboutFromMenu() {
        let window = ensureAboutWindow()
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
        window.isOpaque = false
        window.backgroundColor = .clear
        window.center()
        window.contentView = NSHostingView(rootView: AboutView())
        aboutWindow = window
        return window
    }
}

private struct OnboardingSettingsView: View {
    @ObservedObject var appState: AppState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Tang!")
                    .font(.headline)
                Spacer()
                Text("Setup")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Text("1) Install Android app")
                .font(.caption)
                .foregroundStyle(.secondary)

            Link("github.com/yangtaengg/Tang/releases/latest", destination: URL(string: "https://github.com/yangtaengg/Tang/releases/latest")!)
                .font(.body)

            Text("2) Scan QR in Android app")
                .font(.caption)
                .foregroundStyle(.secondary)

            if let qrImage = appState.qrImage {
                HStack {
                    Spacer()
                    Image(nsImage: qrImage)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 220, height: 220)
                    Spacer()
                }
                .padding(8)
                .background(Color.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
            }

            Text("Use Android app: Notification Access ON -> Pair via QR")
                .font(.caption)
                .foregroundStyle(.secondary)

            Spacer()
        }
        .padding(16)
        .frame(width: 360, height: 420)
        .background(Color(nsColor: .windowBackgroundColor).opacity(0.9))
        .onChange(of: appState.pairedDeviceName) { newValue in
            if newValue != nil {
                dismiss()
            }
        }
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
            HStack {
                Text("Tang!")
                    .font(.headline)
                Spacer()
                Text("About")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Text(versionText)
                .font(.caption)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 8) {
                Text("Resources")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Link("buymeacoffee.com/andyyang", destination: URL(string: "https://buymeacoffee.com/andyyang")!)
                    .font(.body)
                Link("github.com/yangtaengg/Tang", destination: URL(string: "https://github.com/yangtaengg/Tang")!)
                    .font(.body)
            }
            .padding(8)
            .background(Color.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))

            Spacer()
        }
        .padding(16)
        .frame(width: 360, height: 220)
        .background(Color(nsColor: .windowBackgroundColor).opacity(0.9))
    }
}
