package com.smsrelay.mvp

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AlarmNotificationParser {
    private val knownAlarmPackages = setOf(
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.google.android.deskclock",
        "com.samsung.android.clock",
        "com.coloros.alarmclock",
        "com.miui.securitycenter",
        "com.oneplus.deskclock",
        "com.htc.android.worldclock"
    )

    private val alarmKeywords = listOf(
        "alarm",
        "ringing",
        "wake up",
        "clock",
        "timer",
        "알람",
        "기상",
        "깨우기",
        "시간",
        "타이머",
        "闹钟",
        "alarma"
    )

    private val timePattern = Regex(
        "(\\d{1,2}):(\\d{2})\\s*(AM|PM|am|pm|오전|오후)?|" +
            "(\\d{1,2})시\\s*(\\d{1,2})분\\s*(오전|오후)?|" +
            "(오전|오후)\\s*(\\d{1,2}):(\\d{2})|" +
            "(오전|오후)\\s*(\\d{1,2})시\\s*(\\d{1,2})분"
    )

    fun parse(sbn: StatusBarNotification): RelayAlarmEvent? {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" ") { it?.toString()?.trim().orEmpty() }
            .orEmpty()
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.channelId.orEmpty()
        } else {
            ""
        }

        val sourceText = listOf(title, text, subText, textLines, channelId)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val normalized = sourceText.lowercase(Locale.getDefault())
        val fromKnownPackage = knownAlarmPackages.contains(sbn.packageName)
        val isAlarmCategory = notification.category == Notification.CATEGORY_ALARM
        val hasAlarmKeyword = alarmKeywords.any { normalized.contains(it) }

        if (!fromKnownPackage && !isAlarmCategory && !hasAlarmKeyword) {
            return null
        }

        val alarmLabel = extractAlarmLabel(listOf(title, text, subText, textLines))
        val alarmTime = extractAlarmTime(sourceText)

        return RelayAlarmEvent(
            id = UUID.randomUUID().toString(),
            timestamp = sbn.postTime,
            label = alarmLabel.ifBlank { "알람" },
            time = alarmTime.ifBlank { formatTimestamp(sbn.postTime) }
        )
    }

    private fun extractAlarmLabel(candidates: List<String>): String {
        val cleaned = candidates
            .map { removeTimeText(it).trim() }
            .firstOrNull { value ->
                value.isNotBlank() &&
                    !isGenericAlarmLabel(value)
            }
        return cleaned.orEmpty()
    }

    private fun removeTimeText(value: String): String {
        val stripped = timePattern.replace(value, " ")
        return stripped.replace(Regex("\\s+"), " ").trim()
    }

    private fun isGenericAlarmLabel(value: String): Boolean {
        val normalized = value.lowercase(Locale.getDefault())
        return alarmKeywords.any { normalized.contains(it) }
    }

    private fun extractAlarmTime(sourceText: String): String {
        val match = timePattern.find(sourceText)
        return match?.value?.trim() ?: ""
    }

    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
