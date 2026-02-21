import AppKit
import CryptoKit
import Foundation
import SwiftUI
import UserNotifications

@MainActor
final class AppState: ObservableObject {
    @Published var serverState: String = "stopped"
    @Published private(set) var pairingHost: String = LocalNetworkInfo.defaultIPv4()
    @Published var pairingPort: UInt16 = 8765
    @Published var pairingExpiresAt: Date = .distantFuture
    @Published private(set) var pairingCode: String = "000000"
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
        window.isOpaque = false
        window.backgroundColor = .clear
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
    private let fallbackToastDuration: TimeInterval = 8
    private static let nonExpiringExpiresAtMs: Int64 = 253402300799000

    init() {
        let initialPort: UInt16 = 8765
        let existing = KeychainStore.loadToken()
        token = existing ?? TokenFactory.randomBase64Token()
        if existing == nil {
            try? KeychainStore.saveToken(token)
        }

        pairingPort = initialPort
        let initialPairingCode = Self.makePairingCode(from: token)
        pairingCode = initialPairingCode
        server = WebSocketServer(config: .init(port: initialPort, token: token, pairingCode: initialPairingCode))
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
        pairingCode = Self.makePairingCode(from: token)
        server.updatePairingCode(pairingCode)
        pairedDeviceName = nil
        pairedAppVersion = nil
        refreshPairingQR()
    }

    private static func makePairingCode(from token: String) -> String {
        let digest = SHA256.hash(data: Data(token.utf8))
        let value = digest.prefix(4).reduce(0) { ($0 << 8) | UInt64($1) }
        let code = value % 1_000_000
        return String(format: "%06llu", code)
    }

    func openPairDeviceWindow() {
        refreshPairingQR()
        let window = ensurePairDeviceWindow()
        let app = NSRunningApplication.current
        app.activate(options: [.activateAllWindows, .activateIgnoringOtherApps])
        NSApp.activate(ignoringOtherApps: true)
        window.level = .normal
        window.makeKeyAndOrderFront(nil)
    }

    func refreshPairingQR() {
        pairingHost = LocalNetworkInfo.defaultIPv4()
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
        playTrashEmptySound()
        if selectedMessage?.id == message.id {
            selectedMessage = nil
        }
    }

    func clearAllMessages() {
        guard !messageStore.messages.isEmpty else {
            return
        }
        messageStore.removeAll()
        playTrashEmptySound()
        selectedMessage = nil
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
        showFallbackNotification(message: message)
        playAlertSound(.sms)

        if Bundle.main.bundleURL.pathExtension == "app" {
            let content = UNMutableNotificationContent()
            content.title = message.from
            content.body = message.body
            content.sound = .default
            let request = UNNotificationRequest(
                identifier: message.id,
                content: content,
                trigger: nil
            )
            UNUserNotificationCenter.current().add(request)
        }
    }

    private func notifyIncomingCall(_ call: IncomingCallEvent) {
        let title = "Incoming Call"
        let body = call.displayLine
        playAlertSound(.call)

        guard Bundle.main.bundleURL.pathExtension == "app" else {
            showFallbackCallNotification(
                title: title,
                body: body,
                onHangUp: { [weak self] in
                    _ = self?.server.sendCallHangup()
                }
            )
            return
        }

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(
            identifier: "call-\(call.id)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    private enum AlertSoundKind {
        case sms
        case call
    }

    private func playAlertSound(_ kind: AlertSoundKind) {
        let customName = kind == .call ? "iphone_ringtone" : "iphone_sms"
        if let custom = NSSound(named: customName) {
            custom.play()
            return
        }

        let fallbackNames: [String] = kind == .call
            ? ["Submarine", "Funk", "Ping"]
            : ["Pop", "Glass", "Hero", "Ping"]

        for name in fallbackNames {
            if let sound = NSSound(named: name) {
                sound.play()
                return
            }
        }
    }

    private func playTrashEmptySound() {
        let finderTrashPath = "/System/Library/Components/CoreAudio.component/Contents/SharedSupport/SystemSounds/finder/empty trash.aif"
        let finderTrashUrl = URL(fileURLWithPath: finderTrashPath)
        if let finderTrashSound = NSSound(contentsOf: finderTrashUrl, byReference: true) {
            finderTrashSound.play()
            return
        }

        if let fallback = NSSound(named: "Glass") {
            fallback.play()
        }
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
        window.isOpaque = false
        window.backgroundColor = .clear
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
        panel.level = .statusBar
        panel.hasShadow = true
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]

        panel.contentView = NSHostingView(rootView: FallbackMessageToastView(
            message: message,
            hasVerificationCode: verificationCode(in: message) != nil,
            onOpenDetail: { [weak self] in
                self?.openFallbackNotificationDetail()
            },
            onCopy: { [weak self] in
                self?.copyFallbackNotificationMessage()
            },
            onCopyCode: { [weak self] in
                self?.copyFallbackNotificationVerificationCode()
            },
            onClose: { [weak self] in
                self?.closeFallbackPanelIfActive(panel)
            }
        ))

        if let screen = preferredNotificationScreen() {
            let visible = screen.visibleFrame
            var frame = panel.frame
            frame.origin = NSPoint(
                x: visible.maxX - frame.width,
                y: visible.maxY - frame.height
            )
            frame = panel.constrainFrameRect(frame, to: screen)
            panel.setFrame(frame, display: false)
        }

        panel.orderFrontRegardless()
        fallbackNotificationWindow = panel
        installFallbackDismissMonitor()

        DispatchQueue.main.asyncAfter(deadline: .now() + fallbackToastDuration) { [weak self, weak panel] in
            guard let panel else { return }
            self?.closeFallbackPanelIfActive(panel)
        }
    }

