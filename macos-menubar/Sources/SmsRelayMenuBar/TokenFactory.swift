import Foundation
import Security

enum TokenFactory {
    static func randomBase64Token(bytes: Int = 32) -> String {
        var data = Data(count: bytes)
        let status = data.withUnsafeMutableBytes { ptr in
            SecRandomCopyBytes(kSecRandomDefault, bytes, ptr.baseAddress!)
        }
        precondition(status == errSecSuccess)
        return data.base64EncodedString()
    }

    static func randomManualCode() -> String {
        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        var bytes = [UInt8](repeating: 0, count: 12)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess)
        let value = bytes.map { alphabet[Int($0 & 31)] }
        return [0, 4, 8]
            .map { offset in String(value[offset..<(offset + 4)]) }
            .joined(separator: "-")
    }
}
