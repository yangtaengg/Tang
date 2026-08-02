import Foundation
import Network

final class WebSocketServer {
    struct Config {
        let port: UInt16
        let token: String
    }

    private struct PendingAuth {
        let clientNonceBase64: String
        let challengeBase64: String
        let device: String
        let appVersion: String
    }

    private struct PairingWindow {
        let qrSecret: String
        let manualCode: String
        let expiresAt: Date
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
    private var pendingAuthByClient: [UUID: PendingAuth] = [:]
    private var secureSessionsByClient: [UUID: SecureChannel.Session] = [:]
    private var lastSeenByClient: [UUID: Date] = [:]
    private var recentlySeenIds: [String: Date] = [:]
    private var recentAuthFailures: [Date] = []
    private var config: Config
    private var pairingWindow: PairingWindow?
    private var staleSweepTimer: DispatchSourceTimer?
    private let staleClientTimeout: TimeInterval = 95
    private let staleClientSweepInterval: TimeInterval = 15
    private let authFailureWindow: TimeInterval = 60
    private let maxAuthFailuresPerWindow = 10
    private let maxFrameBytes = 256 * 1024

    init(config: Config) {
        self.config = config
    }

    func updateToken(_ token: String) {
        queue.async {
            self.config = Config(port: self.config.port, token: token)
        }
    }

    func beginPairing(qrSecret: String, manualCode: String, expiresAt: Date) {
        queue.async {
            self.pairingWindow = PairingWindow(
                qrSecret: qrSecret,
                manualCode: manualCode,
                expiresAt: expiresAt
            )
        }
    }

