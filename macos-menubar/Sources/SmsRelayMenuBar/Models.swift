import Foundation

struct SmsMessage: Identifiable, Hashable {
    let id: String
    let timestamp: Date
    let from: String
    let body: String
    let sourcePackage: String
    let conversationKey: String
    let replyKey: String?
}

struct IncomingCallEvent: Identifiable, Hashable {
    let id: String
    let timestamp: Date
    let from: String
    let name: String?

    var displayName: String {
        if let name, !name.isEmpty {
            return name
        }
        return from
    }
}

struct PairingPayload: Codable {
    let version: Int
    let url: String
    let pairingToken: String
    let expiresAtMs: Int64
    let deviceName: String
}
