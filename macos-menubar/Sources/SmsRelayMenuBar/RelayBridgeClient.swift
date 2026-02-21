import Foundation

final class RelayBridgeClient {
    struct Config {
        let relayBaseUrl: String
        let relaySecret: String?
        let token: String
        let pairingCode: String
    }

    var onSmsMessage: ((SmsMessage) -> Void)?
    var onIncomingCall: ((IncomingCallEvent) -> Void)?
    var onReplyResult: ((String?, Bool, String?) -> Void)?
    var onReplySmsResult: ((String?, Bool, String?) -> Void)?
    var onServerStateChanged: ((String) -> Void)?
    var onClientAuthenticated: ((String, String) -> Void)?
    var onAuthenticatedClientCountChanged: ((Int) -> Void)?

    private let queue = DispatchQueue(label: "smsrelay.relay.bridge")
    private let session = URLSession(configuration: .default)
    private var task: URLSessionWebSocketTask?
    private var reconnectWorkItem: DispatchWorkItem?
    private var attempt = 0
    private var authenticated = false
    private var config: Config

    init(config: Config) {
        self.config = config
    }

    func start() {
        queue.async {
            self.connectLocked()
        }
    }

    func stop() {
        queue.async {
            self.reconnectWorkItem?.cancel()
            self.reconnectWorkItem = nil
            self.setAuthenticatedLocked(false)
            self.task?.cancel(with: .normalClosure, reason: nil)
            self.task = nil
            self.onServerStateChanged?("stopped")
        }
    }

    func updateToken(_ token: String) {
        queue.async {
            self.config = Config(
                relayBaseUrl: self.config.relayBaseUrl,
                relaySecret: self.config.relaySecret,
                token: token,
                pairingCode: self.config.pairingCode
            )
            self.reconnectNowLocked()
        }
    }

    func updatePairingCode(_ pairingCode: String) {
        queue.async {
            self.config = Config(
                relayBaseUrl: self.config.relayBaseUrl,
                relaySecret: self.config.relaySecret,
                token: self.config.token,
                pairingCode: pairingCode
            )
            self.reconnectNowLocked()
        }
    }

    func sendSmsReply(replyKey: String?, sourcePackage: String, conversationKey: String, body: String) -> Bool {
        queue.sync {
            guard authenticated else {
                return false
            }
            var payload: [String: Any] = [
                "type": "sms.reply",
                "sourcePackage": sourcePackage,
                "conversationKey": conversationKey,
                "body": body
            ]
            if let replyKey, !replyKey.isEmpty {
                payload["replyKey"] = replyKey
            }
            return sendLocked(payload)
        }
    }

    func sendReplySms(to: String, body: String, sourcePackage: String, conversationKey: String, clientMsgId: String, timestampMs: Int64) -> Bool {
        queue.sync {
            guard authenticated else {
                return false
            }
            return sendLocked([
                "type": "reply_sms",
                "to": to,
                "body": body,
                "sourcePackage": sourcePackage,
                "conversation_id": conversationKey,
                "client_msg_id": clientMsgId,
                "timestamp": timestampMs
            ])
        }
    }

    func sendCallHangup() -> Bool {
        queue.sync {
            guard authenticated else {
                return false
            }
            return sendLocked(["type": "call.hangup"])
        }
    }

    static func relayUrlString(baseUrl: String, room: String, secret: String?) -> String {
        guard var components = URLComponents(string: baseUrl) else {
            return baseUrl
        }
        var items = components.queryItems ?? []
        items.removeAll { $0.name == "room" || $0.name == "secret" }
        items.append(URLQueryItem(name: "room", value: room))
        if let secret, !secret.isEmpty {
            items.append(URLQueryItem(name: "secret", value: secret))
        }
        components.queryItems = items
        return components.url?.absoluteString ?? baseUrl
    }

    private func connectLocked() {
        if task != nil {
            return
        }
        guard let url = URL(string: Self.relayUrlString(baseUrl: config.relayBaseUrl, room: config.pairingCode, secret: config.relaySecret)) else {
            onServerStateChanged?("failed: invalid relay url")
            return
        }
        let webSocketTask = session.webSocketTask(with: url)
        task = webSocketTask
        webSocketTask.resume()
        onServerStateChanged?("relay connecting")
        receiveLocked()
    }

