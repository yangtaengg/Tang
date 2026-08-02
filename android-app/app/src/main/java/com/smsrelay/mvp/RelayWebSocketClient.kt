package com.smsrelay.mvp

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.ArrayDeque
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

object RelayWebSocketClient {
    private const val CLOSE_NORMAL = 1000
    private const val SUBNET_SCAN_PORT_TIMEOUT_MS = 120
    private const val DISCOVERY_RETRY_MIN_INTERVAL_MS = 8_000L

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var authenticated = false
    private var pendingHandshake: PendingHandshake? = null
    private var secureSession: SecureChannel.Session? = null
    private val authStateListeners = LinkedHashSet<(Boolean) -> Unit>()

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var attempt = 0
    private var lastDiscoveryAttemptMs = 0L
    private val smsQueue = ArrayDeque<RelaySmsEvent>()
    private val callQueue = ArrayDeque<RelayCallEvent>()
    private const val REPLY_SMS_RESULT_TTL_MS = 10 * 60 * 1000L
    private const val HEARTBEAT_INTERVAL_SECONDS = 20L
    private val recentReplySmsResults = LinkedHashMap<String, CachedReplySmsResult>(128, 0.75f, true)

    private data class CachedReplySmsResult(
        val success: Boolean,
        val reason: String?,
        val atMs: Long
    )

