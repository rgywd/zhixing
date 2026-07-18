package me.rerere.rikkahub.data.workflow.wire

import kotlinx.serialization.json.JsonObject

const val WIRE_MAJOR = 1
const val WIRE_MINOR = 0
const val MAX_WIRE_INTEGER = 9_007_199_254_740_991L

enum class WireErrorCode {
    WIRE_MAJOR_UNSUPPORTED,
    CAPABILITY_UNSUPPORTED,
    DECRYPTION_FAILED,
    REPLAY_REJECTED,
    REVISION_CONFLICT,
    REQUEST_EXPIRED,
}

class WireProtocolException(
    val code: WireErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

data class WireHello(
    val wireMajor: Int,
    val wireMinor: Int,
    val clientKind: String,
    val clientVersion: String,
    val deviceId: String,
    val capabilities: Set<String>,
)

data class WireEnvelopeHeader(
    val v: Int,
    val id: String,
    val accountId: String,
    val senderDeviceId: String,
    val targetId: String,
    val streamId: String,
    val seq: Long,
    val createdAt: Long,
    val expiresAt: Long?,
    val keyId: String,
)

data class WireEnvelope(
    val header: WireEnvelopeHeader,
    val cipherBundle: String,
    val extensions: JsonObject = JsonObject(emptyMap()),
)

fun WireHello.requireWritable(requiredCapabilities: Set<String>) {
    if (wireMajor != WIRE_MAJOR) {
        throw WireProtocolException(
            WireErrorCode.WIRE_MAJOR_UNSUPPORTED,
            "wire major $wireMajor is not writable by $WIRE_MAJOR.$WIRE_MINOR",
        )
    }
    val missing = requiredCapabilities - capabilities
    if (missing.isNotEmpty()) {
        throw WireProtocolException(
            WireErrorCode.CAPABILITY_UNSUPPORTED,
            "missing capabilities: ${missing.sorted().joinToString()}",
        )
    }
}

internal fun WireEnvelopeHeader.validate() {
    require(v >= 0) { "v must be non-negative" }
    requireOpaqueId(id, "id")
    requireOpaqueId(accountId, "accountId")
    requireOpaqueId(senderDeviceId, "senderDeviceId")
    requireOpaqueId(targetId, "targetId")
    requireOpaqueId(streamId, "streamId")
    requireSafeWireInteger(seq, "seq")
    requireSafeWireInteger(createdAt, "createdAt")
    expiresAt?.let { requireSafeWireInteger(it, "expiresAt") }
    requireOpaqueId(keyId, "keyId")
}

private fun requireOpaqueId(value: String, field: String) {
    require(value.isNotEmpty() && OPAQUE_ID.matches(value)) { "$field must be an opaque ID" }
}

private fun requireSafeWireInteger(value: Long, field: String) {
    require(value in 0..MAX_WIRE_INTEGER) { "$field must be a non-negative JSON-safe integer" }
}

private val OPAQUE_ID = Regex("^[A-Za-z0-9_-]+$")
