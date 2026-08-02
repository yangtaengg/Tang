import Foundation
import XCTest
@testable import SmsRelayMenuBar

final class SecureChannelTests: XCTestCase {
    private let secret = "production-test-secret"
    private let clientNonce = Data((0..<32).map(UInt8.init)).base64EncodedString()
    private let challenge = Data((32..<64).map(UInt8.init)).base64EncodedString()

    func testAuthProofMatchesAndroidVector() {
        let proof = SecureChannel.authProofBase64(
            secret: secret,
            clientNonceBase64: clientNonce,
            challengeBase64: challenge,
            device: "Galaxy S24",
            appVersion: "1.0.0"
        )

        XCTAssertEqual(proof, "U53AW7SBWPFauEd2+v7ZKg8zSngElbpXrnLaaz0DXs0=")
        XCTAssertTrue(SecureChannel.proofMatches(expectedBase64: proof, providedBase64: proof))
    }

    func testDirectionalSessionsEncryptAndRejectReplay() throws {
        let keys = try SecureChannel.deriveKeys(
            secret: secret,
            clientNonceBase64: clientNonce,
            challengeBase64: challenge
        )
        XCTAssertEqual(keys.clientToServer.base64EncodedString(), "sO4T5VGY8+mHmRBigLo+GUEM6TBhFRLbZRSEnLoVegA=")
        XCTAssertEqual(keys.serverToClient.base64EncodedString(), "dcjEqyPIZgH3Yco+uEKyJc0+K808yuPFNDUzpd9YzfY=")
        var client = SecureChannel.Session(
            sendKey: keys.clientToServer,
            receiveKey: keys.serverToClient
        )
        var server = SecureChannel.Session(
            sendKey: keys.serverToClient,
            receiveKey: keys.clientToServer
        )
        let frame = try client.encrypt(
            "{\"type\":\"ping\"}",
            nonce: Data((64..<76).map(UInt8.init))
        )

        XCTAssertEqual(frame.ciphertextBase64, "UDAh0876ioiruOBIrcGMgbD1B1VdMLuhWIqnT0UJ9Q==")
        XCTAssertEqual(server.decrypt(frame), "{\"type\":\"ping\"}")
        XCTAssertNil(server.decrypt(frame))

        var tamperedBytes = Data(base64Encoded: frame.ciphertextBase64)!
        tamperedBytes[0] ^= 1
        let tampered = SecureChannel.EncryptedFrame(
            sequence: frame.sequence,
            nonceBase64: frame.nonceBase64,
            ciphertextBase64: tamperedBytes.base64EncodedString()
        )
        var freshServer = SecureChannel.Session(
            sendKey: keys.serverToClient,
            receiveKey: keys.clientToServer
        )
        XCTAssertNil(freshServer.decrypt(tampered))
    }
}
