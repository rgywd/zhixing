package me.rerere.rikkahub.ui.pages.workflow.happy

import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

class HappySocketClient(
    private val json: Json,
    private val recordCrypto: HappyRecordCrypto = HappyRecordCrypto(json),
    private val serverUrl: String = HappyProtocol.SERVER_URL,
    private val clientId: String,
) {
    private val connectionMutex = Mutex()
    private val updateListeners = CopyOnWriteArraySet<() -> Unit>()
    private var token: String? = null
    private var socket: Socket? = null

    fun addUpdateListener(listener: () -> Unit): AutoCloseable {
        updateListeners += listener
        return AutoCloseable { updateListeners -= listener }
    }

    suspend fun connect(credentials: HappyCredentials) {
        ensureConnected(credentials)
    }

    suspend fun abort(credentials: HappyCredentials, session: HappySession) {
        sessionRpc(
            credentials = credentials,
            session = session,
            method = "abort",
            params = buildJsonObject { put("reason", "User requested stop from Zhixing") },
        )
    }

    suspend fun approve(
        credentials: HappyCredentials,
        session: HappySession,
        approvalId: String,
        forSession: Boolean,
    ) {
        sessionRpc(
            credentials = credentials,
            session = session,
            method = "permission",
            params = buildJsonObject {
                put("id", approvalId)
                put("approved", true)
                put("decision", if (forSession) "approved_for_session" else "approved")
            },
        )
    }

    suspend fun deny(
        credentials: HappyCredentials,
        session: HappySession,
        approvalId: String,
        abort: Boolean,
    ) {
        sessionRpc(
            credentials = credentials,
            session = session,
            method = "permission",
            params = buildJsonObject {
                put("id", approvalId)
                put("approved", false)
                put("decision", if (abort) "abort" else "denied")
            },
        )
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
        token = null
    }

    private suspend fun sessionRpc(
        credentials: HappyCredentials,
        session: HappySession,
        method: String,
        params: JsonObject,
    ): JsonElement? {
        val key = session.encryptionKey ?: throw HappyDecryptionException(session.id)
        val connectedSocket = ensureConnected(credentials)
        val encryptedParams = recordCrypto.encryptElement(params, key, session.encryptionVariant)
        val payload = JSONObject()
            .put("method", "${session.id}:$method")
            .put("params", encryptedParams)

        return withTimeout(RPC_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                val ack = Ack { args ->
                    if (!completed.compareAndSet(false, true) || !continuation.isActive) return@Ack
                    val response = args.firstOrNull() as? JSONObject
                    if (response == null) {
                        continuation.resumeWithException(HappyRpcException.InvalidResponse(method))
                    } else if (!response.optBoolean("ok", false)) {
                        continuation.resumeWithException(
                            HappyRpcException.Rejected(method, response.optString("error", "RPC rejected"))
                        )
                    } else {
                        val encryptedResult = response.optString("result")
                        val result = encryptedResult.takeIf(String::isNotBlank)?.let { encoded ->
                            recordCrypto.decryptElement(encoded, key, session.encryptionVariant)
                        }
                        if (encryptedResult.isNotBlank() && result == null) {
                            continuation.resumeWithException(HappyDecryptionException(session.id))
                        } else {
                            continuation.resume(result)
                        }
                    }
                }
                connectedSocket.emit("rpc-call", arrayOf(payload), ack)
                continuation.invokeOnCancellation { completed.set(true) }
            }
        }
    }

    private suspend fun ensureConnected(credentials: HappyCredentials): Socket = connectionMutex.withLock {
        val current = socket
        if (current != null && token == credentials.token && current.connected()) return current
        if (current == null || token != credentials.token) {
            current?.disconnect()
            token = credentials.token
            socket = createSocket(credentials)
        }
        val target = requireNotNull(socket)
        if (!target.connected()) awaitConnection(target)
        target
    }

    private fun createSocket(credentials: HappyCredentials): Socket {
        val options = IO.Options.builder()
            .setPath("/v1/updates")
            .setAuth(
                mapOf(
                    "token" to credentials.token,
                    "clientType" to "user-scoped",
                    "happyClient" to clientId,
                    "appState" to "active",
                )
            )
            .setTransports(arrayOf(WebSocket.NAME))
            .setReconnection(true)
            .setReconnectionDelay(1_000)
            .setReconnectionDelayMax(5_000)
            .setTimeout(CONNECTION_TIMEOUT_MS)
            .build()
        return IO.socket(URI.create(serverUrl), options).apply {
            on("update") { updateListeners.forEach { it() } }
            on("ephemeral") { updateListeners.forEach { it() } }
        }
    }

    private suspend fun awaitConnection(target: Socket) = withTimeout(CONNECTION_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            lateinit var onConnect: io.socket.emitter.Emitter.Listener
            lateinit var onError: io.socket.emitter.Emitter.Listener
            fun cleanUp() {
                target.off(Socket.EVENT_CONNECT, onConnect)
                target.off(Socket.EVENT_CONNECT_ERROR, onError)
            }
            onConnect = io.socket.emitter.Emitter.Listener {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    cleanUp()
                    continuation.resume(Unit)
                }
            }
            onError = io.socket.emitter.Emitter.Listener { args ->
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    cleanUp()
                    continuation.resumeWithException(
                        HappyRpcException.Offline(args.firstOrNull()?.toString().orEmpty())
                    )
                }
            }
            target.once(Socket.EVENT_CONNECT, onConnect)
            target.once(Socket.EVENT_CONNECT_ERROR, onError)
            continuation.invokeOnCancellation {
                completed.set(true)
                cleanUp()
            }
            target.connect()
        }
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val RPC_TIMEOUT_MS = 35_000L
    }
}

sealed class HappyRpcException(message: String) : Exception(message) {
    class Offline(detail: String) : HappyRpcException("Happy relay is offline: $detail")
    class InvalidResponse(method: String) : HappyRpcException("Invalid Happy RPC response for $method")
    class Rejected(method: String, detail: String) : HappyRpcException("Happy RPC $method rejected: $detail")
}