    private func receiveLocked() {
        guard let webSocketTask = task else {
            return
        }

        webSocketTask.receive { [weak self] result in
            guard let self else { return }
            self.queue.async {
                switch result {
                case .failure(let error):
                    self.onServerStateChanged?("relay failed: \(error.localizedDescription)")
                    self.handleDisconnectLocked()
                case .success(let message):
                    self.attempt = 0
                    self.onServerStateChanged?("relay connected")
                    self.handleIncomingLocked(message)
                    self.receiveLocked()
                }
            }
        }
    }

    private func handleIncomingLocked(_ message: URLSessionWebSocketTask.Message) {
        let text: String
        switch message {
        case .string(let value):
            text = value
        case .data(let data):
            text = String(data: data, encoding: .utf8) ?? ""
        @unknown default:
            return
        }

        guard let object = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any],
              let type = object["type"] as? String else {
            return
        }

        if !authenticated {
            guard type == "auth" else {
                return
            }
            let token = object["token"] as? String ?? ""
            if token == config.token || token == config.pairingCode {
                setAuthenticatedLocked(true)
                _ = sendLocked(["type": "auth.ok"])
                let device = object["device"] as? String ?? "Unknown device"
                let appVersion = object["appVersion"] as? String ?? "unknown"
                onClientAuthenticated?(device, appVersion)
            } else {
                _ = sendLocked(["type": "auth.fail", "reason": "invalid token"])
            }
            return
        }

        switch type {
        case "sms.notification":
            guard let id = object["id"] as? String,
                  let timestamp = object["timestamp"] as? Double,
                  let from = object["from"] as? String,
                  let body = object["body"] as? String,
                  let sourcePackage = object["sourcePackage"] as? String else {
                return
            }
            let conversationKey = (object["conversationKey"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            let resolvedConversationKey = (conversationKey?.isEmpty == false) ? (conversationKey ?? from) : from
            let fromPhone = (object["fromPhone"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            let replyKey = object["replyKey"] as? String
            let sms = SmsMessage(
                id: id,
                timestamp: Date(timeIntervalSince1970: timestamp / 1000),
                from: from,
                fromPhone: (fromPhone?.isEmpty == false) ? fromPhone : nil,
                body: body,
                sourcePackage: sourcePackage,
                conversationKey: resolvedConversationKey,
                replyKey: replyKey
            )
            onSmsMessage?(sms)
        case "call.incoming":
            let id = object["id"] as? String ?? UUID().uuidString
            let timestampMs = object["timestamp"] as? Double ?? Date().timeIntervalSince1970 * 1000
            let from = (object["from"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            let name = (object["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            let resolvedFrom = (from?.isEmpty == false) ? (from ?? "Unknown caller") : "Unknown caller"
            let resolvedName = (name?.isEmpty == false) ? name : nil
            onIncomingCall?(IncomingCallEvent(
                id: id,
                timestamp: Date(timeIntervalSince1970: timestampMs / 1000),
                from: resolvedFrom,
                name: resolvedName
            ))
        case "ping":
            _ = sendLocked(["type": "pong"])
        case "sms.reply.result":
            onReplyResult?(object["replyKey"] as? String, object["success"] as? Bool ?? false, object["reason"] as? String)
        case "reply_sms.result":
            onReplySmsResult?(object["client_msg_id"] as? String, object["success"] as? Bool ?? false, object["reason"] as? String)
        case "auth.ok":
            break
        case "auth.fail":
            setAuthenticatedLocked(false)
        default:
            break
        }
    }

    private func sendLocked(_ object: [String: Any]) -> Bool {
        guard let task else {
            return false
        }
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else {
            return false
        }
        task.send(.string(text)) { [weak self] error in
            if let error {
                self?.queue.async {
                    self?.onServerStateChanged?("relay send failed: \(error.localizedDescription)")
                }
            }
        }
        return true
    }

    private func reconnectNowLocked() {
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        setAuthenticatedLocked(false)
        connectLocked()
    }

    private func handleDisconnectLocked() {
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        setAuthenticatedLocked(false)
        scheduleReconnectLocked()
    }

    private func scheduleReconnectLocked() {
        reconnectWorkItem?.cancel()
        let delay = min(30, 1 << min(attempt, 6))
        attempt += 1
        let workItem = DispatchWorkItem { [weak self] in
            self?.queue.async {
                self?.connectLocked()
            }
        }
        reconnectWorkItem = workItem
        queue.asyncAfter(deadline: .now() + .seconds(delay), execute: workItem)
    }

    private func setAuthenticatedLocked(_ value: Bool) {
        guard authenticated != value else {
            return
        }
        authenticated = value
        onAuthenticatedClientCountChanged?(value ? 1 : 0)
    }
}
