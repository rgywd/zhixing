package me.rerere.rikkahub.data.work

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import me.rerere.rikkahub.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * One authenticated Codex App Server JSON-RPC connection.
 *
 * App Server omits the `jsonrpc: 2.0` field on the wire and carries one object per text frame.
 * This client deliberately owns no retry loop: callers decide when foreground/network state permits
 * reconnecting, using [AppServerBackoffPolicy].
 */
class AppServerJsonRpcClient(
    client: OkHttpClient,
    private val json: Json,
    private val backoffPolicy: AppServerBackoffPolicy = AppServerBackoffPolicy(),
    private val requestTimeoutMs: Long = 30_000,
    private val openTimeoutMs: Long = 15_000,
) {
    private data class PendingRequest(val result: CompletableDeferred<JsonElement>)

    private val webSocketClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val connectionMutex = Mutex()
    private val generation = AtomicLong(0)
    private val nextRequestId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val notificationFlow = MutableSharedFlow<AppServerNotification>(
        extraBufferCapacity = NOTIFICATION_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val serverRequestFlow = MutableSharedFlow<AppServerRequest>(
        extraBufferCapacity = SERVER_REQUEST_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableState = MutableStateFlow(AppServerConnectionState())

    @Volatile
    private var socket: WebSocket? = null

    val state: StateFlow<AppServerConnectionState> = mutableState.asStateFlow()
    /** Broadcasts each frame to every repository controller; controllers filter by threadId. */
    val notifications = notificationFlow.asSharedFlow()
    val serverRequests = serverRequestFlow.asSharedFlow()

    suspend fun connect(endpoint: AppServerEndpoint): JsonObject = connectionMutex.withLock {
        val url = validateEndpoint(endpoint)
        disconnectLocked()
        val connectionGeneration = generation.incrementAndGet()
        val opened = CompletableDeferred<Unit>()
        mutableState.value = AppServerConnectionState(AppServerConnectionPhase.CONNECTING)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${endpoint.bearerToken}")
            .build()
        val webSocket = webSocketClient.newWebSocket(request, listener(connectionGeneration, opened))
        socket = webSocket

        try {
            withTimeout(openTimeoutMs) { opened.await() }
            mutableState.value = AppServerConnectionState(AppServerConnectionPhase.INITIALIZING)
            val initialized = requestInternal(
                method = "initialize",
                params = buildJsonObject {
                    put("clientInfo", buildJsonObject {
                        put("name", "zhixing_android")
                        put("title", "Zhixing Android")
                        put("version", BuildConfig.VERSION_NAME)
                    })
                    put("capabilities", buildJsonObject { put("experimentalApi", true) })
                },
            ).jsonObject
            notifyInternal("initialized", JsonObject(emptyMap()))
            mutableState.value = AppServerConnectionState(
                phase = AppServerConnectionPhase.READY,
                serverInfo = initialized,
            )
            initialized
        } catch (error: Throwable) {
            if (generation.get() == connectionGeneration) {
                socket = null
                generation.incrementAndGet()
                webSocket.cancel()
                failPending(error)
                mutableState.value = AppServerConnectionState(
                    phase = AppServerConnectionPhase.FAILED,
                    error = error.message ?: "Codex App Server connection failed",
                )
            }
            throw error
        }
    }

    suspend fun request(
        method: String,
        params: JsonElement = JsonObject(emptyMap()),
    ): JsonElement {
        check(state.value.phase == AppServerConnectionPhase.READY) { "Codex App Server is not ready" }
        var attempt = 0
        while (true) {
            try {
                return requestInternal(method, params)
            } catch (error: AppServerRpcException) {
                if (error.code != APP_SERVER_BUSY || attempt >= backoffPolicy.scheduleMs.lastIndex) throw error
                delay(backoffPolicy.delayMs(attempt, Random.nextDouble()))
                attempt += 1
            }
        }
    }

    fun notify(method: String, params: JsonElement = JsonObject(emptyMap())) {
        check(state.value.phase == AppServerConnectionPhase.READY) { "Codex App Server is not ready" }
        notifyInternal(method, params)
    }

    fun respond(request: AppServerRequest, result: JsonElement) {
        send(buildJsonObject {
            put("id", request.id)
            put("result", result)
        })
    }

    fun respondError(request: AppServerRequest, code: Int, message: String, data: JsonElement? = null) {
        send(buildJsonObject {
            put("id", request.id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            })
        })
    }

    suspend fun disconnect() = connectionMutex.withLock { disconnectLocked() }

    private suspend fun requestInternal(method: String, params: JsonElement): JsonElement {
        val id = nextRequestId.getAndIncrement()
        val key = id.toString()
        val deferred = CompletableDeferred<JsonElement>()
        pending[key] = PendingRequest(deferred)
        try {
            send(buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            })
            return withTimeout(requestTimeoutMs) { deferred.await() }
        } finally {
            pending.remove(key)
        }
    }

    private fun notifyInternal(method: String, params: JsonElement) {
        send(buildJsonObject {
            put("method", method)
            put("params", params)
        })
    }

    private fun send(message: JsonObject) {
        val activeSocket = socket ?: throw AppServerTransportException("Codex App Server socket is closed")
        if (!activeSocket.send(message.toString())) {
            throw AppServerTransportException("Codex App Server rejected the outgoing frame")
        }
    }

    private fun listener(connectionGeneration: Long, opened: CompletableDeferred<Unit>) =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation.get() == connectionGeneration) opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation.get() != connectionGeneration) return
                runCatching { handleMessage(json.parseToJsonElement(text).jsonObject) }
                    .onFailure { failConnection(connectionGeneration, AppServerTransportException("Invalid App Server frame", it)) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                failConnection(
                    connectionGeneration,
                    AppServerTransportException("Codex App Server closed ($code${reason.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()})"),
                    disconnected = true,
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                failConnection(connectionGeneration, AppServerTransportException("Codex App Server connection failed", t))
            }
        }

    private fun handleMessage(message: JsonObject) {
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        if (id != null && (message.containsKey("result") || message.containsKey("error"))) {
            val request = pending.remove(id.requestKey()) ?: return
            val error = message["error"] as? JsonObject
            if (error != null) {
                request.result.completeExceptionally(
                    AppServerRpcException(
                        code = (error["code"] as? JsonPrimitive)?.intOrNull,
                        message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "Codex RPC error",
                        data = error["data"],
                    )
                )
            } else {
                request.result.complete(message["result"] ?: JsonNull)
            }
            return
        }
        if (id != null && method != null) {
            check(serverRequestFlow.tryEmit(AppServerRequest(id, method, message["params"] ?: JsonNull))) {
                "App Server request queue is full"
            }
            return
        }
        if (method != null) {
            check(notificationFlow.tryEmit(AppServerNotification(method, message["params"] ?: JsonNull))) {
                "App Server notification queue is full"
            }
        }
    }

    private fun failConnection(
        connectionGeneration: Long,
        error: Throwable,
        disconnected: Boolean = false,
    ) {
        if (!generation.compareAndSet(connectionGeneration, connectionGeneration + 1)) return
        socket = null
        failPending(error)
        mutableState.value = AppServerConnectionState(
            phase = if (disconnected) AppServerConnectionPhase.DISCONNECTED else AppServerConnectionPhase.FAILED,
            error = error.message,
        )
    }

    private fun disconnectLocked() {
        generation.incrementAndGet()
        val oldSocket = socket
        socket = null
        oldSocket?.close(1000, "client disconnect")
        failPending(CancellationException("Codex App Server disconnected"))
        mutableState.value = AppServerConnectionState(AppServerConnectionPhase.DISCONNECTED)
    }

    private fun failPending(error: Throwable) {
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach { it.result.completeExceptionally(error) }
    }

    private fun validateEndpoint(endpoint: AppServerEndpoint): HttpUrl {
        require(endpoint.bearerToken.isNotBlank()) { "App Server bearer token is required" }
        require(endpoint.bearerToken.none(Char::isWhitespace)) { "App Server bearer token is invalid" }
        val rawUrl = endpoint.webSocketUrl.trim()
        val transportScheme = rawUrl.substringBefore("://", missingDelimiterValue = "").lowercase()
        val httpUrl = when (transportScheme) {
            "wss" -> "https://${rawUrl.substringAfter("://")}"
            "ws" -> "http://${rawUrl.substringAfter("://")}"
            else -> rawUrl
        }
        val url = requireNotNull(httpUrl.toHttpUrlOrNull()) { "Invalid App Server WebSocket URL" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "Credentials must not be embedded in the URL" }
        when (transportScheme) {
            "wss" -> Unit
            "ws" -> require(endpoint.allowInsecureLoopback && url.host.isLoopbackHost()) {
                "Plain WebSocket is only allowed for an explicit loopback connection"
            }
            else -> error("App Server URL must use wss://")
        }
        return url
    }

    private companion object {
        const val APP_SERVER_BUSY = -32001
        const val NOTIFICATION_CAPACITY = 1_024
        const val SERVER_REQUEST_CAPACITY = 64
    }
}

private fun JsonElement.requestKey(): String = when (this) {
    is JsonPrimitive -> content
    else -> toString()
}

private fun String.isLoopbackHost(): Boolean =
    equals("localhost", ignoreCase = true) || this == "127.0.0.1" || this == "::1"
