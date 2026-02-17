package com.smsrelay.mvp

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import java.util.UUID

object SmsNotificationParser {
    private val allowedPackages = setOf(
        "com.samsung.android.messaging",
        "com.sec.android.app.messaging",
        "com.google.android.apps.messaging",
        "com.android.messaging"
    )

    fun parse(sbn: StatusBarNotification, fallbackReplyKey: String? = null): RelaySmsEvent? {
        if (!allowedPackages.contains(sbn.packageName)) {
            return null
        }

        val replyKey = QuickReplyStore.replyKeyIfAvailable(sbn) ?: fallbackReplyKey
        val isGroupSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary && replyKey == null) {
            return null
        }

        val extras = sbn.notification.extras

        val messaging = parseMessagingStyle(extras)
        val fallbackFrom = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val fallbackBody = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

        val sender = messaging?.first?.ifBlank { fallbackFrom } ?: fallbackFrom
        val body = messaging?.second?.ifBlank { fallbackBody } ?: fallbackBody
        if (body.isBlank()) {
            return null
        }

        val conversationKey = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()
            ?.ifBlank { null }
            ?: sbn.tag
            ?: sender

        return RelaySmsEvent(
            id = UUID.randomUUID().toString(),
            timestamp = messaging?.third ?: sbn.postTime,
            from = sender.ifBlank { "Unknown" },
            body = body,
            sourcePackage = sbn.packageName,
            conversationKey = conversationKey,
            replyKey = replyKey
        )
    }

    private fun parseMessagingStyle(extras: android.os.Bundle): Triple<String, String, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return null
        }
        val parcelables = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(parcelables)
        val latest = messages.lastOrNull { !it.text.isNullOrBlank() } ?: return null
        val sender = latest.senderPerson?.name?.toString()
            ?: latest.sender?.toString()
            ?: "Unknown"
        return Triple(sender, latest.text.toString(), latest.timestamp)
    }

}
