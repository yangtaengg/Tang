package com.smsrelay.mvp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PairingStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    init {
        if (!prefs.getBoolean(KEY_LEGACY_STORAGE_REMOVED, false)) {
            appContext.deleteSharedPreferences(LEGACY_PREF_FILE)
            prefs.edit().putBoolean(KEY_LEGACY_STORAGE_REMOVED, true).apply()
        }
    }

    fun save(payload: QrPayload) {
        val plaintext = JSONObject()
            .put(KEY_VERSION, payload.version)
            .put(KEY_URL, payload.url)
            .put(KEY_TOKEN, payload.pairingToken)
            .put(KEY_EXPIRES, payload.expiresAtMs)
            .put(KEY_DEVICE_NAME, payload.deviceName)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val encrypted = encrypt(plaintext)
        prefs.edit().putString(KEY_PAYLOAD, encrypted).apply()
    }

    fun load(): QrPayload? {
        val encrypted = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val payload = runCatching {
            val json = JSONObject(decrypt(encrypted).toString(Charsets.UTF_8))
            QrPayload(
                version = json.getInt(KEY_VERSION),
                url = json.getString(KEY_URL),
                pairingToken = json.getString(KEY_TOKEN),
                expiresAtMs = json.getLong(KEY_EXPIRES),
                deviceName = json.optString(KEY_DEVICE_NAME, "Mac")
            )
        }.getOrNull()
        if (payload == null || !isValidNow(payload)) {
            clear()
            return null
        }
        return payload
    }

    fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    fun parseQrJson(raw: String): Result<QrPayload> {
        return runCatching {
            val json = JSONObject(raw)
            val payload = QrPayload(
                version = json.optInt("version", 1),
                url = json.getString("url"),
                pairingToken = json.getString("pairingToken"),
                expiresAtMs = json.optLong("expiresAtMs", Long.MAX_VALUE),
                deviceName = json.optString("deviceName", "Mac")
            )
            validatePayload(payload)
            payload
        }
    }

    fun isPairedAndValidNow(): Boolean {
        return load() != null
    }

    private fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return listOf(cipher.iv, ciphertext)
            .joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    private fun decrypt(value: String): ByteArray {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2) { "invalid encrypted pairing data" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        require(iv.size == GCM_IV_BYTES) { "invalid pairing data nonce" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun validatePayload(payload: QrPayload) {
        require(payload.version == SecureChannel.PROTOCOL_VERSION) { "unsupported pairing version" }
        val uri = runCatching { URI(payload.url) }.getOrNull()
            ?: throw IllegalArgumentException("invalid pairing URL")
        require(uri.scheme == "ws" || uri.scheme == "wss") { "QR url must be ws:// or wss://" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "pairing URL contains unsupported fields" }
        require(uri.path.isNullOrBlank() || uri.path == "/" || uri.path == "/ws") { "pairing URL path must be /ws" }
        require(uri.port in 1..65535) { "pairing URL must include a valid port" }
        require(isLocalNetworkHost(uri.host.orEmpty())) { "pairing host must be on the local network" }
        require(payload.pairingToken.length >= 12) { "QR token missing" }
        require(payload.deviceName.length <= 200) { "device name is too long" }
        require(isValidNow(payload)) { "pairing payload expired" }
    }

    private fun isValidNow(payload: QrPayload): Boolean {
        return payload.expiresAtMs == Long.MAX_VALUE || payload.expiresAtMs > System.currentTimeMillis()
    }

    private fun isLocalNetworkHost(hostRaw: String): Boolean {
        val host = hostRaw.trim().lowercase()
        if (host == "localhost" || host.endsWith(".local")) {
            return true
        }
        if (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")) {
            return true
        }
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) {
            return false
        }
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 169 && parts[1] == 254) ||
            parts[0] == 127
    }

    private companion object {
        const val PREF_FILE = "pairing.keystore.prefs"
        const val LEGACY_PREF_FILE = "pairing.secure.prefs"
        const val KEY_PAYLOAD = "encrypted_payload"
        const val KEY_LEGACY_STORAGE_REMOVED = "legacy_storage_removed"
        const val KEY_ALIAS = "tang_pairing_token_v2"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
        const val KEY_VERSION = "version"
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_EXPIRES = "expires"
        const val KEY_DEVICE_NAME = "device_name"
    }
}