    private data class PendingHandshake(
        val payload: QrPayload,
        val clientNonceBase64: String,
        val device: String,
        val appVersion: String
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
        val context = appContext
        if (context == null) {
            return
        }
        val payload = PairingStore(context).load()
        if (payload == null) {
            return
        }
        if (socket != null) {
            return
        }

        val request = Request.Builder()
            .url(payload.url)
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(RelayWebSocketClient) {
                    if (!isCurrentSocket(webSocket)) {
                        webSocket.close(CLOSE_NORMAL, "superseded")
                        return
                    }
                    attempt = 0
                    updateAuthenticated(false)
                    secureSession = null
                    val clientNonceBase64 = SecureChannel.randomNonceBase64()
                    val device = Build.MODEL.take(200)
                    val appVersion = BuildConfig.VERSION_NAME.take(100)
                    pendingHandshake = PendingHandshake(
                        payload = payload,
                        clientNonceBase64 = clientNonceBase64,
                        device = device,
                        appVersion = appVersion
                    )
                    webSocket.send(
                        JSONObject()
                            .put("type", "auth.hello")
                            .put("version", SecureChannel.PROTOCOL_VERSION)
                            .put("clientNonce", clientNonceBase64)
                            .put("device", device)
                            .put("appVersion", appVersion)
                            .toString()
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(RelayWebSocketClient) {
                    if (!isCurrentSocket(webSocket)) {
                        return
                    }
                    if (text.length > MAX_FRAME_CHARS) {
                        closeAndReset()
                        return
                    }
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: run {
                        closeAndReset()
                        return
                    }
                    val type = message.optString("type")
                    if (authenticated && type != "secure") {
                        closeAndReset()
                        return
                    }
                    when (type) {
                        "auth.challenge" -> handleAuthChallenge(webSocket, message)
                        "auth.ok" -> {
                            if (secureSession == null || !message.optBoolean("secure")) {
                                closeAndReset()
                                return
                            }
                            updateAuthenticated(true)
                            startHeartbeatLocked()
                            flushQueue()
                        }
                        "auth.fail" -> {
                            updateAuthenticated(false)
                            stopHeartbeatLocked()
                            closeAndReset()
                        }
                        "secure" -> {
                            if (!authenticated) {
                                closeAndReset()
                                return
                            }
                            handleSecureEnvelope(message)
                        }
                        else -> closeAndReset()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                synchronized(RelayWebSocketClient) {
                    if (isCurrentSocket(webSocket)) {
                        closeAndReset()
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(RelayWebSocketClient) {
                    if (!isCurrentSocket(webSocket)) {
                        return
                    }
                    updateAuthenticated(false)
                    stopHeartbeatLocked()
                    pendingHandshake = null
                    secureSession = null
                    socket = null
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(RelayWebSocketClient) {
                    if (!isCurrentSocket(webSocket)) {
                        return
                    }
                    updateAuthenticated(false)
                    stopHeartbeatLocked()
                    pendingHandshake = null
                    secureSession = null
                    socket = null
                    scheduleReconnect()
                }
            }
        })
    }

    @Synchronized
    fun clearConnection() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        stopHeartbeatLocked()
        closeAndReset()
        smsQueue.clear()
        callQueue.clear()
        QuickReplyStore.clear()
    }

    @Synchronized
    fun isAuthenticated(): Boolean = authenticated

    @Synchronized
    private fun isCurrentSocket(candidate: WebSocket): Boolean = socket === candidate

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
            val event = smsQueue.first()
            val payload = buildSmsPayload(event)
            if (!sendSecure(webSocket, payload)) {
                return
            }
            smsQueue.removeFirst()
        }
    }

    private fun flushCallQueue(webSocket: WebSocket) {
        while (callQueue.isNotEmpty()) {
            val event = callQueue.first()
            val payload = buildCallPayload(event)
            if (!sendSecure(webSocket, payload)) {
                return
            }
            callQueue.removeFirst()
        }
    }

    @Synchronized
    private fun handleAuthChallenge(webSocket: WebSocket, message: JSONObject) {
        val pending = pendingHandshake ?: return
        if (message.optInt("version") != SecureChannel.PROTOCOL_VERSION) {
            closeAndReset()
            return
        }
        val challengeBase64 = message.optString("challenge")
        val keys = runCatching {
            SecureChannel.deriveKeys(
                pending.payload.pairingToken,
                pending.clientNonceBase64,
                challengeBase64
            )
        }.getOrNull() ?: run {
            closeAndReset()
            return
        }
        secureSession = SecureChannel.Session(
            sendKey = keys.clientToServer,
            receiveKey = keys.serverToClient
        )
        val proof = SecureChannel.authProofBase64(
            secret = pending.payload.pairingToken,
            clientNonceBase64 = pending.clientNonceBase64,
            challengeBase64 = challengeBase64,
            device = pending.device,
            appVersion = pending.appVersion
        )
        webSocket.send(
            JSONObject()
                .put("type", "auth.proof")
                .put("proof", proof)
                .toString()
        )
    }

    @Synchronized
    private fun handleSecureEnvelope(message: JSONObject) {
        val session = secureSession ?: return
        val sequence = message.optLong("seq", -1)
        if (sequence <= 0) {
            closeAndReset()
            return
        }
        val plaintext = session.decrypt(
            SecureChannel.EncryptedFrame(
                sequence = sequence,
                nonceBase64 = message.optString("nonce"),
                ciphertextBase64 = message.optString("ciphertext")
            )
        ) ?: run {
            closeAndReset()
            return
        }
        val secureMessage = runCatching { JSONObject(plaintext) }.getOrNull() ?: run {
            closeAndReset()
            return
        }
        when (secureMessage.optString("type")) {
            "pairing.complete" -> handlePairingComplete(secureMessage)
            "sms.reply" -> handleReplyCommand(secureMessage)
            "reply_sms" -> handleReplySmsCommand(secureMessage)
            "call.hangup" -> handleCallHangUpCommand()
            "pong" -> Unit
        }
    }

    private fun handlePairingComplete(message: JSONObject) {
        val pending = pendingHandshake ?: return
        val token = message.optString("token")
        if (token.length < 32) {
            closeAndReset()
            return
        }
        appContext?.let { context ->
            PairingStore(context).save(
                pending.payload.copy(
                    version = SecureChannel.PROTOCOL_VERSION,
                    pairingToken = token,
                    expiresAtMs = Long.MAX_VALUE
                )
            )
        }
        pendingHandshake = pending.copy(
            payload = pending.payload.copy(
                version = SecureChannel.PROTOCOL_VERSION,
                pairingToken = token,
                expiresAtMs = Long.MAX_VALUE
            )
        )
    }

    private fun sendSecure(webSocket: WebSocket, payload: JSONObject): Boolean {
        val session = secureSession ?: return false
        val frame = runCatching { session.encrypt(payload.toString()) }.getOrNull() ?: return false
        return webSocket.send(
            JSONObject()
                .put("type", "secure")
                .put("seq", frame.sequence)
                .put("nonce", frame.nonceBase64)
                .put("ciphertext", frame.ciphertextBase64)
                .toString()
        )
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
        } else {
            val reason = result.exceptionOrNull()?.message ?: "quick reply failed"
            sendReplyResult(replyKey, success = false, reason = reason)
        }
    }

