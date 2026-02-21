import Foundation

@MainActor
final class MessageStore: ObservableObject {
    @Published private(set) var messages: [SmsMessage] = []

    func append(_ message: SmsMessage) {
        messages.insert(message, at: 0)
        if messages.count > 10 {
            messages = Array(messages.prefix(10))
        }
    }

    func remove(messageID: String) {
        messages.removeAll { $0.id == messageID }
    }

    func removeAll() {
        messages.removeAll()
    }
}
