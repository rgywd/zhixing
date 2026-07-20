package me.rerere.rikkahub.data.work

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppServerJsonRpcClientTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends bearer and completes initialize before becoming ready`() = runBlocking {
        val frames = Collections.synchronizedList(mutableListOf<JsonObject>())
        val initializedSeen = CountDownLatch(1)
        server.enqueue(webSocketResponse { socket, message ->
            frames += message
            if (message.string("method") == "initialize") {
                socket.send("""{"id":${message["id"]},"result":{"userAgent":"codex-test","platformFamily":"windows"}}""")
            }
            if (message.string("method") == "initialized") initializedSeen.countDown()
        })
        val client = client()

        val info = client.connect(endpoint())

        val handshake = server.takeRequest()
        assertEquals("Bearer secret-token", handshake.getHeader("Authorization"))
        assertEquals("codex-test", info.string("userAgent"))
        assertEquals(AppServerConnectionPhase.READY, client.state.value.phase)
        assertEquals("test-connection", client.state.value.connectionId)
        assertTrue(initializedSeen.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("initialize", "initialized"), frames.map { it.string("method") })
        val initialize = frames.first()
        assertEquals("zhixing_android", initialize["params"]!!.jsonObject["clientInfo"]!!.jsonObject.string("name"))
        assertTrue(initialize["params"]!!.jsonObject["capabilities"]!!.jsonObject["experimentalApi"].toString().toBoolean())
        client.disconnect()
    }

    @Test
    fun `correlates requests and surfaces rpc errors`() = runBlocking {
        server.enqueue(webSocketResponse { socket, message ->
            when (message.string("method")) {
                "initialize" -> socket.send("""{"id":${message["id"]},"result":{}}""")
                "thread/read" -> socket.send("""{"id":${message["id"]},"result":{"thread":{"id":"thread_1"}}}""")
                "model/list" -> socket.send("""{"id":${message["id"]},"error":{"code":-32001,"message":"Server overloaded"}}""")
            }
        })
        val client = client()
        client.connect(endpoint())

        val thread = client.request("thread/read", buildJsonObject { put("threadId", "thread_1") })
        assertEquals("thread_1", thread.jsonObject["thread"]!!.jsonObject.string("id"))
        val error = runCatching { client.request("model/list") }.exceptionOrNull()
        assertTrue(error is AppServerRpcException)
        assertEquals(-32001, (error as AppServerRpcException).code)
        client.disconnect()
    }

    @Test
    fun `retries app server busy responses with bounded backoff`() = runBlocking {
        var attempts = 0
        server.enqueue(webSocketResponse { socket, message ->
            when (message.string("method")) {
                "initialize" -> socket.send("""{"id":${message["id"]},"result":{}}""")
                "thread/read" -> {
                    attempts += 1
                    if (attempts == 1) {
                        socket.send("""{"id":${message["id"]},"error":{"code":-32001,"message":"busy"}}""")
                    } else {
                        socket.send("""{"id":${message["id"]},"result":{"thread":{"id":"thread_1"}}}""")
                    }
                }
            }
        })
        val client = client()
        client.connect(endpoint())

        val result = client.request("thread/read")

        assertEquals(2, attempts)
        assertEquals("thread_1", result.jsonObject["thread"]!!.jsonObject.string("id"))
        client.disconnect()
    }

    @Test
    fun `keeps notifications separate from server requests and can respond`() = runBlocking {
        val responseSeen = CountDownLatch(1)
        server.enqueue(webSocketResponse { socket, message ->
            when (message.string("method")) {
                "initialize" -> socket.send("""{"id":${message["id"]},"result":{}}""")
                "initialized" -> {
                    socket.send("""{"method":"turn/started","params":{"turn":{"id":"turn_1"}}}""")
                    socket.send("""{"id":"approval_1","method":"item/commandExecution/requestApproval","params":{"itemId":"item_1"}}""")
                }
                null -> if (message["id"] == JsonPrimitive("approval_1") && message.containsKey("result")) {
                    responseSeen.countDown()
                }
            }
        })
        val client = client()
        val notificationResult = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { client.notifications.first() }
        }
        val requestResult = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { client.serverRequests.first() }
        }
        client.connect(endpoint())

        val notification = notificationResult.await()
        val request = requestResult.await()
        assertEquals("turn/started", notification.method)
        assertEquals("item/commandExecution/requestApproval", request.method)
        assertTrue(notification.connectionGeneration > 0)
        assertEquals(client.connectionGeneration, notification.connectionGeneration)
        assertEquals(notification.connectionGeneration, request.connectionGeneration)
        assertEquals("test-connection", notification.connectionId)
        assertEquals(notification.connectionId, request.connectionId)
        client.respond(request, buildJsonObject { put("decision", "decline") })
        assertTrue(responseSeen.await(2, TimeUnit.SECONDS))
        client.disconnect()
    }

    @Test
    fun `broadcasts a notification to every repository controller`() = runBlocking {
        server.enqueue(webSocketResponse { socket, message ->
            when (message.string("method")) {
                "initialize" -> socket.send("""{"id":${message["id"]},"result":{}}""")
                "initialized" -> socket.send(
                    """{"method":"turn/started","params":{"threadId":"thread_1","turn":{"id":"turn_1"}}}"""
                )
            }
        })
        val client = client()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { client.notifications.first() }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { client.notifications.first() }
        }

        client.connect(endpoint())

        assertEquals("turn/started", first.await().method)
        assertEquals("turn/started", second.await().method)
        client.disconnect()
    }

    @Test
    fun `disconnect fails an in-flight request and returns to disconnected`() = runBlocking {
        server.enqueue(webSocketResponse { socket, message ->
            if (message.string("method") == "initialize") socket.send("""{"id":${message["id"]},"result":{}}""")
        })
        val client = client(requestTimeoutMs = 10_000)
        client.connect(endpoint())
        val request = async { runCatching { client.request("thread/read") }.exceptionOrNull() }

        client.disconnect()

        assertTrue(withTimeout(2_000) { request.await() } != null)
        assertEquals(AppServerConnectionPhase.DISCONNECTED, client.state.value.phase)
    }

    @Test
    fun `an old write generation cannot send on a replacement websocket`() = runBlocking {
        server.enqueue(webSocketResponse { socket, message ->
            if (message.string("method") == "initialize") socket.send("""{"id":${message["id"]},"result":{}}""")
        })
        val replacementFrames = Collections.synchronizedList(mutableListOf<JsonObject>())
        server.enqueue(webSocketResponse { socket, message ->
            replacementFrames += message
            if (message.string("method") == "initialize") socket.send("""{"id":${message["id"]},"result":{}}""")
        })
        val client = client()
        client.connect(endpoint("connection-a"))
        val oldGeneration = client.connectionGeneration
        client.connect(endpoint("connection-b"))

        val error = runCatching {
            client.request(
                "turn/start",
                expectedConnectionGeneration = oldGeneration,
            )
        }.exceptionOrNull()

        assertTrue(error is AppServerTransportException)
        assertFalse(replacementFrames.any { it.string("method") == "turn/start" })
        assertTrue(client.connectionGeneration != oldGeneration)
        client.disconnect()
    }

    @Test
    fun `an inactive repository cannot replace the current logical connection`() = runBlocking {
        server.enqueue(webSocketResponse { socket, message ->
            if (message.string("method") == "initialize") socket.send("""{"id":${message["id"]},"result":{}}""")
        })
        val client = client()
        client.connect(endpoint("connection-a"))
        val generation = client.connectionGeneration

        val error = runCatching {
            client.connect(endpoint("connection-b")) { error("repository is inactive") }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(AppServerConnectionPhase.READY, client.state.value.phase)
        assertEquals("connection-a", client.state.value.connectionId)
        assertEquals(generation, client.connectionGeneration)
        assertEquals(1, server.requestCount)
        client.disconnect()
    }

    @Test
    fun `owner is revalidated after initialize before ready is published`() = runBlocking {
        val owner = AtomicReference("connection-a")
        val frames = Collections.synchronizedList(mutableListOf<JsonObject>())
        server.enqueue(webSocketResponse { socket, message ->
            frames += message
            if (message.string("method") == "initialize") {
                owner.set("connection-b")
                socket.send("""{"id":${message["id"]},"result":{}}""")
            }
        })
        val client = client()

        val error = runCatching {
            client.connect(endpoint("connection-a")) {
                check(owner.get() == "connection-a") { "repository is inactive" }
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(frames.any { it.string("method") == "initialized" })
        assertFalse(
            client.state.value.phase == AppServerConnectionPhase.READY &&
                client.state.value.connectionId == "connection-a",
        )
    }

    @Test
    fun `cancelling a slow owner releases a queued replacement connection`() = runBlocking {
        val firstInitializeSeen = CountDownLatch(1)
        server.enqueue(webSocketResponse { _, message ->
            if (message.string("method") == "initialize") firstInitializeSeen.countDown()
        })
        server.enqueue(webSocketResponse { socket, message ->
            if (message.string("method") == "initialize") {
                socket.send("""{"id":${message["id"]},"result":{"userAgent":"connection-b"}}""")
            }
        })
        val owner = AtomicReference("connection-a")
        val client = client(requestTimeoutMs = 5_000)
        val states = Collections.synchronizedList(mutableListOf<AppServerConnectionState>())
        val stateCollector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            client.state.collect { states += it }
        }
        val first = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            client.connect(endpoint("connection-a")) {
                check(owner.get() == "connection-a") { "repository is inactive" }
            }
        }
        assertTrue(firstInitializeSeen.await(2, TimeUnit.SECONDS))
        val replacement = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            client.connect(endpoint("connection-b")) {
                check(owner.get() == "connection-b") { "repository is inactive" }
            }
        }

        owner.set("connection-b")
        first.cancelAndJoin()
        val info = withTimeout(2_000) { replacement.await() }

        assertEquals("connection-b", info.string("userAgent"))
        assertEquals(AppServerConnectionPhase.READY, client.state.value.phase)
        assertEquals("connection-b", client.state.value.connectionId)
        assertFalse(states.any {
            it.phase == AppServerConnectionPhase.READY && it.connectionId == "connection-a"
        })
        assertEquals(2, server.requestCount)
        client.disconnect()
        stateCollector.cancelAndJoin()
    }

    @Test
    fun `a busy read retry cannot cross onto a replacement websocket`() = runBlocking {
        server.enqueue(webSocketResponse { socket, message ->
            when (message.string("method")) {
                "initialize" -> socket.send("""{"id":${message["id"]},"result":{}}""")
                "thread/read" -> socket.send(
                    """{"id":${message["id"]},"error":{"code":-32001,"message":"busy"}}"""
                )
            }
        })
        val replacementFrames = Collections.synchronizedList(mutableListOf<JsonObject>())
        server.enqueue(webSocketResponse { socket, message ->
            replacementFrames += message
            if (message.string("method") == "initialize") socket.send("""{"id":${message["id"]},"result":{}}""")
        })
        val client = client(backoffPolicy = AppServerBackoffPolicy(scheduleMs = listOf(1, 1), jitterRatio = 0.0))
        client.connect(endpoint("connection-a"))
        val oldGeneration = client.connectionGeneration
        val attempts = AtomicInteger(0)
        val retryEntered = CountDownLatch(1)
        val allowRetry = CountDownLatch(1)
        val read = async(Dispatchers.Default) {
            runCatching {
                client.request(
                    "thread/read",
                    expectedConnectionGeneration = oldGeneration,
                    beforeAttempt = {
                        if (attempts.incrementAndGet() == 2) {
                            retryEntered.countDown()
                            check(allowRetry.await(2, TimeUnit.SECONDS))
                        }
                    },
                )
            }.exceptionOrNull()
        }
        assertTrue(retryEntered.await(2, TimeUnit.SECONDS))

        client.connect(endpoint("connection-b"))
        allowRetry.countDown()

        assertTrue(withTimeout(2_000) { read.await() } is AppServerTransportException)
        assertFalse(replacementFrames.any { it.string("method") == "thread/read" })
        client.disconnect()
    }

    @Test
    fun `rejects plain websocket outside explicit loopback`() = runBlocking {
        val client = client()

        val error = runCatching {
            client.connect(AppServerEndpoint("ws://100.64.0.10:4500", "secret", allowInsecureLoopback = true))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(client.state.value.phase == AppServerConnectionPhase.READY)
    }

    private fun client(
        requestTimeoutMs: Long = 2_000,
        backoffPolicy: AppServerBackoffPolicy = AppServerBackoffPolicy(
            scheduleMs = listOf(1, 1),
            jitterRatio = 0.0,
        ),
    ) = AppServerJsonRpcClient(
        client = OkHttpClient(),
        json = json,
        backoffPolicy = backoffPolicy,
        requestTimeoutMs = requestTimeoutMs,
        openTimeoutMs = 2_000,
    )

    private fun endpoint(connectionId: String = "test-connection") = AppServerEndpoint(
        webSocketUrl = server.url("/").toString().replaceFirst("http://", "ws://"),
        bearerToken = "secret-token",
        connectionId = connectionId,
        allowInsecureLoopback = true,
    )

    private fun webSocketResponse(onFrame: (WebSocket, JsonObject) -> Unit): MockResponse =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                onFrame(webSocket, json.parseToJsonElement(text).jsonObject)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
        })
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