    @Synchronized
    private fun handleReplySmsCommand(payload: JSONObject) {
        val to = payload.optString("to")
        val body = payload.optString("body")
        val sourcePackage = payload.optString("sourcePackage")
        val conversationId = payload.optString("conversation_id")
        val clientMsgId = payload.optString("client_msg_id")
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
        result.getOrNull()
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
        sendSecure(webSocket, payload)
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
        sendSecure(webSocket, payload)
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
            RelayForegroundService.stop(context)
            return
        }

        val base = 1000L * (1L shl attempt.coerceAtMost(6))
        val delayMs = min(30_000L, base)
        attempt += 1

        reconnectFuture = scheduler.schedule(
            {
                recoverPairingUrlOnCurrentWifiIfNeeded()
                connectIfNeeded()
            },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    private fun recoverPairingUrlOnCurrentWifiIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastDiscoveryAttemptMs < DISCOVERY_RETRY_MIN_INTERVAL_MS) {
            return
        }
        lastDiscoveryAttemptMs = now

        val context = appContext ?: return
        val store = PairingStore(context)
        val payload = store.load() ?: return
        val uri = runCatching { URI(payload.url) }.getOrNull() ?: return
        val host = uri.host ?: return
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "wss") 443 else 80

        if (isPortOpen(host, port, SUBNET_SCAN_PORT_TIMEOUT_MS)) {
            return
        }

        val discovered = discoverHostOnWifiSubnet(context, port) ?: return
        if (discovered == host) {
            return
        }

        val path = if (uri.path.isNullOrBlank() || uri.path == "/") "/ws" else uri.path
        val updatedUrl = URI(uri.scheme ?: "ws", uri.userInfo, discovered, port, path, uri.query, uri.fragment).toString()
        store.save(payload.copy(url = updatedUrl))
    }

    private fun discoverHostOnWifiSubnet(context: Context, port: Int): String? {
        val localIp = currentWifiIpv4(context) ?: return null
        val prefix = localIp.substringBeforeLast('.', "")
        if (prefix.isBlank()) {
            return null
        }
        val selfLast = localIp.substringAfterLast('.', "").toIntOrNull()

        val pool = Executors.newFixedThreadPool(24)
        val completion = ExecutorCompletionService<String?>(pool)
        var submitted = 0

        try {
            for (last in 1..254) {
                if (last == selfLast) {
                    continue
                }
                val host = "$prefix.$last"
                completion.submit(Callable {
                    if (isPortOpen(host, port, SUBNET_SCAN_PORT_TIMEOUT_MS)) host else null
                })
                submitted++
            }

            repeat(submitted) {
                val found = completion.take().get()
                if (!found.isNullOrBlank()) {
                    return found
                }
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    private fun currentWifiIpv4(context: Context): String? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null
        return linkProperties.linkAddresses
            .mapNotNull { it.address }
            .firstOrNull { address ->
                address is Inet4Address &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress
            }
            ?.hostAddress
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            true
        }.getOrDefault(false)
    }

    @Synchronized
    private fun closeAndReset() {
        stopHeartbeatLocked()
        socket?.close(CLOSE_NORMAL, "reset")
        socket = null
        pendingHandshake = null
        secureSession = null
        updateAuthenticated(false)
    }

    @Synchronized
    private fun startHeartbeatLocked() {
        if (heartbeatFuture?.isDone == false) {
            return
        }
        heartbeatFuture = scheduler.scheduleWithFixedDelay(
            { sendHeartbeatTick() },
            HEARTBEAT_INTERVAL_SECONDS,
            HEARTBEAT_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    @Synchronized
    private fun stopHeartbeatLocked() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    @Synchronized
    private fun sendHeartbeatTick() {
        val webSocket = socket ?: return
        if (!authenticated) {
            return
        }
        val sent = sendSecure(
            webSocket,
            JSONObject()
                .put("type", "ping")
                .put("timestamp", System.currentTimeMillis())
        )
        if (!sent) {
            updateAuthenticated(false)
            stopHeartbeatLocked()
            socket = null
            scheduleReconnect()
        }
    }

    private const val MAX_FRAME_CHARS = 512 * 1024
}
