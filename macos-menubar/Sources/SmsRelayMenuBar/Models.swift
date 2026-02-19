import Foundation

struct SmsMessage: Identifiable, Hashable {
    let id: String
    let timestamp: Date
    let from: String
    let fromPhone: String?
    let body: String
    let sourcePackage: String
    let conversationKey: String
    let replyKey: String?

    var formattedFrom: String {
        formatPhoneNumberForDisplay(from)
    }
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

    var formattedFrom: String {
        formatPhoneNumberForDisplay(from)
    }

    var displayLine: String {
        if let name, !name.isEmpty {
            return "\(name) (\(formattedFrom))"
        }
        return formattedFrom
    }
}

struct PairingPayload: Codable {
    let version: Int
    let url: String
    let pairingToken: String
    let expiresAtMs: Int64
    let deviceName: String
}

private func formatPhoneNumberForDisplay(_ raw: String) -> String {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
        return raw
    }
    let hasPlus = trimmed.hasPrefix("+")
    let digits = trimmed.filter(\.isNumber)
    if digits.count < 7 {
        return raw
    }

    if hasPlus && digits.hasPrefix("82") {
        let local = "0" + String(digits.dropFirst(2))
        return formatLocalKrNumber(local)
    }

    if digits.hasPrefix("0") {
        return formatLocalKrNumber(digits)
    }

    if hasPlus {
        return "+\(digits)"
    }

    return formatByCommonBlocks(digits)
}

private func formatLocalKrNumber(_ digits: String) -> String {
    switch digits.count {
    case 11:
        return "\(digits.prefix(3))-\(digits.dropFirst(3).prefix(4))-\(digits.suffix(4))"
    case 10:
        if digits.hasPrefix("02") {
            return "\(digits.prefix(2))-\(digits.dropFirst(2).prefix(4))-\(digits.suffix(4))"
        }
        return "\(digits.prefix(3))-\(digits.dropFirst(3).prefix(3))-\(digits.suffix(4))"
    case 9:
        if digits.hasPrefix("02") {
            return "\(digits.prefix(2))-\(digits.dropFirst(2).prefix(3))-\(digits.suffix(4))"
        }
        return formatByCommonBlocks(digits)
    default:
        return formatByCommonBlocks(digits)
    }
}

private func formatByCommonBlocks(_ digits: String) -> String {
    switch digits.count {
    case 11:
        return "\(digits.prefix(3))-\(digits.dropFirst(3).prefix(4))-\(digits.suffix(4))"
    case 10:
        return "\(digits.prefix(3))-\(digits.dropFirst(3).prefix(3))-\(digits.suffix(4))"
    default:
        return digits
    }
}
