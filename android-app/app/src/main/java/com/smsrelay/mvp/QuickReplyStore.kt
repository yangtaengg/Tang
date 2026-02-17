package com.smsrelay.mvp

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.service.notification.StatusBarNotification

object QuickReplyStore {
    private data class ReplyTarget(
        val pendingIntent: PendingIntent,
        val remoteInput: RemoteInput?,
        val supportsFreeFormInput: Boolean,
        val sourcePackage: String,
        val conversationKey: String,
        val updatedAtMs: Long,
        val expiresAtMs: Long
    )

    private val targets = mutableMapOf<String, ReplyTarget>()
    private const val TARGET_TTL_MS = 10 * 60 * 1000L

    @Synchronized
    fun updateFromNotification(sbn: StatusBarNotification) {
        val now = System.currentTimeMillis()
        prune(now)
        val target = extractReplyTarget(sbn)
        if (target == null) {
            return
        }
        targets[sbn.key] = target.copy(updatedAtMs = now, expiresAtMs = now + TARGET_TTL_MS)
    }

    @Synchronized
    fun replyKeyIfAvailable(sbn: StatusBarNotification): String? {
        prune(System.currentTimeMillis())
        return if (targets.containsKey(sbn.key)) sbn.key else null
    }

    @Synchronized
    fun refreshFromActiveNotifications(activeNotifications: Array<StatusBarNotification>) {
        val now = System.currentTimeMillis()
        prune(now)
        for (item in activeNotifications) {
            val target = extractReplyTarget(item) ?: continue
            targets[item.key] = target.copy(updatedAtMs = now, expiresAtMs = now + TARGET_TTL_MS)
        }
    }

    @Synchronized
    fun fallbackReplyKeyFor(
        sbn: StatusBarNotification,
        activeNotifications: Array<StatusBarNotification>
    ): String? {
        prune(System.currentTimeMillis())

        val sameGroup = activeNotifications
            .filter { it.key != sbn.key }
            .filter { it.packageName == sbn.packageName }
            .filter { sbn.groupKey != null && it.groupKey == sbn.groupKey }
            .filter { targets.containsKey(it.key) }
            .maxByOrNull { it.postTime }

        if (sameGroup != null) {
            return sameGroup.key
        }

        val samePackage = activeNotifications
            .filter { it.key != sbn.key }
            .filter { it.packageName == sbn.packageName }
            .filter { targets.containsKey(it.key) }
            .maxByOrNull { it.postTime }

        return samePackage?.key
    }

    @Synchronized
    fun remove(notificationKey: String) {
        targets.remove(notificationKey)
    }

    @Synchronized
    fun clear() {
        targets.clear()
    }

    @Synchronized
    fun sendReply(context: Context, replyKey: String, message: String): Result<Unit> {
        prune(System.currentTimeMillis())
        val target = targets[replyKey] ?: return Result.failure(IllegalStateException("reply target not found"))
        return sendWithTarget(context, target, message)
    }

    @Synchronized
    fun sendReplyByConversation(
        context: Context,
        sourcePackage: String,
        conversationKey: String,
        message: String
    ): Result<Unit> {
        prune(System.currentTimeMillis())
        val target = targets.values
            .filter { it.sourcePackage == sourcePackage }
            .filter {
                conversationKey.isNotBlank() &&
                    it.conversationKey.equals(conversationKey, ignoreCase = true)
            }
            .maxByOrNull { it.updatedAtMs }
            ?: targets.values
                .filter { it.sourcePackage == sourcePackage }
                .maxByOrNull { it.updatedAtMs }
            ?: return Result.failure(IllegalStateException("reply target not found"))
        return sendWithTarget(context, target, message)
    }

    private fun sendWithTarget(context: Context, target: ReplyTarget, message: String): Result<Unit> {
        return runCatching {
            val fillInIntent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val remoteInput = target.remoteInput
            if (remoteInput != null) {
                val results = Bundle().apply {
                    putCharSequence(remoteInput.resultKey, message)
                }
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), fillInIntent, results)
            } else {
                fillInIntent.putExtra("android.intent.extra.TEXT", message)
                fillInIntent.putExtra("quick_reply", message)
                fillInIntent.putExtra("reply_text", message)
            }
            target.pendingIntent.send(context, 0, fillInIntent)
        }
    }

    private fun prune(nowMs: Long) {
        val iterator = targets.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.value.expiresAtMs <= nowMs) {
                iterator.remove()
            }
        }
    }

    private fun extractReplyTarget(sbn: StatusBarNotification): ReplyTarget? {
        val notification = sbn.notification
        val extras = notification.extras
        val conversationKey = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()
            ?.trim()
            ?.ifBlank { null }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val sourcePackage = sbn.packageName
        val actionCandidates = mutableListOf<Notification.Action>()
        notification.actions?.let { actionCandidates.addAll(it) }
        val wearableActions = Notification.WearableExtender(notification).actions
        if (wearableActions.isNotEmpty()) {
            actionCandidates.addAll(wearableActions)
        }

        val preferred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            actionCandidates.filter { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
        } else {
            emptyList()
        }
        val ordered = if (preferred.isEmpty()) actionCandidates else preferred + actionCandidates

        for (action in ordered) {
            val pendingIntent = action.actionIntent ?: continue
            val remoteInputs = action.remoteInputs ?: continue
            val selectedInput = remoteInputs.firstOrNull { it.allowFreeFormInput } ?: remoteInputs.firstOrNull() ?: continue
            return ReplyTarget(
                pendingIntent = pendingIntent,
                remoteInput = selectedInput,
                supportsFreeFormInput = selectedInput.allowFreeFormInput,
                sourcePackage = sourcePackage,
                conversationKey = conversationKey,
                updatedAtMs = 0L,
                expiresAtMs = 0L
            )
        }

        for (action in ordered) {
            val pendingIntent = action.actionIntent ?: continue
            val title = action.title?.toString()?.trim()?.lowercase() ?: continue
            val looksLikeReply = title.contains("reply") || title.contains("답장") || title.contains("회신")
            if (!looksLikeReply) {
                continue
            }
            return ReplyTarget(
                pendingIntent = pendingIntent,
                remoteInput = null,
                supportsFreeFormInput = false,
                sourcePackage = sourcePackage,
                conversationKey = conversationKey,
                updatedAtMs = 0L,
                expiresAtMs = 0L
            )
        }
        return null
    }
}