    private func showFallbackCallNotification(
        title: String,
        body: String,
        onHangUp: @escaping () -> Void
    ) {
        fallbackNotificationWindow?.close()
        fallbackNotificationMessage = nil

        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 360, height: 140),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isFloatingPanel = true
        panel.level = .statusBar
        panel.hasShadow = true
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]

        panel.contentView = NSHostingView(rootView: FallbackCallToastView(
            title: title,
            messageText: body,
            onHangUp: onHangUp,
            onClose: { [weak self] in
                self?.closeFallbackPanelIfActive(panel)
            }
        ))

        if let screen = preferredNotificationScreen() {
            let visible = screen.visibleFrame
            var frame = panel.frame
            frame.origin = NSPoint(
                x: visible.maxX - frame.width,
                y: visible.maxY - frame.height
            )
            frame = panel.constrainFrameRect(frame, to: screen)
            panel.setFrame(frame, display: false)
        }

        panel.orderFrontRegardless()
        fallbackNotificationWindow = panel
        installFallbackDismissMonitor()

        DispatchQueue.main.asyncAfter(deadline: .now() + fallbackToastDuration) { [weak self, weak panel] in
            guard let panel else { return }
            self?.closeFallbackPanelIfActive(panel)
        }
    }

    @objc private func copyFallbackNotificationMessage() {
        guard let message = fallbackNotificationMessage else {
            return
        }
        copy(message)
        closeFallbackPanelIfActive(fallbackNotificationWindow as? NSPanel)
    }

    @objc private func copyFallbackNotificationVerificationCode() {
        guard let message = fallbackNotificationMessage else {
            return
        }
        copyVerificationCode(from: message)
        closeFallbackPanelIfActive(fallbackNotificationWindow as? NSPanel)
    }

    @objc private func openFallbackNotificationDetail() {
        guard let message = fallbackNotificationMessage else {
            return
        }
        closeFallbackPanelIfActive(fallbackNotificationWindow as? NSPanel)
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

    private func closeFallbackPanelIfActive(_ panel: NSPanel?) {
        guard let panel,
              fallbackNotificationWindow === panel else {
            return
        }
        clearFallbackNotificationReference()
        DispatchQueue.main.async {
            panel.close()
        }
    }

    private func preferredNotificationScreen() -> NSScreen? {
        let mouseLocation = NSEvent.mouseLocation
        if let screenUnderMouse = NSScreen.screens.first(where: { $0.frame.contains(mouseLocation) }) {
            return screenUnderMouse
        }
        return NSScreen.main ?? NSScreen.screens.first
    }
}

private struct FallbackMessageToastView: View {
    let message: SmsMessage
    let hasVerificationCode: Bool
    let onOpenDetail: () -> Void
    let onCopy: () -> Void
    let onCopyCode: () -> Void
    let onClose: () -> Void

    private var timeText: String {
        message.timestamp.formatted(date: .omitted, time: .shortened)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Circle()
                    .fill(Color.accentColor.opacity(0.85))
                    .frame(width: 7, height: 7)
                Text("New message")
                    .font(.system(size: 12, weight: .semibold))
                Spacer()
                Text(timeText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Button(action: onOpenDetail) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(message.formattedFrom)
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                    Text(message.body)
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(Color.white.opacity(0.24), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            .buttonStyle(.plain)

            HStack(spacing: 6) {
                Button("Copy", action: onCopy)
                if hasVerificationCode {
                    Button("Copy code", action: onCopyCode)
                }
                Spacer()
                Button("Close", action: onClose)
            }
            .font(.system(size: 12, weight: .semibold))
            .controlSize(.regular)
            .buttonStyle(.bordered)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .frame(width: 380, height: 162)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(.ultraThinMaterial)
                .background(Color(nsColor: .windowBackgroundColor).opacity(0.45), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(Color.white.opacity(0.35), lineWidth: 1)
                )
                .shadow(color: Color.black.opacity(0.14), radius: 16, x: 0, y: 9)
        )
    }
}

private struct FallbackCallToastView: View {
    let title: String
    let messageText: String
    let onHangUp: () -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Circle()
                    .fill(Color.green.opacity(0.85))
                    .frame(width: 7, height: 7)
                Text(title)
                    .font(.system(size: 12, weight: .semibold))
                Spacer()
            }

            Text(messageText)
                .font(.system(size: 12))
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(Color.white.opacity(0.24), in: RoundedRectangle(cornerRadius: 10, style: .continuous))

            HStack(spacing: 8) {
                Button("Hang up") {
                    onHangUp()
                    onClose()
                }
                .font(.system(size: 12, weight: .semibold))
                .controlSize(.regular)
                .buttonStyle(.borderedProminent)

                Spacer()
                Button("Close", action: onClose)
                    .font(.system(size: 12, weight: .semibold))
                    .controlSize(.regular)
                    .buttonStyle(.bordered)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .frame(width: 360, height: 120)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(.ultraThinMaterial)
                .background(Color(nsColor: .windowBackgroundColor).opacity(0.45), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(Color.white.opacity(0.35), lineWidth: 1)
                )
                .shadow(color: Color.black.opacity(0.14), radius: 16, x: 0, y: 9)
        )
    }
}
