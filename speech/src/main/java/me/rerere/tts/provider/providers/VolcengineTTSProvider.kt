package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

private const val SERVER_FULL_RESPONSE = 0x9
private const val SERVER_AUDIO_ONLY_RESPONSE = 0xb
private const val SERVER_ERROR_RESPONSE = 0xf
private const val MESSAGE_FLAG_WITH_EVENT = 0x4
private const val EVENT_CONNECTION_STARTED = 50
private const val EVENT_CONNECTION_FAILED = 51
private const val EVENT_CONNECTION_FINISHED = 52
private const val EVENT_SESSION_FINISHED = 152
private const val EVENT_SESSION_FAILED = 153
private const val API_SUCCESS_CODE = 20_000_000

class VolcengineTTSProvider : TTSProvider<TTSProviderSetting.Volcengine> {
    private val client = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Volcengine,
        request: TTSRequest,
    ): Flow<AudioChunk> = callbackFlow {
        require(providerSetting.apiKey.isNotBlank()) { "请先填写火山引擎 API Key" }
        require(providerSetting.baseUrl.startsWith("wss://")) { "火山引擎 TTS 地址必须使用 wss://" }
        require(request.text.isNotBlank()) { "语音合成文本不能为空" }

        val connectId = Uuid.random().toString()
        val metadata = mapOf(
            "provider" to "volcengine",
            "model" to providerSetting.model,
            "voice" to providerSetting.voice,
        )
        val httpRequest = Request.Builder()
            .url(providerSetting.baseUrl)
            .header("X-Api-Key", providerSetting.apiKey)
            .header("X-Api-Resource-Id", providerSetting.model)
            .header("X-Api-Connect-Id", connectId)
            .build()

        val completed = AtomicBoolean(false)
        val receivedAudio = AtomicBoolean(false)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val sent = webSocket.send(
                    encodeVolcengineTtsRequest(providerSetting, request.text).toByteString()
                )
                if (!sent) close(IllegalStateException("火山引擎 TTS 请求发送失败"))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { parseVolcengineTtsMessage(bytes.toByteArray()) }
                    .onSuccess { message ->
                        when (message) {
                            is VolcengineServerMessage.Audio -> {
                                val result = trySend(
                                    AudioChunk(
                                        data = message.data,
                                        format = providerSetting.format.toAudioFormat(),
                                        sampleRate = providerSetting.sampleRate,
                                        metadata = metadata,
                                    )
                                )
                                if (result.isSuccess) receivedAudio.set(true)
                            }

                            VolcengineServerMessage.Finished -> {
                                completed.set(true)
                                if (receivedAudio.get()) {
                                    webSocket.close(1000, "TTS completed")
                                    close()
                                } else {
                                    webSocket.cancel()
                                    close(IllegalStateException("火山引擎 TTS 未返回音频数据"))
                                }
                            }

                            is VolcengineServerMessage.Error -> {
                                completed.set(true)
                                webSocket.close(1000, "TTS failed")
                                close(IllegalStateException(message.message))
                            }

                            VolcengineServerMessage.Ignored -> Unit
                        }
                    }
                    .onFailure {
                        completed.set(true)
                        webSocket.cancel()
                        close(it)
                    }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                completed.set(true)
                webSocket.cancel()
                close(IllegalStateException("火山引擎 TTS 返回了非预期文本帧: $text"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                completed.set(true)
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!completed.get()) {
                    close(IllegalStateException("火山引擎 TTS 连接提前关闭: $code $reason"))
                }
            }
        }

        val webSocket = client.newWebSocket(httpRequest, listener)
        awaitClose { webSocket.cancel() }
    }
}

internal fun encodeVolcengineTtsRequest(
    setting: TTSProviderSetting.Volcengine,
    text: String,
): ByteArray {
    val payload = buildJsonObject {
        put("user", buildJsonObject { put("uid", "zhixing-${Uuid.random()}") })
        put("req_params", buildJsonObject {
            put("text", text)
            put("speaker", setting.voice)
            put("audio_params", buildJsonObject {
                put("format", setting.format)
                put("sample_rate", setting.sampleRate)
                put("speech_rate", setting.speechRate)
                put("loudness_rate", setting.loudnessRate)
                put("enable_timestamp", false)
            })
        })
    }.toString().encodeToByteArray()
    return ByteBuffer.allocate(8 + payload.size)
        .order(ByteOrder.BIG_ENDIAN)
        .put(byteArrayOf(0x11, 0x10, 0x10, 0x00))
        .putInt(payload.size)
        .put(payload)
        .array()
}

internal sealed interface VolcengineServerMessage {
    data class Audio(val data: ByteArray) : VolcengineServerMessage
    data class Error(val message: String) : VolcengineServerMessage
    data object Finished : VolcengineServerMessage
    data object Ignored : VolcengineServerMessage
}

