package com.smsrelay.mvp

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecureChannel {
    const val PROTOCOL_VERSION = 2
    private const val AUTH_CONTEXT = "Tang/auth/v2"
    private const val CLIENT_TO_SERVER_CONTEXT = "Tang/client-to-server/v2"
    private const val SERVER_TO_CLIENT_CONTEXT = "Tang/server-to-client/v2"
    private const val FRAME_CONTEXT = "Tang/frame/v2/"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    data class DirectionalKeys(
        val clientToServer: ByteArray,
        val serverToClient: ByteArray
    )

    data class EncryptedFrame(
        val sequence: Long,
        val nonceBase64: String,
        val ciphertextBase64: String
    )

    fun randomNonceBase64(bytes: Int = 32): String {
        val value = ByteArray(bytes)
        random.nextBytes(value)
        return Base64.getEncoder().encodeToString(value)
    }

    fun authProofBase64(
        secret: String,
        clientNonceBase64: String,
        challengeBase64: String,
        device: String,
        appVersion: String
    ): String {
        val proof = hmacSha256(
            key = secret.toByteArray(StandardCharsets.UTF_8),
            data = authInput(clientNonceBase64, challengeBase64, device, appVersion)
        )
        return Base64.getEncoder().encodeToString(proof)
    }

    fun proofMatches(expectedBase64: String, providedBase64: String): Boolean {
        val expected = decodeBase64(expectedBase64) ?: return false
        val provided = decodeBase64(providedBase64) ?: return false
        return MessageDigest.isEqual(expected, provided)
    }

    fun deriveKeys(
        secret: String,
        clientNonceBase64: String,
        challengeBase64: String
    ): DirectionalKeys {
        val clientNonce = requireNotNull(decodeBase64(clientNonceBase64)) { "invalid client nonce" }
        val challenge = requireNotNull(decodeBase64(challengeBase64)) { "invalid challenge" }
        require(clientNonce.size == 32) { "client nonce must be 32 bytes" }
        require(challenge.size == 32) { "challenge must be 32 bytes" }
        val salt = clientNonce + challenge
        val secretBytes = secret.toByteArray(StandardCharsets.UTF_8)
        return DirectionalKeys(
            clientToServer = hkdfSha256(secretBytes, salt, CLIENT_TO_SERVER_CONTEXT.toByteArray(), 32),
            serverToClient = hkdfSha256(secretBytes, salt, SERVER_TO_CLIENT_CONTEXT.toByteArray(), 32)
        )
    }

    class Session(
        private val sendKey: ByteArray,
        private val receiveKey: ByteArray
    ) {
        private var sendSequence = 0L
        private var receiveSequence = 0L

        @Synchronized
        fun encrypt(plaintext: String): EncryptedFrame {
            val nonce = ByteArray(NONCE_BYTES)
            random.nextBytes(nonce)
            return encrypt(plaintext, nonce)
        }

        @Synchronized
        internal fun encrypt(plaintext: String, nonce: ByteArray): EncryptedFrame {
            require(nonce.size == NONCE_BYTES) { "nonce must be 12 bytes" }
            val sequence = Math.addExact(sendSequence, 1L)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(sendKey, "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            cipher.updateAAD(frameAad(sequence))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            sendSequence = sequence
            return EncryptedFrame(
                sequence = sequence,
                nonceBase64 = Base64.getEncoder().encodeToString(nonce),
                ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext)
            )
        }

        @Synchronized
        fun decrypt(frame: EncryptedFrame): String? {
            if (frame.sequence != receiveSequence + 1) {
                return null
            }
            val nonce = decodeBase64(frame.nonceBase64) ?: return null
            val ciphertext = decodeBase64(frame.ciphertextBase64) ?: return null
            if (nonce.size != NONCE_BYTES || ciphertext.size < TAG_BITS / 8) {
                return null
            }
            val plaintext = runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(receiveKey, "AES"),
                    GCMParameterSpec(TAG_BITS, nonce)
                )
                cipher.updateAAD(frameAad(frame.sequence))
                cipher.doFinal(ciphertext)
            }.getOrNull() ?: return null
            receiveSequence = frame.sequence
            return plaintext.toString(StandardCharsets.UTF_8)
        }
    }

    private fun authInput(
        clientNonceBase64: String,
        challengeBase64: String,
        device: String,
        appVersion: String
    ): ByteArray {
        val encoder = Base64.getEncoder()
        val deviceBase64 = encoder.encodeToString(device.toByteArray(StandardCharsets.UTF_8))
        val versionBase64 = encoder.encodeToString(appVersion.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            AUTH_CONTEXT,
            clientNonceBase64,
            challengeBase64,
            deviceBase64,
            versionBase64
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
    }

    private fun frameAad(sequence: Long): ByteArray {
        return "$FRAME_CONTEXT$sequence".toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodeBase64(value: String): ByteArray? {
        return runCatching { Base64.getDecoder().decode(value) }.getOrNull()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int
    ): ByteArray {
        require(outputLength in 1..32) { "only one SHA-256 HKDF block is supported" }
        val pseudoRandomKey = hmacSha256(salt, inputKeyMaterial)
        val block = hmacSha256(pseudoRandomKey, info + byteArrayOf(1))
        return block.copyOf(outputLength)
    }
}
