package com.smsrelay.mvp

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import android.telephony.PhoneNumberUtils
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
        val fallbackBody = extractFallbackBody(extras)

        if (isGroupSummary && messaging == null) {
            return null
        }

        val sender = messaging?.sender?.ifBlank { fallbackFrom } ?: fallbackFrom
        val body = messaging?.body?.ifBlank { fallbackBody } ?: fallbackBody
        val senderPhone = normalizePhone(
            messaging?.senderPhone
                ?: extractPhoneCandidate(fallbackFrom)
                ?: extractPhoneCandidate(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty())
        )
        if (body.isBlank()) {
            return null
        }

        if (isHiddenSensitiveContent(body) || isGenericOpenMessagesPrompt(body)) {
            return null
        }

        val conversationKey = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()
            ?.ifBlank { null }
            ?: sbn.tag
            ?: sender

        return RelaySmsEvent(
            id = UUID.randomUUID().toString(),
            timestamp = messaging?.timestamp ?: sbn.postTime,
            from = sender.ifBlank { "Unknown" },
            fromPhone = senderPhone,
            body = body,
            sourcePackage = sbn.packageName,
            conversationKey = conversationKey,
            replyKey = replyKey
        )
    }

    private data class ParsedMessaging(
        val sender: String,
        val senderPhone: String?,
        val body: String,
        val timestamp: Long
    )

    private fun parseMessagingStyle(extras: android.os.Bundle): ParsedMessaging? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return null
        }
        val parcelables = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(parcelables)
        val latest = messages
            .asReversed()
            .firstOrNull {
                val text = it.text?.toString()?.trim().orEmpty()
                text.isNotBlank() && !isHiddenSensitiveContent(text) && !isGenericOpenMessagesPrompt(text)
            }
            ?: return null
        val sender = latest.senderPerson?.name?.toString()
            ?: latest.sender?.toString()
            ?: "Unknown"
        val senderPhone = normalizePhone(
            latest.senderPerson?.uri?.removePrefix("tel:")
                ?: extractPhoneCandidate(sender)
        )
        return ParsedMessaging(
            sender = sender,
            senderPhone = senderPhone,
            body = latest.text.toString(),
            timestamp = latest.timestamp
        )
    }

    private fun extractPhoneCandidate(text: String): String? {
        if (text.isBlank()) {
            return null
        }
        return Regex("\\+?[0-9][0-9()\\-\\s]{6,}").find(text)?.value
    }

    private fun normalizePhone(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) {
            return null
        }
        val normalized = PhoneNumberUtils.normalizeNumber(value)
        return if (normalized.count { it.isDigit() } >= 7) normalized else null
    }

    private fun extractFallbackBody(extras: android.os.Bundle): String {
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.firstOrNull {
                it.isNotBlank() && !isHiddenSensitiveContent(it) && !isGenericOpenMessagesPrompt(it)
            }

        if (!textLines.isNullOrBlank()) {
            return textLines
        }

        return sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )
            .mapNotNull { it?.trim() }
            .firstOrNull {
                it.isNotBlank() && !isHiddenSensitiveContent(it) && !isGenericOpenMessagesPrompt(it)
            }
            .orEmpty()
    }

    private fun isHiddenSensitiveContent(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) {
            return false
        }
        return hiddenContentMarkers.any { marker -> normalized.contains(marker) }
    }

    private fun isGenericOpenMessagesPrompt(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) {
            return false
        }
        return genericOpenMessagesMarkers.any { marker -> normalized == marker || normalized.contains(marker) }
    }

    private val hiddenContentMarkers = listOf(
        "sensitive notification content hidden",
        "sensitive content hidden",
        "notification content hidden",
        "private content hidden",
        "content hidden",
        "내용이 숨겨졌",
        "민감한 알림",
        "알림 내용 숨김",
        "콘텐츠 숨김"
    )

    private val genericOpenMessagesMarkers = listOf(
        "view messages",
        "open messages",
        "new messages",
        "메시지 보기",
        "메시지 열기",
        "새 메시지"
    )

}
