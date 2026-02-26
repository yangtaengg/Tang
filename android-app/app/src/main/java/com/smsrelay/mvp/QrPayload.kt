package com.smsrelay.mvp

data class QrPayload(
    val version: Int,
    val url: String,
    val pairingToken: String,
    val expiresAtMs: Long,
    val deviceName: String
)

data class RelaySmsEvent(
    val id: String,
    val timestamp: Long,
    val from: String,
    val fromPhone: String?,
    val body: String,
    val sourcePackage: String,
    val conversationKey: String,
    val replyKey: String?
)

data class RelayCallEvent(
    val id: String,
    val timestamp: Long,
    val from: String,
    val name: String?
)
