import Foundation
import Network

final class WebSocketServer {
    struct Config {
        let port: UInt16
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

    private let queue = DispatchQueue(label: "smsrelay.ws.server")
    private var listener: NWListener?
    private var clients: [UUID: NWConnection] = [:]
    private var authenticatedClients: Set<UUID> = []
    private var recentlySeenIds: [String: Date] = [:]
    private var config: Config

    init(config: Config) {
        self.config = config
    }

    func updateToken(_ token: String) {
        config = Config(port: config.port, token: token, pairingCode: config.pairingCode)
    }

    func updatePairingCode(_ pairingCode: String) {
        config = Config(port: config.port, token: config.token, pairingCode: pairingCode)
    }

    func start() {
        queue.async {
            do {
                let parameters = NWParameters.tcp
                let wsOptions = NWProtocolWebSocket.Options()
                wsOptions.autoReplyPing = true
                parameters.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

                let port = NWEndpoint.Port(rawValue: self.config.port) ?? 8765
                let listener = try NWListener(using: parameters, on: port)
                self.listener = listener

                listener.stateUpdateHandler = { [weak self] state in
                    self?.onServerStateChanged?("\(state)")
                }

                listener.newConnectionHandler = { [weak self] connection in
                    self?.accept(connection)
                }
                listener.start(queue: self.queue)
            } catch {
                self.onServerStateChanged?("failed: \(error.localizedDescription)")
            }
        }
    }

    func stop() {
        queue.async {
            self.listener?.cancel()
            self.listener = nil
            self.clients.values.forEach { $0.cancel() }
            self.clients.removeAll()
            self.authenticatedClients.removeAll()
            self.notifyAuthenticatedClientCountChanged()
        }
    }

    func sendSmsReply(
        replyKey: String?,
        sourcePackage: String,
        conversationKey: String,
        body: String
    ) -> Bool {
        queue.sync {
            guard let clientId = authenticatedClients.first,
                  let connection = clients[clientId] else {
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
            send(payload, to: connection)
            return true
        }
    }

    func sendReplySms(
        to: String,
        body: String,
        sourcePackage: String,
        conversationKey: String,
        clientMsgId: String,
        timestampMs: Int64
    ) -> Bool {
        queue.sync {
            guard let clientId = authenticatedClients.first,
                  let connection = clients[clientId] else {
                return false
            }
            let payload: [String: Any] = [
                "type": "reply_sms",
                "to": to,
                "body": body,
                "sourcePackage": sourcePackage,
                "conversation_id": conversationKey,
                "client_msg_id": clientMsgId,
                "timestamp": timestampMs
            ]
            send(payload, to: connection)
            return true
        }
    }

    func sendCallHangup() -> Bool {
        queue.sync {
            guard let clientId = authenticatedClients.first,
                  let connection = clients[clientId] else {
                return false
            }
            let payload: [String: Any] = [
                "type": "call.hangup"
            ]
            send(payload, to: connection)
            return true
        }
    }

    private func accept(_ connection: NWConnection) {
        let id = UUID()
        clients[id] = connection

        connection.stateUpdateHandler = { [weak self] state in
            if case .failed = state {
                self?.dropClient(id)
            }
            if case .cancelled = state {
                self?.dropClient(id)
            }
        }

        connection.start(queue: queue)
        receive(on: connection, id: id)
    }

    private func dropClient(_ id: UUID) {
        guard let connection = clients.removeValue(forKey: id) else {
            return
        }
        connection.cancel()
        let removed = authenticatedClients.remove(id) != nil
        if removed {
            notifyAuthenticatedClientCountChanged()
        }
    }

    private func receive(on connection: NWConnection, id: UUID) {
        connection.receiveMessage { [weak self] data, context, _, error in
            guard let self else { return }
            if error != nil {
                self.dropClient(id)
                return
            }
            guard let data else {
                self.receive(on: connection, id: id)
                return
            }
            self.handle(data: data, context: context, clientId: id, connection: connection)
            self.receive(on: connection, id: id)
        }
    }

    private func handle(data: Data, context: NWConnection.ContentContext?, clientId: UUID, connection: NWConnection) {
        guard isTextFrame(context),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = object["type"] as? String else {
            return
        }

        if !authenticatedClients.contains(clientId) {
            guard type == "auth" else {
                send(["type": "auth.fail", "reason": "missing auth"], to: connection)
                return
            }
            let token = object["token"] as? String ?? ""
            if token == config.token || token == config.pairingCode {
                authenticatedClients.insert(clientId)
                notifyAuthenticatedClientCountChanged()
                send(["type": "auth.ok"], to: connection)
                let device = object["device"] as? String ?? "Unknown device"
                let appVersion = object["appVersion"] as? String ?? "unknown"
                onClientAuthenticated?(device, appVersion)
            } else {
                send(["type": "auth.fail", "reason": "invalid token"], to: connection)
                dropClient(clientId)
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
            if isDuplicate(messageId: id) {
                return
            }
            let message = SmsMessage(
                id: id,
                timestamp: Date(timeIntervalSince1970: timestamp / 1000),
                from: from,
                fromPhone: (fromPhone?.isEmpty == false) ? fromPhone : nil,
                body: body,
                sourcePackage: sourcePackage,
                conversationKey: resolvedConversationKey,
                replyKey: replyKey
            )
            onSmsMessage?(message)
        case "call.incoming":
            let id = object["id"] as? String ?? UUID().uuidString
            let timestampMs = object["timestamp"] as? Double ?? Date().timeIntervalSince1970 * 1000
            let from = (object["from"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            let name = (object["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            let resolvedFrom = (from?.isEmpty == false) ? (from ?? "Unknown caller") : "Unknown caller"
            let resolvedName = (name?.isEmpty == false) ? name : nil
            let callEvent = IncomingCallEvent(
                id: id,
                timestamp: Date(timeIntervalSince1970: timestampMs / 1000),
                from: resolvedFrom,
                name: resolvedName
            )
            onIncomingCall?(callEvent)
        case "ping":
            send(["type": "pong"], to: connection)
        case "sms.reply.result":
            let replyKey = object["replyKey"] as? String
            let success = object["success"] as? Bool ?? false
            let reason = object["reason"] as? String
            onReplyResult?(replyKey, success, reason)
        case "reply_sms.result":
            let clientMsgId = object["client_msg_id"] as? String
            let success = object["success"] as? Bool ?? false
            let reason = object["reason"] as? String
            onReplySmsResult?(clientMsgId, success, reason)
        default:
            return
        }
    }

    private func isDuplicate(messageId: String) -> Bool {
        let now = Date()
        recentlySeenIds = recentlySeenIds.filter { now.timeIntervalSince($0.value) <= 90 }
        if recentlySeenIds[messageId] != nil {
            return true
        }
        recentlySeenIds[messageId] = now
        return false
    }

    private func send(_ object: [String: Any], to connection: NWConnection) {
        guard let data = try? JSONSerialization.data(withJSONObject: object) else {
            return
        }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "json", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .idempotent)
    }

    private func isTextFrame(_ context: NWConnection.ContentContext?) -> Bool {
        guard let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata else {
            return false
        }
        return metadata.opcode == .text
    }

    private func notifyAuthenticatedClientCountChanged() {
        onAuthenticatedClientCountChanged?(authenticatedClients.count)
    }
}
