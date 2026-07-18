package me.rerere.rikkahub.data.workflow.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

object WireEnvelopeCodec {
    private val knownFields = setOf(
        "v",
        "id",
        "accountId",
        "senderDeviceId",
        "targetId",
        "streamId",
        "seq",
        "createdAt",
        "expiresAt",
        "keyId",
        "cipherBundle",
    )

    fun decode(encoded: String, json: Json = Json): WireEnvelope {
        val root = json.parseToJsonElement(encoded).jsonObject
        val header = WireEnvelopeHeader(
            v = root.requiredNumber("v").int,
            id = root.required("id").jsonPrimitive.content,
            accountId = root.required("accountId").jsonPrimitive.content,
            senderDeviceId = root.required("senderDeviceId").jsonPrimitive.content,
            targetId = root.required("targetId").jsonPrimitive.content,
            streamId = root.required("streamId").jsonPrimitive.content,
            seq = root.requiredNumber("seq").long,
            createdAt = root.requiredNumber("createdAt").long,
            expiresAt = root["expiresAt"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.also {
                require(!it.isString) { "expiresAt must be a number or null" }
            }?.long,
            keyId = root.required("keyId").jsonPrimitive.content,
        ).also(WireEnvelopeHeader::validate)
        val cipherBundle = root.required("cipherBundle").jsonPrimitive.content
        require(cipherBundle.isNotEmpty() && BASE64_URL.matches(cipherBundle)) {
            "cipherBundle must be unpadded base64url"
        }
        return WireEnvelope(
            header = header,
            cipherBundle = cipherBundle,
            extensions = JsonObject(root.filterKeys { it !in knownFields }),
        )
    }

    fun encode(envelope: WireEnvelope): String = buildJsonObject {
        envelope.extensions.forEach { (key, value) -> if (key !in knownFields) put(key, value) }
        with(envelope.header) {
            put("v", v)
            put("id", id)
            put("accountId", accountId)
            put("senderDeviceId", senderDeviceId)
            put("targetId", targetId)
            put("streamId", streamId)
            put("seq", seq)
            put("createdAt", createdAt)
            put("expiresAt", expiresAt?.let(::JsonPrimitive) ?: JsonNull)
            put("keyId", keyId)
        }
        put("cipherBundle", envelope.cipherBundle)
    }.toString()

    private fun JsonObject.required(key: String) = requireNotNull(this[key]) { "missing $key" }

    private fun JsonObject.requiredNumber(key: String) = required(key).jsonPrimitive.also {
        require(!it.isString) { "$key must be a number" }
    }

    private val BASE64_URL = Regex("^[A-Za-z0-9_-]+$")
}
