package com.smsrelay.mvp

import android.app.Notification
import android.service.notification.StatusBarNotification

object CallActionExecutor {
    private val rejectKeywords = listOf(
        "decline", "reject", "hang up", "hangup", "end",
        "거절", "끊", "종료"
    )

    fun hangUpFrom(activeNotifications: Array<StatusBarNotification>): Result<Unit> {
        val target = activeNotifications
            .sortedByDescending { it.postTime }
            .firstOrNull { looksLikeCall(it) }
            ?: return Result.failure(IllegalStateException("call notification not found"))

        val actions = mutableListOf<Notification.Action>()
        target.notification.actions?.let { actions.addAll(it) }
        val wearableActions = Notification.WearableExtender(target.notification).actions
        if (wearableActions.isNotEmpty()) {
            actions.addAll(wearableActions)
        }

        val rejectAction = actions.firstOrNull { action ->
            val title = action.title?.toString()?.trim()?.lowercase().orEmpty()
            rejectKeywords.any { keyword -> title.contains(keyword) }
        } ?: return Result.failure(IllegalStateException("decline action not found"))

        return runCatching {
            rejectAction.actionIntent?.send()
                ?: throw IllegalStateException("decline pending intent missing")
        }
    }

    private fun looksLikeCall(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        if (notification.category == Notification.CATEGORY_CALL) {
            return true
        }

        val extras = notification.extras
        val merged = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        ).joinToString(" ").lowercase()

        return merged.contains("incoming call") ||
            merged.contains("수신 전화") ||
            merged.contains("전화 수신")
    }
}
