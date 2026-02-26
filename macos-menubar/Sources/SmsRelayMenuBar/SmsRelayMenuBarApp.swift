import AppKit
import Combine
import ServiceManagement
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
        NSApp.applicationIconImage = appIconImage()
        enableLaunchAtLoginIfPossible()

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

    private func enableLaunchAtLoginIfPossible() {
        guard Bundle.main.bundleURL.pathExtension == "app" else {
            return
        }
        do {
            try SMAppService.mainApp.register()
        } catch {
            NSLog("[Tang] launch-at-login registration failed: \(error.localizedDescription)")
        }
    }

    private func updateStatusIcon(forMessageCount count: Int) {
        guard let button = statusItem.button else {
            return
        }
        let symbolName = count > 0 ? "message.badge" : "message"
        button.image = NSImage(systemSymbolName: symbolName, accessibilityDescription: L("app_name"))
    }

    private func appIconImage() -> NSImage {
        return speechBubbleIconImage(size: NSSize(width: 512, height: 512))
    }

    private func speechBubbleIconImage(size: NSSize) -> NSImage {
        let image = NSImage(size: size)
        image.lockFocus()

        let rect = NSRect(origin: .zero, size: size)
        let width = size.width
        let radius = min(size.width, size.height) * 0.22
        let background = NSBezierPath(roundedRect: rect.insetBy(dx: 0.5, dy: 0.5), xRadius: radius, yRadius: radius)
        let gradient = NSGradient(
            colors: [
                NSColor(calibratedRed: 0.43, green: 0.64, blue: 1.0, alpha: 1.0),
                NSColor(calibratedRed: 0.18, green: 0.42, blue: 1.0, alpha: 1.0),
                NSColor(calibratedRed: 0.14, green: 0.34, blue: 0.84, alpha: 1.0)
            ]
        )
        gradient?.draw(in: background, angle: -45)

        NSColor.white.setFill()
        let bubbleRect = NSRect(
            x: width * 0.19,
            y: width * 0.24,
            width: width * 0.62,
            height: width * 0.56
        )
        let bubble = NSBezierPath(
            roundedRect: bubbleRect,
            xRadius: width * 0.10,
            yRadius: width * 0.10
        )
        bubble.fill()

        let tail = NSBezierPath()
        tail.move(to: NSPoint(x: width * 0.40, y: width * 0.24))
        tail.line(to: NSPoint(x: width * 0.30, y: width * 0.10))
        tail.line(to: NSPoint(x: width * 0.50, y: width * 0.24))
        tail.close()
        tail.fill()

        NSColor(calibratedRed: 0.18, green: 0.42, blue: 1.0, alpha: 0.34).setFill()
        let line1 = NSBezierPath(roundedRect: NSRect(x: width * 0.30, y: width * 0.54, width: width * 0.36, height: width * 0.05), xRadius: width * 0.025, yRadius: width * 0.025)
        line1.fill()
        let line2 = NSBezierPath(roundedRect: NSRect(x: width * 0.30, y: width * 0.44, width: width * 0.26, height: width * 0.05), xRadius: width * 0.025, yRadius: width * 0.025)
        line2.fill()

        image.unlockFocus()
        return image
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

        let pairItem = NSMenuItem(title: L("pair_device_title"), action: #selector(openPairDeviceFromMenu), keyEquivalent: "")
        pairItem.target = self
        menu.addItem(pairItem)

        let aboutItem = NSMenuItem(title: L("about_title"), action: #selector(openAboutFromMenu), keyEquivalent: "")
        aboutItem.target = self
        menu.addItem(aboutItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(title: L("menu_quit"), action: #selector(quitFromMenu), keyEquivalent: "q")
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
        window.title = L("about_window_title")
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
                Text(L("app_name"))
                    .font(.headline)
                Spacer()
                Text(L("setup_title"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Text(L("setup_step_install_android"))
                .font(.caption)
                .foregroundStyle(.secondary)

            Link("github.com/yangtaengg/Tang/releases/latest", destination: URL(string: "https://github.com/yangtaengg/Tang/releases/latest")!)
                .font(.body)

            Text(L("setup_step_scan_qr"))
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

            Text(L("setup_hint"))
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
                Text(L("app_name"))
                    .font(.headline)
                Spacer()
                Text(L("about_title"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Text(versionText)
                .font(.caption)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 8) {
                Text(L("about_resources"))
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
