package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

enum class AppServerConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    INITIALIZING,
    READY,
    FAILED,
}

data class AppServerConnectionState(
    val phase: AppServerConnectionPhase = AppServerConnectionPhase.DISCONNECTED,
    /** Logical Work connection that owns this transport state. */
    val connectionId: String? = null,
    val serverInfo: JsonObject? = null,
    val error: String? = null,
)

data class AppServerEndpoint(
    val webSocketUrl: String,
    val bearerToken: String,
    val connectionId: String = "default",
    /** Plain ws is only valid for a loopback listener or local unit test. */
    val allowInsecureLoopback: Boolean = false,
)

data class AppServerNotification(
    val method: String,
    val params: JsonElement = JsonNull,
    val connectionGeneration: Long = 0,
    val connectionId: String = "",
)

data class AppServerRequest(
    val id: JsonElement,
    val method: String,
    val params: JsonElement = JsonNull,
    val connectionGeneration: Long = 0,
    val connectionId: String = "",
)

class AppServerRpcException(
    val code: Int?,
    override val message: String,
    val data: JsonElement? = null,
) : IllegalStateException(message)

open class AppServerTransportException(
    override val message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class AppServerRequestTimeoutException(
    val method: String,
    val timeoutMs: Long,
    cause: Throwable,
) : AppServerTransportException(
    "Codex 请求 $method 在 ${timeoutMs / 1_000} 秒内没有响应",
    cause,
)

data class AppServerBackoffPolicy(
    val scheduleMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000),
    val jitterRatio: Double = 0.2,
) {
    init {
        require(scheduleMs.isNotEmpty() && scheduleMs.all { it > 0 })
        require(jitterRatio in 0.0..1.0)
    }

    fun delayMs(attempt: Int, jitterUnit: Double = 0.5): Long {
        require(attempt >= 0)
        require(jitterUnit in 0.0..1.0)
        val base = scheduleMs[attempt.coerceAtMost(scheduleMs.lastIndex)]
        val multiplier = 1.0 + ((jitterUnit * 2.0) - 1.0) * jitterRatio
        return (base * multiplier).toLong().coerceAtLeast(1)
    }
}
