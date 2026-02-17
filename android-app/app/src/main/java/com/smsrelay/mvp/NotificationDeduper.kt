package com.smsrelay.mvp

import java.util.LinkedHashMap

object NotificationDeduper {
    private const val WINDOW_MS = 12_000L
    private const val MAX_ENTRIES = 400
    private val recent = LinkedHashMap<String, Long>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun isDuplicate(event: RelaySmsEvent): Boolean {
        val now = System.currentTimeMillis()
        cleanup(now)

        val roundedTs = (event.timestamp / 5_000L) * 5_000L
        val key = listOf(
            event.sourcePackage,
            event.conversationKey,
            event.body,
            if (event.replyKey.isNullOrEmpty()) "no-reply" else "has-reply",
            roundedTs.toString()
        ).joinToString("|")

        val previous = recent[key]
        if (previous != null && now - previous <= WINDOW_MS) {
            return true
        }

        recent[key] = now
        if (recent.size > MAX_ENTRIES) {
            val first = recent.entries.firstOrNull()?.key
            if (first != null) {
                recent.remove(first)
            }
        }
        return false
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
