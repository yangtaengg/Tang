package com.smsrelay.mvp

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

object RelayWebSocketClient {
    private const val TAG = "RelayWebSocketClient"
    private const val CLOSE_NORMAL = 1000

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var authenticated = false
    private val authStateListeners = LinkedHashSet<(Boolean) -> Unit>()

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var pingFuture: ScheduledFuture<*>? = null
    private var attempt = 0
    private var lastPongAtMs = 0L
    private val smsQueue = ArrayDeque<RelaySmsEvent>()
    private val callQueue = ArrayDeque<RelayCallEvent>()
    private const val REPLY_SMS_RESULT_TTL_MS = 10 * 60 * 1000L
    private const val PING_INTERVAL_MS = 20_000L
    private const val PONG_TIMEOUT_MS = 60_000L
    private val recentReplySmsResults = LinkedHashMap<String, CachedReplySmsResult>(128, 0.75f, true)

    private data class CachedReplySmsResult(
        val success: Boolean,
        val reason: String?,
        val atMs: Long
    )

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    @Synchronized
    fun connectIfNeeded() {
        val context = appContext ?: return
        val payload = PairingStore(context).load() ?: return
        if (socket != null) {
            return
        }

        val request = Request.Builder()
            .url(payload.url)
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                updateAuthenticated(false)
                Log.i(TAG, "WebSocket opened: ${payload.url}")
                val auth = JSONObject()
                    .put("type", "auth")
                    .put("token", payload.pairingToken)
                    .put("device", Build.MODEL)
                    .put("appVersion", "0.1.0")
                webSocket.send(auth.toString())
                Log.i(TAG, "Auth message sent")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                val type = message.optString("type")
                when (type) {
                    "auth.ok" -> {
                        updateAuthenticated(true)
                        lastPongAtMs = System.currentTimeMillis()
                        startPingLoop()
                        Log.i(TAG, "Auth acknowledged by server")
                        flushQueue()
                    }
                    "auth.fail" -> {
                        updateAuthenticated(false)
                        stopPingLoop()
                        Log.w(TAG, "Auth rejected by server")
                        closeAndReset()
                    }
                    "sms.reply" -> {
                        handleReplyCommand(message)
                    }
                    "reply_sms" -> {
                        handleReplySmsCommand(message)
                    }
                    "call.hangup" -> {
                        handleCallHangUpCommand()
                    }
                    "pong" -> {
                        lastPongAtMs = System.currentTimeMillis()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Unit
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code / $reason")
                updateAuthenticated(false)
                stopPingLoop()
                socket = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                updateAuthenticated(false)
                stopPingLoop()
                socket = null
                scheduleReconnect()
            }
        })
    }

    @Synchronized
    fun clearConnection() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        stopPingLoop()
        closeAndReset()
        smsQueue.clear()
        callQueue.clear()
        QuickReplyStore.clear()
    }

    @Synchronized
    fun isAuthenticated(): Boolean = authenticated

    @Synchronized
    fun addAuthStateListener(listener: (Boolean) -> Unit) {
        authStateListeners.add(listener)
        listener(authenticated)
    }

    @Synchronized
    fun removeAuthStateListener(listener: (Boolean) -> Unit) {
        authStateListeners.remove(listener)
    }

    @Synchronized
    private fun updateAuthenticated(newValue: Boolean) {
        if (authenticated == newValue) {
            return
        }
        authenticated = newValue
        val listeners = authStateListeners.toList()
        listeners.forEach { it(newValue) }
    }

    @Synchronized
    fun enqueueNotification(event: RelaySmsEvent) {
        enqueueWithLimit(smsQueue, event, 100)
    }

    @Synchronized
    fun enqueueIncomingCall(event: RelayCallEvent) {
        enqueueWithLimit(callQueue, event, 30)
    }

    private fun <T> enqueueWithLimit(queue: ArrayDeque<T>, event: T, maxSize: Int) {
        if (queue.size >= maxSize) {
            queue.removeFirst()
        }
        queue.addLast(event)
        connectIfNeeded()
        if (authenticated) {
            flushQueue()
        }
    }

    @Synchronized
    private fun flushQueue() {
        val webSocket = socket ?: return
        if (!authenticated) {
            return
        }
        flushSmsQueue(webSocket)
        flushCallQueue(webSocket)
    }

    private fun flushSmsQueue(webSocket: WebSocket) {
        while (smsQueue.isNotEmpty()) {
            val event = smsQueue.removeFirst()
            val payload = buildSmsPayload(event)
            webSocket.send(payload.toString())
        }
    }

    private fun flushCallQueue(webSocket: WebSocket) {
        while (callQueue.isNotEmpty()) {
            val event = callQueue.removeFirst()
            val payload = buildCallPayload(event)
            webSocket.send(payload.toString())
        }
    }

    private fun buildSmsPayload(event: RelaySmsEvent): JSONObject {
        val payload = JSONObject()
            .put("type", "sms.notification")
            .put("id", event.id)
            .put("timestamp", event.timestamp)
            .put("from", event.from)
            .put("body", event.body)
            .put("sourcePackage", event.sourcePackage)
            .put("conversationKey", event.conversationKey)
        event.fromPhone?.let { payload.put("fromPhone", it) }
        event.replyKey?.let { payload.put("replyKey", it) }
        return payload
    }

    private fun buildCallPayload(event: RelayCallEvent): JSONObject {
        val payload = JSONObject()
            .put("type", "call.incoming")
            .put("id", event.id)
            .put("timestamp", event.timestamp)
            .put("from", event.from)
        event.name?.let { payload.put("name", it) }
        return payload
    }

    @Synchronized
    private fun handleReplyCommand(payload: JSONObject) {
        val replyKey = payload.optString("replyKey")
        val sourcePackage = payload.optString("sourcePackage")
        val conversationKey = payload.optString("conversationKey")
        val body = payload.optString("body")
        if (body.isBlank()) {
            sendReplyResult(replyKey, success = false, reason = "invalid payload")
            return
        }

        val context = appContext
        if (context == null) {
            sendReplyResult(replyKey, success = false, reason = "context unavailable")
            return
        }

        val result = if (replyKey.isNotBlank()) {
            val direct = QuickReplyStore.sendReply(context, replyKey, body)
            if (direct.isSuccess) {
                direct
            } else if (sourcePackage.isBlank()) {
                direct
            } else {
                QuickReplyStore.sendReplyByConversation(context, sourcePackage, conversationKey, body)
            }
        } else {
            if (sourcePackage.isBlank()) {
                Result.failure(IllegalStateException("missing source package"))
            } else {
                QuickReplyStore.sendReplyByConversation(context, sourcePackage, conversationKey, body)
            }
        }
        if (result.isSuccess) {
            sendReplyResult(replyKey, success = true, reason = null)
            Log.i(TAG, "Quick reply sent for key=$replyKey")
        } else {
            val reason = result.exceptionOrNull()?.message ?: "quick reply failed"
            sendReplyResult(replyKey, success = false, reason = reason)
            Log.w(TAG, "Quick reply failed for key=$replyKey: $reason")
        }
    }

    @Synchronized
    private fun handleReplySmsCommand(payload: JSONObject) {
        val to = payload.optString("to")
        val body = payload.optString("body")
        val sourcePackage = payload.optString("sourcePackage")
        val conversationId = payload.optString("conversation_id")
        val clientMsgId = payload.optString("client_msg_id")
        Log.i(TAG, "reply_sms received: to='${to.take(32)}' conversation='${conversationId.take(32)}' sourcePackage='$sourcePackage'")
        if (body.isBlank() || clientMsgId.isBlank()) {
            sendReplySmsResult(clientMsgId, success = false, reason = "invalid payload")
            return
        }

        val context = appContext
        if (context == null) {
            sendReplySmsResult(clientMsgId, success = false, reason = "context unavailable")
            return
        }
        if (!PermissionHelper.hasSendSmsPermission(context)) {
            cacheReplySmsResult(clientMsgId, success = false, reason = "send_sms permission required")
            sendReplySmsResult(clientMsgId, success = false, reason = "send_sms permission required")
            return
        }

        val cached = getCachedReplySmsResult(clientMsgId)
        if (cached != null) {
            sendReplySmsResult(clientMsgId, cached.success, cached.reason ?: "duplicate")
            return
        }

        val primaryDestination = if (to.isNotBlank()) to else conversationId
        val sendResult = SmsSendManager.send(
            context = context,
            toRaw = primaryDestination,
            body = body,
            clientMsgId = clientMsgId
        ) { success, reason ->
            cacheReplySmsResult(clientMsgId, success, reason)
            sendReplySmsResult(clientMsgId, success, reason)
            if (success) {
                Log.i(TAG, "SMS send succeeded for client_msg_id=$clientMsgId")
            } else {
                Log.w(TAG, "SMS send failed for client_msg_id=$clientMsgId: $reason")
            }
        }

        if (sendResult.isFailure) {
            val reason = sendResult.exceptionOrNull()?.message ?: "sms send failed"
            if (reason == "recipient unavailable" && sourcePackage.isNotBlank() && conversationId.isNotBlank()) {
                val quickReplyFallback = QuickReplyStore.sendReplyByConversation(
                    context = context,
                    sourcePackage = sourcePackage,
                    conversationKey = conversationId,
                    message = body
                )
                if (quickReplyFallback.isSuccess) {
                    cacheReplySmsResult(clientMsgId, success = true, reason = "sent via quick reply")
                    sendReplySmsResult(clientMsgId, success = true, reason = "sent via quick reply")
                    return
                }
            }
            cacheReplySmsResult(clientMsgId, success = false, reason = reason)
            sendReplySmsResult(clientMsgId, success = false, reason = reason)
        }
    }

    @Synchronized
    private fun handleCallHangUpCommand() {
        val result = SmsNotificationListenerService.hangUpIncomingCall()
        if (result.isSuccess) {
            Log.i(TAG, "Call hang-up action sent")
        } else {
            val reason = result.exceptionOrNull()?.message ?: "unknown"
            Log.w(TAG, "Call hang-up failed: $reason")
        }
    }

    @Synchronized
    private fun sendReplyResult(replyKey: String, success: Boolean, reason: String?) {
        val webSocket = socket ?: return
        if (!authenticated) {
            return
        }
        val payload = JSONObject()
            .put("type", "sms.reply.result")
            .put("replyKey", replyKey)
            .put("success", success)
        reason?.let { payload.put("reason", it) }
        webSocket.send(payload.toString())
    }

    @Synchronized
    private fun sendReplySmsResult(clientMsgId: String, success: Boolean, reason: String?) {
        val webSocket = socket ?: return
        if (!authenticated) {
            return
        }
        val payload = JSONObject()
            .put("type", "reply_sms.result")
            .put("client_msg_id", clientMsgId)
            .put("success", success)
        reason?.let { payload.put("reason", it) }
        webSocket.send(payload.toString())
    }

    @Synchronized
    private fun cacheReplySmsResult(clientMsgId: String, success: Boolean, reason: String?) {
        pruneReplySmsResults(System.currentTimeMillis())
        recentReplySmsResults[clientMsgId] = CachedReplySmsResult(
            success = success,
            reason = reason,
            atMs = System.currentTimeMillis()
        )
        if (recentReplySmsResults.size > 512) {
            recentReplySmsResults.entries.firstOrNull()?.key?.let { recentReplySmsResults.remove(it) }
        }
    }

    @Synchronized
    private fun getCachedReplySmsResult(clientMsgId: String): CachedReplySmsResult? {
        pruneReplySmsResults(System.currentTimeMillis())
        return recentReplySmsResults[clientMsgId]
    }

    private fun pruneReplySmsResults(now: Long) {
        val iterator = recentReplySmsResults.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (now - item.value.atMs > REPLY_SMS_RESULT_TTL_MS) {
                iterator.remove()
            }
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (reconnectFuture?.isDone == false) {
            return
        }
        val context = appContext ?: return
        if (!PairingStore(context).isPairedAndValidNow()) {
            return
        }

        val base = 1000L * (1L shl attempt.coerceAtMost(6))
        val delayMs = min(30_000L, base)
        attempt += 1

        reconnectFuture = scheduler.schedule(
            { connectIfNeeded() },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    private fun startPingLoop() {
        if (pingFuture?.isDone == false) {
            return
        }
        pingFuture = scheduler.scheduleAtFixedRate(
            { sendPingIfNeeded() },
            PING_INTERVAL_MS,
            PING_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    private fun stopPingLoop() {
        pingFuture?.cancel(false)
        pingFuture = null
    }

    @Synchronized
    private fun sendPingIfNeeded() {
        val webSocket = socket ?: return
        if (!authenticated) {
            return
        }
        val now = System.currentTimeMillis()
        if (lastPongAtMs > 0 && now - lastPongAtMs > PONG_TIMEOUT_MS) {
            Log.w(TAG, "Ping timeout; resetting websocket connection")
            stopPingLoop()
            webSocket.cancel()
            socket = null
            updateAuthenticated(false)
            scheduleReconnect()
            return
        }
        val pingPayload = JSONObject()
            .put("type", "ping")
            .put("ts", now)
        webSocket.send(pingPayload.toString())
    }

    @Synchronized
    private fun closeAndReset() {
        stopPingLoop()
        socket?.close(CLOSE_NORMAL, "reset")
        socket = null
        updateAuthenticated(false)
    }
}
