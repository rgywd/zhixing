package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class AppServerCompatibilityLevel {
    FULL,
    TEXT_ONLY,
    READ_ONLY,
    INCOMPATIBLE,
}

data class AppServerCompatibility(
    val level: AppServerCompatibilityLevel,
    val reason: String? = null,
)

data class AppServerRuntimeFacts(
    val codexVersion: String?,
    val schemaHash: String?,
    val methods: Set<String> = emptySet(),
    val attachmentSupervisorReady: Boolean = false,
)

object AppServerCompatibilityGate {
    const val REVIEWED_CODEX_VERSION = "0.144.0"
    const val REVIEWED_SCHEMA_HASH = "e75404842a291fc0473a34abc0d3cd9b036182210f3c0e37709515dbab247ba0"

    val requiredWriteMethods = setOf(
        "thread/start",
        "thread/resume",
        "thread/read",
        "turn/start",
        "turn/steer",
        "turn/interrupt",
    )

    fun evaluateInitialize(serverInfo: JsonObject?): AppServerCompatibility {
        val userAgent = (serverInfo?.get("userAgent") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val version = VERSION_PATTERN.find(userAgent)?.groupValues?.getOrNull(1)
        if (version != REVIEWED_CODEX_VERSION) {
            return AppServerCompatibility(
                if (version == null) AppServerCompatibilityLevel.READ_ONLY else AppServerCompatibilityLevel.INCOMPATIBLE,
                if (version == null) "App Server 未提供可验证版本" else "Codex $version 尚未通过兼容性审核",
            )
        }
        val requiredFields = setOf("codexHome", "platformFamily", "platformOs")
        val missing = requiredFields.filter { key ->
            (serverInfo?.get(key) as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
        }
        if (missing.isNotEmpty()) {
            return AppServerCompatibility(
                AppServerCompatibilityLevel.READ_ONLY,
                "App Server initialize 缺少字段：${missing.sorted().joinToString()}",
            )
        }
        return AppServerCompatibility(
            AppServerCompatibilityLevel.TEXT_ONLY,
            "Codex $version 已核对；未配置 Supervisor，附件暂不可用",
        )
    }

    fun evaluate(facts: AppServerRuntimeFacts): AppServerCompatibility {
        if (facts.codexVersion != null && facts.codexVersion != REVIEWED_CODEX_VERSION) {
            return AppServerCompatibility(
                AppServerCompatibilityLevel.INCOMPATIBLE,
                "Codex ${facts.codexVersion} has not been reviewed (expected $REVIEWED_CODEX_VERSION)",
            )
        }
        if (facts.schemaHash != null && facts.schemaHash != REVIEWED_SCHEMA_HASH) {
            return AppServerCompatibility(
                AppServerCompatibilityLevel.INCOMPATIBLE,
                "Codex App Server schema has changed",
            )
        }
        if (facts.codexVersion == null || facts.schemaHash == null) {
            return AppServerCompatibility(
                AppServerCompatibilityLevel.READ_ONLY,
                "Waiting for the local supervisor compatibility probe",
            )
        }
        val missing = requiredWriteMethods - facts.methods
        if (missing.isNotEmpty()) {
            return AppServerCompatibility(
                AppServerCompatibilityLevel.READ_ONLY,
                "Missing required App Server methods: ${missing.sorted().joinToString()}",
            )
        }
        return if (facts.attachmentSupervisorReady) {
            AppServerCompatibility(AppServerCompatibilityLevel.FULL)
        } else {
            AppServerCompatibility(
                AppServerCompatibilityLevel.TEXT_ONLY,
                "Attachment supervisor is unavailable",
            )
        }
    }

    fun evaluateWithSupervisor(
        direct: AppServerCompatibility,
        facts: AppServerRuntimeFacts,
    ): AppServerCompatibility = if (direct.level == AppServerCompatibilityLevel.TEXT_ONLY) {
        evaluate(facts)
    } else {
        direct
    }

    private val VERSION_PATTERN = Regex(
        "^Codex Desktop/(\\d+\\.\\d+\\.\\d+) \\([^()\\r\\n]+\\)$",
    )
}
