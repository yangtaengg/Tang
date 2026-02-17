import Foundation
import Security

enum TokenFactory {
    static func randomBase64Token(bytes: Int = 32) -> String {
        var data = Data(count: bytes)
        _ = data.withUnsafeMutableBytes { ptr in
            SecRandomCopyBytes(kSecRandomDefault, bytes, ptr.baseAddress!)
        }
        return data.base64EncodedString()
    }
}
