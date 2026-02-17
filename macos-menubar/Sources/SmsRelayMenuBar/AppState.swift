import AppKit
import Foundation
import SwiftUI
import UserNotifications

@MainActor
final class AppState: ObservableObject {
    @Published var serverState: String = "stopped"
    @Published var pairingHost: String = LocalNetworkInfo.defaultIPv4()
    @Published var pairingPort: UInt16 = 8765
    @Published var pairingExpiresAt: Date = .distantFuture
    @Published var qrImage: NSImage?
    @Published var qrPayloadString: String = ""
    @Published private(set) var pairedDeviceName: String?
    @Published private(set) var pairedAppVersion: String?
    @Published var selectedMessage: SmsMessage?
    @Published var replyStatusText: String?

    let messageStore = MessageStore()
    private let server: WebSocketServer
    private(set) var token: String
    private var pendingReplySmsClientMsgId: String?
    private var pairDeviceWindow: NSWindow?
    private lazy var messageWindow: NSWindow = {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 360),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Message"
        window.identifier = NSUserInterfaceItemIdentifier("message-detail")
        window.level = .normal
        window.isReleasedWhenClosed = false
        window.center()
        window.contentView = NSHostingView(rootView: MessageDetailView(appState: self, onClose: { [weak window] in
            window?.orderOut(nil)
            // If you want to return to menu-bar-only behavior after closing this window,
            // call NSApp.setActivationPolicy(.accessory) here.
        }))
        return window
    }()
    private var fallbackNotificationWindow: NSWindow?
    private var fallbackNotificationMessage: SmsMessage?
    private var fallbackGlobalClickMonitor: Any?
    private var fallbackLocalClickMonitor: Any?
    private static let nonExpiringExpiresAtMs: Int64 = 253402300799000

    init() {
        let initialPort: UInt16 = 8765
        let existing = KeychainStore.loadToken()
        token = existing ?? TokenFactory.randomBase64Token()
        if existing == nil {
            try? KeychainStore.saveToken(token)
        }

        pairingPort = initialPort
        server = WebSocketServer(config: .init(port: initialPort, token: token))
        server.onServerStateChanged = { [weak self] state in
            Task { @MainActor in
                self?.serverState = state
            }
        }
        server.onSmsMessage = { [weak self] message in
            Task { @MainActor in
                self?.messageStore.append(message)
                self?.notify(message)
            }
        }
        server.onIncomingCall = { [weak self] call in
            Task { @MainActor in
                self?.notifyIncomingCall(call)
            }
        }
        server.onReplyResult = { [weak self] _, success, reason in
            Task { @MainActor in
                if success {
                    self?.replyStatusText = "Reply sent"
                } else {
                    self?.replyStatusText = reason ?? "Reply failed"
                }
            }
        }
        server.onReplySmsResult = { [weak self] clientMsgId, success, reason in
            Task { @MainActor in
                if let clientMsgId,
                   let pending = self?.pendingReplySmsClientMsgId,
                   pending != clientMsgId {
                    return
                }
                self?.pendingReplySmsClientMsgId = nil
                if success {
                    self?.replyStatusText = "Success!"
                } else {
                    self?.replyStatusText = reason ?? "SMS send failed"
                }
            }
        }
        server.onClientAuthenticated = { [weak self] device, appVersion in
            Task { @MainActor in
                self?.pairedDeviceName = device
                self?.pairedAppVersion = appVersion
                self?.closePairingWindowIfOpen()
            }
        }
        server.onAuthenticatedClientCountChanged = { [weak self] count in
            Task { @MainActor in
                guard let self else { return }
                if count == 0 {
                    self.pairedDeviceName = nil
                    self.pairedAppVersion = nil
                }
            }
        }

        server.start()
        refreshPairingQR()
    }

    func regenerateToken() {
        token = TokenFactory.randomBase64Token()
        try? KeychainStore.saveToken(token)
        server.updateToken(token)
        pairedDeviceName = nil
        pairedAppVersion = nil
        refreshPairingQR()
    }

    func openPairDeviceWindow() {
        refreshPairingQR()
        let window = ensurePairDeviceWindow()
        _ = NSApp.setActivationPolicy(.regular)
        let app = NSRunningApplication.current
        app.activate(options: [.activateAllWindows, .activateIgnoringOtherApps])
        NSApp.activate(ignoringOtherApps: true)
        window.level = .normal
        window.makeKeyAndOrderFront(nil)
    }

    func refreshPairingQR() {
        pairingExpiresAt = Date(timeIntervalSince1970: TimeInterval(Self.nonExpiringExpiresAtMs) / 1000)
        let payload = PairingPayload(
            version: 1,
            url: "ws://\(pairingHost):\(pairingPort)/ws",
            pairingToken: token,
            expiresAtMs: Self.nonExpiringExpiresAtMs,
            deviceName: Host.current().localizedName ?? "Mac"
        )

        let encoder = JSONEncoder()
        guard let data = try? encoder.encode(payload),
              let json = String(data: data, encoding: .utf8) else {
            return
        }
        qrPayloadString = json
        qrImage = QRCodeRenderer.image(from: json)
    }

    func copy(_ message: SmsMessage) {
        let text = "[\(message.from)] \(message.body)"
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    func deleteMessage(_ message: SmsMessage) {
        messageStore.remove(messageID: message.id)
        if selectedMessage?.id == message.id {
            selectedMessage = nil
        }
    }

    func verificationCode(in message: SmsMessage) -> String? {
        let range = NSRange(message.body.startIndex..., in: message.body)
        guard let regex = try? NSRegularExpression(pattern: #"(?<!\d)\d{4,8}(?!\d)"#),
              let match = regex.firstMatch(in: message.body, range: range),
              let codeRange = Range(match.range, in: message.body) else {
            return nil
        }
        return String(message.body[codeRange])
    }

    func copyVerificationCode(from message: SmsMessage) {
        guard let code = verificationCode(in: message) else {
            return
        }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(code, forType: .string)
    }

    func openMessageDetail(_ message: SmsMessage) {
        fallbackNotificationWindow?.close()
        clearFallbackNotificationReference()
        selectedMessage = message
        replyStatusText = nil
        showMessageWindow()
    }

    private func showMessageWindow() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in
                self?.showMessageWindow()
            }
            return
        }

        _ = NSApp.setActivationPolicy(.regular)
        messageWindow.level = .normal
        let app = NSRunningApplication.current
        app.activate(options: [.activateAllWindows, .activateIgnoringOtherApps])
        NSApp.activate(ignoringOtherApps: true)
        messageWindow.makeKeyAndOrderFront(nil)
    }

    func sendReply(for message: SmsMessage, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            replyStatusText = "Reply text is empty"
            return
        }

        let sent = server.sendSmsReply(
            replyKey: message.replyKey,
            sourcePackage: message.sourcePackage,
            conversationKey: message.conversationKey,
            body: trimmed
        )

        if sent {
            replyStatusText = "Sending..."
        } else {
            replyStatusText = "No connected Android device"
        }
    }

    func sendReplySms(for message: SmsMessage, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            replyStatusText = "Reply text is empty"
            return
        }

        let clientMsgId = UUID().uuidString
        let timestampMs = Int64(Date().timeIntervalSince1970 * 1000)
        let destination = message.fromPhone ?? message.from
        let sent = server.sendReplySms(
            to: destination,
            body: trimmed,
            sourcePackage: message.sourcePackage,
            conversationKey: message.conversationKey,
            clientMsgId: clientMsgId,
            timestampMs: timestampMs
        )

        if sent {
            pendingReplySmsClientMsgId = clientMsgId
            replyStatusText = "Sending SMS..."
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
                guard let self else { return }
                if self.pendingReplySmsClientMsgId == clientMsgId {
                    self.pendingReplySmsClientMsgId = nil
                    self.replyStatusText = "Success!"
                }
            }
        } else {
            pendingReplySmsClientMsgId = nil
            replyStatusText = "No connected Android device"
        }
    }

    func copyPairingPayload() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(qrPayloadString, forType: .string)
    }

    private func notify(_ message: SmsMessage) {
        guard Bundle.main.bundleURL.pathExtension == "app" else {
            showFallbackNotification(message: message)
            return
        }
        let content = UNMutableNotificationContent()
        content.title = message.from
        content.body = message.body
        let request = UNNotificationRequest(
            identifier: message.id,
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    private func notifyIncomingCall(_ call: IncomingCallEvent) {
        let title = "Incoming Call"
        let body = call.displayName == call.from ? call.from : "\(call.displayName) (\(call.from))"

        guard Bundle.main.bundleURL.pathExtension == "app" else {
            showFallbackCallNotification(title: title, body: body)
            return
        }

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        let request = UNNotificationRequest(
            identifier: "call-\(call.id)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    private func closePairingWindowIfOpen() {
        guard let window = pairDeviceWindow else {
            return
        }
        if window.isVisible {
            window.orderOut(nil)
        }
    }

    private func ensurePairDeviceWindow() -> NSWindow {
        if let window = pairDeviceWindow {
            return window
        }

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 360, height: 560),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Pair device"
        window.identifier = NSUserInterfaceItemIdentifier("pair-device")
        window.level = .normal
        window.isReleasedWhenClosed = false
        window.center()
        window.contentView = NSHostingView(rootView: PairingView(appState: self))
        pairDeviceWindow = window
        return window
    }

    private func showFallbackNotification(message: SmsMessage) {
        fallbackNotificationWindow?.close()
        fallbackNotificationMessage = message

        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 380, height: 200),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.hasShadow = true
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]

        let container = NSView(frame: NSRect(x: 0, y: 0, width: 380, height: 200))
        container.wantsLayer = true
        container.layer?.cornerRadius = 14
        container.layer?.borderWidth = 1
        container.layer?.borderColor = NSColor.separatorColor.cgColor
        container.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor

        let chipLabel = NSTextField(labelWithString: "New SMS")
        chipLabel.font = .systemFont(ofSize: 11, weight: .semibold)
        chipLabel.textColor = .secondaryLabelColor
        chipLabel.translatesAutoresizingMaskIntoConstraints = false

        let timestampLabel = NSTextField(labelWithString: message.timestamp.formatted(date: .omitted, time: .shortened))
        timestampLabel.font = .systemFont(ofSize: 11)
        timestampLabel.textColor = .secondaryLabelColor
        timestampLabel.alignment = .right
        timestampLabel.translatesAutoresizingMaskIntoConstraints = false

        let titleLabel = NSTextField(labelWithString: message.from)
        titleLabel.font = .systemFont(ofSize: 15, weight: .semibold)
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        let bodyLabel = NSTextField(labelWithString: message.body)
        bodyLabel.lineBreakMode = .byWordWrapping
        bodyLabel.maximumNumberOfLines = 5
        bodyLabel.translatesAutoresizingMaskIntoConstraints = false
        let bodyClick = NSClickGestureRecognizer(target: self, action: #selector(openFallbackNotificationDetail))
        bodyLabel.addGestureRecognizer(bodyClick)

        let divider = NSBox()
        divider.boxType = .separator
        divider.translatesAutoresizingMaskIntoConstraints = false

        let copyButton = NSButton(title: "복사", target: self, action: #selector(copyFallbackNotificationMessage))
        copyButton.bezelStyle = .rounded
        copyButton.translatesAutoresizingMaskIntoConstraints = false

        var copyCodeButton: NSButton?
        if verificationCode(in: message) != nil {
            let button = NSButton(title: "인증번호 복사", target: self, action: #selector(copyFallbackNotificationVerificationCode))
            button.bezelStyle = .rounded
            button.translatesAutoresizingMaskIntoConstraints = false
            copyCodeButton = button
        }

        let closeButton = NSButton(title: "닫기", target: panel, action: #selector(NSWindow.close))
        closeButton.bezelStyle = .rounded
        closeButton.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(copyButton)
        if let copyCodeButton {
            container.addSubview(copyCodeButton)
        }
        container.addSubview(chipLabel)
        container.addSubview(timestampLabel)
        container.addSubview(titleLabel)
        container.addSubview(bodyLabel)
        container.addSubview(divider)
        container.addSubview(closeButton)

        NSLayoutConstraint.activate([
            chipLabel.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
            chipLabel.topAnchor.constraint(equalTo: container.topAnchor, constant: 12),
            timestampLabel.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -16),
            timestampLabel.firstBaselineAnchor.constraint(equalTo: chipLabel.firstBaselineAnchor),
            titleLabel.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
            titleLabel.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -16),
            titleLabel.topAnchor.constraint(equalTo: chipLabel.bottomAnchor, constant: 6),
            bodyLabel.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
            bodyLabel.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -16),
            bodyLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 8),
            divider.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 12),
            divider.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -12),
            divider.topAnchor.constraint(equalTo: bodyLabel.bottomAnchor, constant: 12),
            closeButton.topAnchor.constraint(equalTo: divider.bottomAnchor, constant: 10),
            closeButton.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -16),
            closeButton.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -12),
            copyButton.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
            copyButton.centerYAnchor.constraint(equalTo: closeButton.centerYAnchor)
        ])

        if let copyCodeButton {
            NSLayoutConstraint.activate([
                copyCodeButton.leadingAnchor.constraint(equalTo: copyButton.trailingAnchor, constant: 8),
                copyCodeButton.centerYAnchor.constraint(equalTo: closeButton.centerYAnchor)
            ])
        }

        panel.contentView = container

        if let screen = preferredNotificationScreen() {
            let visible = screen.visibleFrame
            var frame = panel.frame
            frame.origin = NSPoint(
                x: visible.maxX - frame.width - 80,
                y: visible.maxY - frame.height
            )
            frame = panel.constrainFrameRect(frame, to: screen)
            panel.setFrame(frame, display: false)
        }

        panel.orderFront(nil)
        fallbackNotificationWindow = panel
        installFallbackDismissMonitor()

        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self, weak panel] in
            guard let panel else { return }
            panel.close()
            if self?.fallbackNotificationWindow === panel {
                self?.clearFallbackNotificationReference()
            }
        }
    }

    private func showFallbackCallNotification(title: String, body: String) {
        fallbackNotificationWindow?.close()
        fallbackNotificationMessage = nil

        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 360, height: 140),
            styleMask: [.titled, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.title = title
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]

        let container = NSView(frame: NSRect(x: 0, y: 0, width: 360, height: 140))

        let bodyLabel = NSTextField(labelWithString: body)
        bodyLabel.lineBreakMode = .byTruncatingTail
        bodyLabel.maximumNumberOfLines = 3
        bodyLabel.translatesAutoresizingMaskIntoConstraints = false

        let closeButton = NSButton(title: "닫기", target: panel, action: #selector(NSWindow.close))
        closeButton.bezelStyle = .rounded
        closeButton.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(bodyLabel)
        container.addSubview(closeButton)

        NSLayoutConstraint.activate([
            bodyLabel.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 14),
            bodyLabel.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -14),
            bodyLabel.topAnchor.constraint(equalTo: container.topAnchor, constant: 14),
            closeButton.topAnchor.constraint(equalTo: bodyLabel.bottomAnchor, constant: 10),
            closeButton.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -14),
            closeButton.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -12)
        ])

        panel.contentView = container

        if let screen = preferredNotificationScreen() {
            let visible = screen.visibleFrame
            var frame = panel.frame
            frame.origin = NSPoint(
                x: visible.maxX - frame.width - 80,
                y: visible.maxY - frame.height
            )
            frame = panel.constrainFrameRect(frame, to: screen)
            panel.setFrame(frame, display: false)
        }

        panel.orderFront(nil)
        fallbackNotificationWindow = panel
        installFallbackDismissMonitor()

        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self, weak panel] in
            guard let panel else { return }
            panel.close()
            if self?.fallbackNotificationWindow === panel {
                self?.clearFallbackNotificationReference()
            }
        }
    }

    @objc private func copyFallbackNotificationMessage() {
        guard let message = fallbackNotificationMessage else {
            return
        }
        copy(message)
        fallbackNotificationWindow?.close()
        clearFallbackNotificationReference()
    }

    @objc private func copyFallbackNotificationVerificationCode() {
        guard let message = fallbackNotificationMessage else {
            return
        }
        copyVerificationCode(from: message)
        fallbackNotificationWindow?.close()
        clearFallbackNotificationReference()
    }

    @objc private func openFallbackNotificationDetail() {
        guard let message = fallbackNotificationMessage else {
            return
        }
        fallbackNotificationWindow?.close()
        clearFallbackNotificationReference()
        openMessageDetail(message)
    }

    private func clearFallbackNotificationReference() {
        removeFallbackDismissMonitor()
        fallbackNotificationWindow = nil
        fallbackNotificationMessage = nil
    }

    private func installFallbackDismissMonitor() {
        removeFallbackDismissMonitor()
        let mask: NSEvent.EventTypeMask = [.leftMouseDown, .rightMouseDown, .otherMouseDown]
        fallbackGlobalClickMonitor = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] _ in
            Task { @MainActor in
                self?.handleFallbackOutsideClick(at: NSEvent.mouseLocation)
            }
        }
        fallbackLocalClickMonitor = NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] event in
            let point: NSPoint
            if let window = event.window {
                point = window.convertPoint(toScreen: event.locationInWindow)
            } else {
                point = NSEvent.mouseLocation
            }
            self?.handleFallbackOutsideClick(at: point)
            return event
        }
    }

    private func removeFallbackDismissMonitor() {
        if let monitor = fallbackGlobalClickMonitor {
            NSEvent.removeMonitor(monitor)
            fallbackGlobalClickMonitor = nil
        }
        if let monitor = fallbackLocalClickMonitor {
            NSEvent.removeMonitor(monitor)
            fallbackLocalClickMonitor = nil
        }
    }

    private func handleFallbackOutsideClick(at screenPoint: NSPoint) {
        guard let panel = fallbackNotificationWindow else {
            removeFallbackDismissMonitor()
            return
        }
        if panel.frame.contains(screenPoint) {
            return
        }
        panel.close()
        clearFallbackNotificationReference()
    }

    private func preferredNotificationScreen() -> NSScreen? {
        let mouseLocation = NSEvent.mouseLocation
        if let screenUnderMouse = NSScreen.screens.first(where: { $0.frame.contains(mouseLocation) }) {
            return screenUnderMouse
        }
        return NSScreen.main ?? NSScreen.screens.first
    }
}
