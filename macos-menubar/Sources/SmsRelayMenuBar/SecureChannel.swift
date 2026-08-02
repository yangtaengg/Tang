import CryptoKit
import Foundation
import Security

enum SecureChannel {
    static let protocolVersion = 2
    private static let authContext = "Tang/auth/v2"
    private static let clientToServerContext = "Tang/client-to-server/v2"
    private static let serverToClientContext = "Tang/server-to-client/v2"
    private static let frameContext = "Tang/frame/v2/"

    struct DirectionalKeys {
        let clientToServer: Data
        let serverToClient: Data
    }

    struct EncryptedFrame {
        let sequence: UInt64
        let nonceBase64: String
        let ciphertextBase64: String
    }

    static func randomNonceBase64(bytes: Int = 32) -> String {
        var data = Data(count: bytes)
        let status = data.withUnsafeMutableBytes { pointer in
            SecRandomCopyBytes(kSecRandomDefault, bytes, pointer.baseAddress!)
        }
        precondition(status == errSecSuccess)
        return data.base64EncodedString()
    }

    static func authProofBase64(
        secret: String,
        clientNonceBase64: String,
        challengeBase64: String,
        device: String,
        appVersion: String
    ) -> String {
        let proof = hmacSha256(
            key: Data(secret.utf8),
            data: authInput(
                clientNonceBase64: clientNonceBase64,
                challengeBase64: challengeBase64,
                device: device,
                appVersion: appVersion
            )
        )
        return proof.base64EncodedString()
    }

    static func proofMatches(expectedBase64: String, providedBase64: String) -> Bool {
        guard let expected = Data(base64Encoded: expectedBase64),
              let provided = Data(base64Encoded: providedBase64),
              expected.count == provided.count else {
            return false
        }
        return zip(expected, provided).reduce(UInt8(0)) { result, pair in
            result | (pair.0 ^ pair.1)
        } == 0
    }

    static func deriveKeys(
        secret: String,
        clientNonceBase64: String,
        challengeBase64: String
    ) throws -> DirectionalKeys {
        guard let clientNonce = Data(base64Encoded: clientNonceBase64), clientNonce.count == 32,
              let challenge = Data(base64Encoded: challengeBase64), challenge.count == 32 else {
            throw SecureChannelError.invalidNonce
        }
        var salt = Data()
        salt.append(clientNonce)
        salt.append(challenge)
        let inputKeyMaterial = Data(secret.utf8)
        return DirectionalKeys(
            clientToServer: hkdfSha256(
                inputKeyMaterial: inputKeyMaterial,
                salt: salt,
                info: Data(clientToServerContext.utf8),
                outputLength: 32
            ),
            serverToClient: hkdfSha256(
                inputKeyMaterial: inputKeyMaterial,
                salt: salt,
                info: Data(serverToClientContext.utf8),
                outputLength: 32
            )
        )
    }

    struct Session {
        private let sendKey: SymmetricKey
        private let receiveKey: SymmetricKey
        private var sendSequence: UInt64 = 0
        private var receiveSequence: UInt64 = 0

        init(sendKey: Data, receiveKey: Data) {
            self.sendKey = SymmetricKey(data: sendKey)
            self.receiveKey = SymmetricKey(data: receiveKey)
        }

        mutating func encrypt(_ plaintext: String) throws -> EncryptedFrame {
            let nonceData = Data((0..<12).map { _ in UInt8.random(in: .min ... .max) })
            return try encrypt(plaintext, nonce: nonceData)
        }

        mutating func encrypt(_ plaintext: String, nonce: Data) throws -> EncryptedFrame {
            guard nonce.count == 12 else {
                throw SecureChannelError.invalidNonce
            }
            let sequence = sendSequence.addingReportingOverflow(1)
            guard !sequence.overflow else {
                throw SecureChannelError.sequenceOverflow
            }
            let sealed = try AES.GCM.seal(
                Data(plaintext.utf8),
                using: sendKey,
                nonce: try AES.GCM.Nonce(data: nonce),
                authenticating: frameAad(sequence.partialValue)
            )
            var ciphertext = sealed.ciphertext
            ciphertext.append(sealed.tag)
            sendSequence = sequence.partialValue
            return EncryptedFrame(
                sequence: sequence.partialValue,
                nonceBase64: nonce.base64EncodedString(),
                ciphertextBase64: ciphertext.base64EncodedString()
            )
        }

        mutating func decrypt(_ frame: EncryptedFrame) -> String? {
            guard frame.sequence == receiveSequence + 1,
                  let nonceData = Data(base64Encoded: frame.nonceBase64), nonceData.count == 12,
                  let ciphertextAndTag = Data(base64Encoded: frame.ciphertextBase64),
                  ciphertextAndTag.count >= 16 else {
                return nil
            }
            let ciphertext = ciphertextAndTag.dropLast(16)
            let tag = ciphertextAndTag.suffix(16)
            guard let sealedBox = try? AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonceData),
                ciphertext: ciphertext,
                tag: tag
            ),
            let plaintext = try? AES.GCM.open(
                sealedBox,
                using: receiveKey,
                authenticating: SecureChannel.frameAad(frame.sequence)
            ),
            let value = String(data: plaintext, encoding: .utf8) else {
                return nil
            }
            receiveSequence = frame.sequence
            return value
        }
    }

    enum SecureChannelError: Error {
        case invalidNonce
        case sequenceOverflow
    }

    private static func authInput(
        clientNonceBase64: String,
        challengeBase64: String,
        device: String,
        appVersion: String
    ) -> Data {
        let fields = [
            authContext,
            clientNonceBase64,
            challengeBase64,
            Data(device.utf8).base64EncodedString(),
            Data(appVersion.utf8).base64EncodedString()
        ]
        return Data(fields.joined(separator: "\n").utf8)
    }

    private static func frameAad(_ sequence: UInt64) -> Data {
        Data("\(frameContext)\(sequence)".utf8)
    }

    private static func hmacSha256(key: Data, data: Data) -> Data {
        let code = HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key))
        return Data(code)
    }

    private static func hkdfSha256(
        inputKeyMaterial: Data,
        salt: Data,
        info: Data,
        outputLength: Int
    ) -> Data {
        precondition((1...32).contains(outputLength))
        let pseudoRandomKey = hmacSha256(key: salt, data: inputKeyMaterial)
        var blockInput = info
        blockInput.append(1)
        return Data(hmacSha256(key: pseudoRandomKey, data: blockInput).prefix(outputLength))
    }
}
