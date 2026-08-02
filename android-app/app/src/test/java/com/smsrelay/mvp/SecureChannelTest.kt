package com.smsrelay.mvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SecureChannelTest {
    private val secret = "production-test-secret"
    private val clientNonce = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val challenge = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 32).toByte() })

    @Test
    fun authProofIsStableAndSecretBound() {
        val proof = SecureChannel.authProofBase64(
            secret,
            clientNonce,
            challenge,
            "Galaxy S24",
            "1.0.0"
        )

        assertEquals("U53AW7SBWPFauEd2+v7ZKg8zSngElbpXrnLaaz0DXs0=", proof)
        assertTrue(SecureChannel.proofMatches(proof, proof))
        assertFalse(
            SecureChannel.proofMatches(
                proof,
                SecureChannel.authProofBase64("wrong", clientNonce, challenge, "Galaxy S24", "1.0.0")
            )
        )
    }

    @Test
    fun directionalSessionsEncryptAndRejectReplay() {
        val keys = SecureChannel.deriveKeys(secret, clientNonce, challenge)
        assertEquals("sO4T5VGY8+mHmRBigLo+GUEM6TBhFRLbZRSEnLoVegA=", Base64.getEncoder().encodeToString(keys.clientToServer))
        assertEquals("dcjEqyPIZgH3Yco+uEKyJc0+K808yuPFNDUzpd9YzfY=", Base64.getEncoder().encodeToString(keys.serverToClient))
        val client = SecureChannel.Session(keys.clientToServer, keys.serverToClient)
        val server = SecureChannel.Session(keys.serverToClient, keys.clientToServer)

        val frame = client.encrypt("{\"type\":\"ping\"}", ByteArray(12) { (it + 64).toByte() })

        assertEquals("UDAh0876ioiruOBIrcGMgbD1B1VdMLuhWIqnT0UJ9Q==", frame.ciphertextBase64)
        assertEquals("{\"type\":\"ping\"}", server.decrypt(frame))
        assertNull(server.decrypt(frame))

        val tamperedBytes = Base64.getDecoder().decode(frame.ciphertextBase64).also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        val tampered = frame.copy(
            ciphertextBase64 = Base64.getEncoder().encodeToString(tamperedBytes)
        )
        val freshServer = SecureChannel.Session(keys.serverToClient, keys.clientToServer)
        assertNull(freshServer.decrypt(tampered))
    }
}