internal fun parseVolcengineTtsMessage(data: ByteArray): VolcengineServerMessage {
    require(data.size >= 4) { "火山引擎 TTS 响应头不完整" }
    val headerSize = (data[0].toInt() and 0x0f) * 4
    require(headerSize in 4..data.size) { "火山引擎 TTS 响应头长度非法" }
    val messageType = (data[1].toInt() ushr 4) and 0x0f
    val flags = data[1].toInt() and 0x0f
    var offset = headerSize

    if (messageType == SERVER_ERROR_RESPONSE) {
        val code = data.readInt(offset, "错误码")
        offset += 4
        if ((flags and MESSAGE_FLAG_WITH_EVENT) != 0) {
            val event = data.readInt(offset, "错误事件")
            offset += 4
            val idLabel = if (event in setOf(
                    EVENT_CONNECTION_STARTED,
                    EVENT_CONNECTION_FAILED,
                    EVENT_CONNECTION_FINISHED,
                )
            ) {
                "连接 ID"
            } else {
                "会话 ID"
            }
            val (_, nextOffset) = data.readLengthPrefixed(offset, idLabel)
            offset = nextOffset
        }
        val payload = data.readLengthPrefixed(offset, "错误消息").first
        return VolcengineServerMessage.Error(
            "火山引擎 TTS 协议错误 $code: ${payload.decodeToString()}"
        )
    }

    if (flags == 0x1 || flags == 0x3) {
        data.readInt(offset, "序列号")
        offset += 4
    }

    var event: Int? = null
    if ((flags and MESSAGE_FLAG_WITH_EVENT) != 0) {
        event = data.readInt(offset, "事件")
        offset += 4
        if (event !in setOf(EVENT_CONNECTION_STARTED, EVENT_CONNECTION_FAILED, EVENT_CONNECTION_FINISHED)) {
            val (_, nextOffset) = data.readLengthPrefixed(offset, "会话 ID")
            offset = nextOffset
        } else {
            val (_, nextOffset) = data.readLengthPrefixed(offset, "连接 ID")
            offset = nextOffset
        }
    }

    if (messageType !in setOf(SERVER_FULL_RESPONSE, SERVER_AUDIO_ONLY_RESPONSE)) {
        return VolcengineServerMessage.Ignored
    }
    val (payload, _) = data.readLengthPrefixed(offset, "响应负载")
    if (messageType == SERVER_AUDIO_ONLY_RESPONSE) {
        return VolcengineServerMessage.Audio(payload)
    }

    val statusError = payload.statusError()
    if (event == EVENT_CONNECTION_FAILED || event == EVENT_SESSION_FAILED || statusError != null) {
        return VolcengineServerMessage.Error(statusError ?: payload.decodeToString())
    }
    return if (event == EVENT_SESSION_FINISHED) {
        VolcengineServerMessage.Finished
    } else {
        VolcengineServerMessage.Ignored
    }
}

private fun ByteArray.statusError(): String? = runCatching {
    if (isEmpty()) return null
    val json = Json.parseToJsonElement(decodeToString()).jsonObject
    val code = json["status_code"]?.jsonPrimitive?.intOrNull
        ?: json["code"]?.jsonPrimitive?.intOrNull
        ?: return null
    if (code == 0 || code == API_SUCCESS_CODE) return null
    val message = json["message"]?.jsonPrimitive?.contentOrNull
        ?: json["error"]?.jsonPrimitive?.contentOrNull
        ?: decodeToString()
    "火山引擎 TTS 请求失败 $code: $message"
}.getOrNull()

private fun ByteArray.readInt(offset: Int, label: String): Int {
    require(offset >= 0 && offset + 4 <= size) { "火山引擎 TTS 响应缺少$label" }
    return ByteBuffer.wrap(this, offset, 4).order(ByteOrder.BIG_ENDIAN).int
}

private fun ByteArray.readLengthPrefixed(offset: Int, label: String): Pair<ByteArray, Int> {
    val length = readInt(offset, "${label}长度")
    require(length >= 0) { "火山引擎 TTS ${label}长度非法" }
    val start = offset + 4
    require(start + length <= size) { "火山引擎 TTS ${label}不完整" }
    return copyOfRange(start, start + length) to (start + length)
}

private fun String.toAudioFormat(): AudioFormat = when (lowercase()) {
    "wav" -> AudioFormat.WAV
    "pcm" -> AudioFormat.PCM
    "ogg" -> AudioFormat.OGG
    "opus" -> AudioFormat.OPUS
    "aac" -> AudioFormat.AAC
    else -> AudioFormat.MP3
}
