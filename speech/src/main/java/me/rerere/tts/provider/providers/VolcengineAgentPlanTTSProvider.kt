package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val AGENT_PLAN_TTS_URL =
    "https://openspeech.bytedance.com/api/v3/plan/tts/unidirectional"
private const val AGENT_PLAN_TTS_RESOURCE_ID = "seed-tts-2.0"
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

class VolcengineAgentPlanTTSProvider : TTSProvider<TTSProviderSetting.VolcengineAgentPlan> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.VolcengineAgentPlan,
        request: TTSRequest,
    ): Flow<AudioChunk> = flow {
        require(providerSetting.apiKey.isNotBlank()) {
            "请先在火山引擎 Agent Plan 提供商中填写 API Key"
        }

        val requestBody = buildJsonObject {
            put("user", buildJsonObject { put("uid", "zhixing") })
            put("req_params", buildJsonObject {
                put("text", request.text)
                put("speaker", providerSetting.voice)
                put("audio_params", buildJsonObject {
                    put("format", providerSetting.format)
                    put("sample_rate", providerSetting.sampleRate)
                    put("speech_rate", providerSetting.speechRate)
                    put("loudness_rate", providerSetting.loudnessRate)
                    put("enable_timestamp", false)
                })
            })
        }
        val httpRequest = Request.Builder()
            .url(AGENT_PLAN_TTS_URL)
            .addHeader("X-Api-Key", providerSetting.apiKey)
            .addHeader("X-Api-Resource-Id", AGENT_PLAN_TTS_RESOURCE_ID)
            .addHeader("X-Api-Request-Id", Uuid.random().toString())
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                error("Agent Plan TTS 请求失败: HTTP ${response.code} $responseBody")
            }
            val chunks = parseAgentPlanTtsResponse(responseBody)
            chunks.forEachIndexed { index, bytes ->
                emit(
                    AudioChunk(
                        data = bytes,
                        format = providerSetting.format.toAudioFormat(),
                        sampleRate = providerSetting.sampleRate,
                        isLast = index == chunks.lastIndex,
                        metadata = mapOf(
                            "provider" to "volcengine_agent_plan",
                            "model" to "doubao-seed-tts-2.0",
                            "voice" to providerSetting.voice,
                        ),
                    )
                )
            }
        }
    }
}

internal fun parseAgentPlanTtsResponse(body: String): List<ByteArray> {
    val objects = splitJsonObjects(body)
    require(objects.isNotEmpty()) { "Agent Plan TTS 返回空响应" }
    val chunks = objects.mapNotNull { objectText ->
        val item = Json.parseToJsonElement(objectText).jsonObject
        val code = item["code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code == 20000000) {
            return@mapNotNull null
        }
        if (code != 0) {
            val message = item["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            error("Agent Plan TTS 返回错误: $code $message")
        }
        item["data"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { Base64.getDecoder().decode(it) }
    }
    require(chunks.isNotEmpty()) { "Agent Plan TTS 响应中没有音频数据" }
    return chunks
}

internal fun splitJsonObjects(body: String): List<String> {
    val result = mutableListOf<String>()
    var start = -1
    var depth = 0
    var inString = false
    var escaped = false
    body.forEachIndexed { index, char ->
        if (start < 0) {
            if (char == '{') {
                start = index
                depth = 1
            }
            return@forEachIndexed
        }
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                inString = false
            }
        } else {
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        result += body.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
    }
    require(start < 0 && !inString) { "Agent Plan TTS 返回了不完整的 JSON 数据" }
    return result
}

private fun String.toAudioFormat(): AudioFormat = when (lowercase()) {
    "wav" -> AudioFormat.WAV
    "pcm" -> AudioFormat.PCM
    "ogg" -> AudioFormat.OGG
    "opus" -> AudioFormat.OPUS
    "aac" -> AudioFormat.AAC
    else -> AudioFormat.MP3
}
