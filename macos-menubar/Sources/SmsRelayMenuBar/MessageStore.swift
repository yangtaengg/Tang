import Foundation

@MainActor
final class MessageStore: ObservableObject {
    @Published private(set) var messages: [SmsMessage] = []

    var conversationHeads: [SmsMessage] {
        var seen = Set<String>()
        var heads: [SmsMessage] = []
        for message in messages {
            let key = conversationID(for: message)
            if seen.insert(key).inserted {
                heads.append(message)
            }
        }
        return heads
    }

    func append(_ message: SmsMessage) {
        messages.insert(message, at: 0)
        if messages.count > 10 {
            messages = Array(messages.prefix(10))
        }
    }

    func remove(messageID: String) {
        messages.removeAll { $0.id == messageID }
    }

    func removeConversation(containing seed: SmsMessage) {
        let key = conversationID(for: seed)
        messages.removeAll { conversationID(for: $0) == key }
    }

    func messages(inConversationWith seed: SmsMessage) -> [SmsMessage] {
        let key = conversationID(for: seed)
        return messages
            .filter { conversationID(for: $0) == key }
            .sorted { $0.timestamp < $1.timestamp }
    }

    func latestMessage(inConversationWith seed: SmsMessage) -> SmsMessage? {
        let key = conversationID(for: seed)
        return messages.first { conversationID(for: $0) == key }
    }

    func conversationID(for message: SmsMessage) -> String {
        if let normalizedPhone = normalizedPhone(from: message.fromPhone), normalizedPhone.count >= 7 {
            return "phone:\(normalizedPhone)"
        }
        if let normalizedPhone = normalizedPhone(from: message.from), normalizedPhone.count >= 7 {
            return "phone:\(normalizedPhone)"
        }
        let key = message.conversationKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if !key.isEmpty {
            return "conversation:\(key.lowercased())"
        }
        return "from:\(message.from.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())"
    }

    private func normalizedPhone(from raw: String?) -> String? {
        let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if value.isEmpty {
            return nil
        }
        let digits = value.filter(\.isNumber)
        if digits.count >= 7 {
            return digits
        }
        return nil
    }

    func removeAll() {
        messages.removeAll()
    }
}
