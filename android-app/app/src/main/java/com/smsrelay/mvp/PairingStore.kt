package com.smsrelay.mvp

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

class PairingStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(payload: QrPayload) {
        prefs.edit()
            .putInt(KEY_VERSION, payload.version)
            .putString(KEY_URL, payload.url)
            .putString(KEY_TOKEN, payload.pairingToken)
            .putLong(KEY_EXPIRES, payload.expiresAtMs)
            .putString(KEY_DEVICE_NAME, payload.deviceName)
            .apply()
    }

    fun load(): QrPayload? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val version = prefs.getInt(KEY_VERSION, 1)
        val expiresAtMs = prefs.getLong(KEY_EXPIRES, Long.MAX_VALUE)
        val deviceName = prefs.getString(KEY_DEVICE_NAME, "Mac") ?: "Mac"
        return QrPayload(version, url, token, expiresAtMs, deviceName)
    }

    fun clear() {
        prefs.edit().clear().apply()
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

    private fun validatePayload(payload: QrPayload) {
        require(payload.url.startsWith("ws://") || payload.url.startsWith("wss://")) {
            "QR url must be ws:// or wss://"
        }
        require(payload.pairingToken.isNotBlank()) { "QR token missing" }
    }

    private companion object {
        const val PREF_FILE = "pairing.secure.prefs"
        const val KEY_VERSION = "version"
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_EXPIRES = "expires"
        const val KEY_DEVICE_NAME = "device_name"
    }
}