    func endPairing() {
        queue.async {
            self.pairingWindow = nil
        }
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
                self.startStaleClientSweepIfNeeded()
            } catch {
                self.onServerStateChanged?("failed: \(error.localizedDescription)")
            }
        }
    }

    func stop() {
        queue.async {
            self.listener?.cancel()
            self.listener = nil
            self.staleSweepTimer?.cancel()
            self.staleSweepTimer = nil
            self.clients.values.forEach { $0.cancel() }
            self.clients.removeAll()
            self.authenticatedClients.removeAll()
            self.pendingAuthByClient.removeAll()
            self.secureSessionsByClient.removeAll()
            self.lastSeenByClient.removeAll()
            self.pairingWindow = nil
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
                  clients[clientId] != nil else {
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
            return sendSecure(payload, to: clientId)
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
                  clients[clientId] != nil else {
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
            return sendSecure(payload, to: clientId)
        }
    }

    func sendCallHangup() -> Bool {
        queue.sync {
            guard let clientId = authenticatedClients.first,
                  clients[clientId] != nil else {
                return false
            }
            let payload: [String: Any] = [
                "type": "call.hangup"
            ]
            return sendSecure(payload, to: clientId)
        }
    }

    private func accept(_ connection: NWConnection) {
        let id = UUID()
        clients[id] = connection
        lastSeenByClient[id] = Date()

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
        pendingAuthByClient.removeValue(forKey: id)
        secureSessionsByClient.removeValue(forKey: id)
        lastSeenByClient.removeValue(forKey: id)
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
            self.lastSeenByClient[id] = Date()
            self.handle(data: data, context: context, clientId: id, connection: connection)
            self.receive(on: connection, id: id)
        }
    }

    private func handle(data: Data, context: NWConnection.ContentContext?, clientId: UUID, connection: NWConnection) {
        guard data.count <= maxFrameBytes,
              isTextFrame(context),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = object["type"] as? String else {
            dropClient(clientId)
            return
        }

        if !authenticatedClients.contains(clientId) {
            handleAuthentication(
                object: object,
                type: type,
                clientId: clientId,
                connection: connection
            )
            return
        }

        guard type == "secure",
              let sequenceNumber = object["seq"] as? NSNumber,
              let nonceBase64 = object["nonce"] as? String,
              let ciphertextBase64 = object["ciphertext"] as? String,
              var session = secureSessionsByClient[clientId] else {
            dropClient(clientId)
            return
        }
        let frame = SecureChannel.EncryptedFrame(
            sequence: sequenceNumber.uint64Value,
            nonceBase64: nonceBase64,
            ciphertextBase64: ciphertextBase64
        )
        guard let plaintext = session.decrypt(frame),
              let plaintextData = plaintext.data(using: .utf8),
              plaintextData.count <= maxFrameBytes,
              let secureObject = (try? JSONSerialization.jsonObject(with: plaintextData)) as? [String: Any],
              let secureType = secureObject["type"] as? String else {
            dropClient(clientId)
            return
        }
        secureSessionsByClient[clientId] = session
        handleAuthenticatedObject(secureObject, type: secureType, clientId: clientId)
    }

    private func handleAuthentication(
        object: [String: Any],
        type: String,
        clientId: UUID,
        connection: NWConnection
    ) {
        guard !authFailureLimitReached() else {
            sendPlain(["type": "auth.fail", "reason": "rate limited"], to: connection)
            dropClient(clientId)
            return
        }

        switch type {
        case "auth.hello":
            guard pendingAuthByClient[clientId] == nil,
                  (object["version"] as? NSNumber)?.intValue == SecureChannel.protocolVersion,
                  let clientNonceBase64 = object["clientNonce"] as? String,
                  let clientNonce = Data(base64Encoded: clientNonceBase64),
                  clientNonce.count == 32,
                  let device = boundedString(object["device"], maxLength: 200),
                  let appVersion = boundedString(object["appVersion"], maxLength: 100) else {
                failAuthentication(clientId: clientId, connection: connection, reason: "invalid hello")
                return
            }
            let challengeBase64 = SecureChannel.randomNonceBase64()
            pendingAuthByClient[clientId] = PendingAuth(
                clientNonceBase64: clientNonceBase64,
                challengeBase64: challengeBase64,
                device: device,
                appVersion: appVersion
            )
            sendPlain(
                [
                    "type": "auth.challenge",
                    "version": SecureChannel.protocolVersion,
                    "challenge": challengeBase64
                ],
                to: connection
            )
        case "auth.proof":
            guard let pending = pendingAuthByClient.removeValue(forKey: clientId),
                  let providedProof = object["proof"] as? String,
                  providedProof.count <= 128 else {
                failAuthentication(clientId: clientId, connection: connection, reason: "invalid proof")
                return
            }
            let matched = candidateSecrets().first { candidate in
                let expected = SecureChannel.authProofBase64(
                    secret: candidate.secret,
                    clientNonceBase64: pending.clientNonceBase64,
                    challengeBase64: pending.challengeBase64,
                    device: pending.device,
                    appVersion: pending.appVersion
                )
                return SecureChannel.proofMatches(
                    expectedBase64: expected,
                    providedBase64: providedProof
                )
            }
            guard let matched,
                  let keys = try? SecureChannel.deriveKeys(
                    secret: matched.secret,
                    clientNonceBase64: pending.clientNonceBase64,
                    challengeBase64: pending.challengeBase64
                  ) else {
                failAuthentication(clientId: clientId, connection: connection, reason: "invalid credential")
                return
            }
            secureSessionsByClient[clientId] = SecureChannel.Session(
                sendKey: keys.serverToClient,
                receiveKey: keys.clientToServer
            )
            authenticatedClients.insert(clientId)
            lastSeenByClient[clientId] = Date()
            notifyAuthenticatedClientCountChanged()
            sendPlain(
                [
                    "type": "auth.ok",
                    "version": SecureChannel.protocolVersion,
                    "secure": true
                ],
                to: connection
            )
            if matched.isPairingCredential {
                _ = sendSecure(
                    [
                        "type": "pairing.complete",
                        "token": config.token
                    ],
                    to: clientId
                )
                pairingWindow = nil
            }
            onClientAuthenticated?(pending.device, pending.appVersion)
        default:
            failAuthentication(clientId: clientId, connection: connection, reason: "protocol upgrade required")
        }
    }

    private func handleAuthenticatedObject(_ object: [String: Any], type: String, clientId: UUID) {
        switch type {
        case "sms.notification":
            guard let id = boundedString(object["id"], maxLength: 128),
                  let timestamp = (object["timestamp"] as? NSNumber)?.doubleValue,
                  let from = boundedString(object["from"], maxLength: 512),
                  let body = boundedString(object["body"], maxLength: 32_768),
                  let sourcePackage = boundedString(object["sourcePackage"], maxLength: 256) else {
                return
            }
            let conversationKey = boundedString(object["conversationKey"], maxLength: 1_024)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let resolvedConversationKey = (conversationKey?.isEmpty == false) ? (conversationKey ?? from) : from
            let fromPhone = boundedString(object["fromPhone"], maxLength: 100)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let replyKey = boundedString(object["replyKey"], maxLength: 1_024)
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
            let id = boundedString(object["id"], maxLength: 128) ?? UUID().uuidString
            let timestampMs = (object["timestamp"] as? NSNumber)?.doubleValue ?? Date().timeIntervalSince1970 * 1000
            let from = boundedString(object["from"], maxLength: 512)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let name = boundedString(object["name"], maxLength: 512)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
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
            _ = sendSecure(["type": "pong"], to: clientId)
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

    private func candidateSecrets() -> [(secret: String, isPairingCredential: Bool)] {
        var candidates = [(secret: config.token, isPairingCredential: false)]
        guard let pairingWindow, pairingWindow.expiresAt > Date() else {
            self.pairingWindow = nil
            return candidates
        }
        candidates.append((secret: pairingWindow.qrSecret, isPairingCredential: true))
        candidates.append((secret: pairingWindow.manualCode, isPairingCredential: true))
        return candidates
    }

    private func authFailureLimitReached() -> Bool {
        let cutoff = Date().addingTimeInterval(-authFailureWindow)
        recentAuthFailures.removeAll { $0 < cutoff }
        return recentAuthFailures.count >= maxAuthFailuresPerWindow
    }

    private func failAuthentication(clientId: UUID, connection: NWConnection, reason: String) {
        recentAuthFailures.append(Date())
        sendPlain(["type": "auth.fail", "reason": reason], to: connection)
        dropClient(clientId)
    }

    private func boundedString(_ value: Any?, maxLength: Int) -> String? {
        guard let value = value as? String, !value.isEmpty, value.count <= maxLength else {
            return nil
        }
        return value
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

    @discardableResult
    private func sendSecure(_ object: [String: Any], to clientId: UUID) -> Bool {
        guard let connection = clients[clientId],
              var session = secureSessionsByClient[clientId],
              let data = try? JSONSerialization.data(withJSONObject: object),
              data.count <= maxFrameBytes,
              let plaintext = String(data: data, encoding: .utf8),
              let frame = try? session.encrypt(plaintext) else {
            return false
        }
        secureSessionsByClient[clientId] = session
        sendPlain(
            [
                "type": "secure",
                "seq": frame.sequence,
                "nonce": frame.nonceBase64,
                "ciphertext": frame.ciphertextBase64
            ],
            to: connection
        )
        return true
    }

    private func sendPlain(_ object: [String: Any], to connection: NWConnection) {
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

    private func startStaleClientSweepIfNeeded() {
        guard staleSweepTimer == nil else {
            return
        }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + staleClientSweepInterval, repeating: staleClientSweepInterval)
        timer.setEventHandler { [weak self] in
            self?.dropStaleAuthenticatedClients()
        }
        staleSweepTimer = timer
        timer.resume()
    }

    private func dropStaleAuthenticatedClients() {
        guard !authenticatedClients.isEmpty else {
            return
        }
        let now = Date()
        let staleIds = authenticatedClients.filter { id in
            guard let lastSeen = lastSeenByClient[id] else {
                return true
            }
            return now.timeIntervalSince(lastSeen) > staleClientTimeout
        }
        staleIds.forEach { dropClient($0) }
    }
}
