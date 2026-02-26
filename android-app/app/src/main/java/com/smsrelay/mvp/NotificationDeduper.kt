package com.smsrelay.mvp

import java.util.LinkedHashMap

object NotificationDeduper {
    private const val WINDOW_MS = 12_000L
    private const val MAX_ENTRIES = 400
    private val recent = LinkedHashMap<String, Long>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun isDuplicate(event: RelaySmsEvent): Boolean {
        val now = System.currentTimeMillis()
        val key = buildSmsKey(event)
        return checkDuplicate(key, now)
    }

    @Synchronized
    fun isDuplicate(event: RelayCallEvent): Boolean {
        val now = System.currentTimeMillis()
        val key = buildCallKey(event)
        return checkDuplicate(key, now)
    }

    private fun checkDuplicate(key: String, now: Long): Boolean {
        cleanup(now)
        val roundedTs = (now / 5_000L) * 5_000L
        val fullKey = "$key|$roundedTs"

        val previous = recent[fullKey]
        if (previous != null && now - previous <= WINDOW_MS) {
            return true
        }

        recent[fullKey] = now
        if (recent.size > MAX_ENTRIES) {
            recent.entries.firstOrNull()?.key?.let { recent.remove(it) }
        }
        return false
    }

    private fun buildSmsKey(event: RelaySmsEvent): String {
        return listOf(
            event.sourcePackage,
            event.conversationKey,
            event.body,
            if (event.replyKey.isNullOrEmpty()) "no-reply" else "has-reply"
        ).joinToString("|")
    }

    private fun buildCallKey(event: RelayCallEvent): String {
        return listOf(
            "call",
            event.from,
            event.name ?: "no-name"
        ).joinToString("|")
    }

    private fun cleanup(now: Long) {
        val iterator = recent.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (now - item.value > WINDOW_MS) {
                iterator.remove()
            }
        }
    }
}
