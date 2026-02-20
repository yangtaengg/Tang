package com.smsrelay.mvp

import android.app.Notification
import android.service.notification.StatusBarNotification
import android.telephony.PhoneNumberUtils
import java.util.UUID

object CallNotificationParser {
    private val knownDialerPackages = setOf(
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.android.dialer",
        "com.android.server.telecom"
    )

    fun parse(sbn: StatusBarNotification): RelayCallEvent? {
        val notification = sbn.notification
        val category = notification.category.orEmpty()
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        val fromKnownDialer = knownDialerPackages.contains(sbn.packageName)
        val isCallCategory = category == Notification.CATEGORY_CALL
        val looksLikeCall = isSystemCallLabel(title) || isSystemCallLabel(text) || isSystemCallLabel(subText)
        if (!fromKnownDialer && !isCallCategory && !looksLikeCall) {
            return null
        }

        val number = normalizePhone(
            extractPhoneCandidate(title)
                ?: extractPhoneCandidate(text)
                ?: extractPhoneCandidate(subText)
        )

        val titleIsSystemLabel = isSystemCallLabel(title)
        val textIsSystemLabel = isSystemCallLabel(text)
        val name = when {
            title.isBlank() -> null
            normalizePhone(title) != null -> null
            titleIsSystemLabel -> null
            else -> title
        }

        val from = number
            ?: if (titleIsSystemLabel) {
                text.takeUnless { text.isBlank() || textIsSystemLabel }
                    ?: subText.takeUnless { subText.isBlank() || isSystemCallLabel(subText) }
                    ?: "Unknown caller"
            } else {
                title.ifBlank { text.ifBlank { "Unknown caller" } }
            }

        return RelayCallEvent(
            id = UUID.randomUUID().toString(),
            timestamp = sbn.postTime,
            from = from,
            name = name
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

    private fun isSystemCallLabel(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("incoming call") ||
            normalized.contains("수신 전화") ||
            normalized.contains("전화 수신")
    }
}
