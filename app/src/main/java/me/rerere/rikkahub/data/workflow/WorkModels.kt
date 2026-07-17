package me.rerere.rikkahub.data.workflow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 远端 Coding Agent 类型。wireName 是 happy-cli spawn-happy-session 的 agent 参数值。
 */
enum class WorkAgent(val wireName: String) {
    CODEX("codex"),
    CLAUDE("claude"),
    OTHER("");

    companion object {
        fun detect(flavor: String?, codexThreadId: String?): WorkAgent = when {
            flavor.equals("codex", ignoreCase = true) || !codexThreadId.isNullOrBlank() -> CODEX
            flavor.equals("claude", ignoreCase = true) || flavor.isNullOrBlank() -> CLAUDE
            else -> OTHER
        }

        fun fromName(name: String?): WorkAgent =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OTHER
    }
}

data class WorkMachine(
    val id: String,
    val host: String,
    val displayName: String?,
    val platform: String?,
    val active: Boolean,
    val activeAt: Long,
    val supportsCodex: Boolean?,
    val supportsClaude: Boolean?,
    val homeDir: String?,
) {
    val label: String get() = displayName ?: host

    fun supports(agent: WorkAgent): Boolean = when (agent) {
        WorkAgent.CODEX -> supportsCodex != false
        WorkAgent.CLAUDE -> supportsClaude != false
        WorkAgent.OTHER -> false
    }
}

@Serializable
data class WorkApproval(
    val id: String,
    val tool: String,
    val arguments: String,
)

data class WorkSession(
    val id: String,
    val machineId: String?,
    val path: String?,
    val host: String?,
    val name: String?,
    val agent: WorkAgent,
    val active: Boolean,
    val activeAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val approvals: List<WorkApproval>,
    /** false 表示该会话的 data key 无法解封，内容不可读，但仍保留在列表中 */
    val decryptable: Boolean,
    /** 最近一条用户消息 meta 携带的 permissionMode；CLI 侧模式按会话粘滞 */
    val lastPermissionMode: String? = null,
) {
    val isFullAccess: Boolean get() = lastPermissionMode == PERMISSION_MODE_FULL_ACCESS
}

enum class WorkRole { USER, AGENT }

/**
 * 结构化消息片段。以多态 JSON 存入 Room，序列化名一旦发布不可更改。
 */
@Serializable
sealed interface WorkMessagePart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : WorkMessagePart

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val text: String) : WorkMessagePart

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val name: String,
        val input: String = "",
        val callId: String? = null,
        val title: String? = null,
    ) : WorkMessagePart

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val output: String,
        val callId: String? = null,
        val isError: Boolean = false,
    ) : WorkMessagePart

    @Serializable
    @SerialName("file_edit")
    data class FileEdit(
        val filePath: String,
        val description: String? = null,
        val diff: String? = null,
    ) : WorkMessagePart

    @Serializable
    @SerialName("terminal")
    data class Terminal(val output: String) : WorkMessagePart

    @Serializable
    @SerialName("event")
    data class Event(val kind: String, val text: String? = null) : WorkMessagePart

    /** 未识别的记录类型，保底不丢内容 */
    @Serializable
    @SerialName("raw")
    data class Raw(val kind: String, val text: String) : WorkMessagePart
}

data class WorkMessage(
    val id: String,
    val sessionId: String,
    val seq: Long,
    val role: WorkRole,
    val parts: List<WorkMessagePart>,
    val createdAt: Long,
)

/** 完全访问模式对应的 Happy permissionMode 取值，Codex 与 Claude 共用 */
const val PERMISSION_MODE_FULL_ACCESS = "bypassPermissions"

/**
 * 思考深度档位（存储值即官方取值）。
 *
 * - Claude Code：low/medium/high/xhigh/max，经 spawn 环境变量
 *   `CLAUDE_CODE_EFFORT_LEVEL` 下发（xhigh 仅 Fable 5 / Sonnet 5 / Opus 4.7+ 支持）。
 * - Codex：wire 值来自 openai/codex 的 ReasoningEffort 枚举
 *   （none/minimal/low/medium/high/xhigh/max/ultra），UI 只暴露 5.6 系列
 *   客户端展示的常用档（low≈轻度、xhigh≈极高、ultra 更快消耗额度）。
 *   官方无环境变量通道、happy-cli meta 也不透传，目前无法远程下发，
 *   仅保存偏好并在 UI 中如实标注（需在开发机 config.toml 配置）。
 */
object WorkReasoningEffort {
    val CLAUDE_LEVELS = listOf("low", "medium", "high", "xhigh", "max")
    val CODEX_LEVELS = listOf("low", "medium", "high", "xhigh", "max", "ultra")

    fun levelsFor(agent: WorkAgent): List<String> = when (agent) {
        WorkAgent.CLAUDE -> CLAUDE_LEVELS
        WorkAgent.CODEX -> CODEX_LEVELS
        WorkAgent.OTHER -> emptyList()
    }

    fun isRemotelyApplicable(agent: WorkAgent): Boolean = agent == WorkAgent.CLAUDE
}

/** 思考深度经 spawn 的 environmentVariables 下发；目前仅 Claude Code 有官方环境变量通道 */
fun spawnEnvironment(agent: WorkAgent, reasoningEffort: String?): Map<String, String> {
    if (agent != WorkAgent.CLAUDE || reasoningEffort == null) return emptyMap()
    if (reasoningEffort !in WorkReasoningEffort.CLAUDE_LEVELS) return emptyMap()
    return mapOf("CLAUDE_CODE_EFFORT_LEVEL" to reasoningEffort)
}

data class WorkSpawnRequest(
    val machineId: String,
    val directory: String,
    val agent: WorkAgent,
    val prompt: String,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val fullAccess: Boolean = false,
    val disallowedTools: List<String> = emptyList(),
    val approvedNewDirectoryCreation: Boolean = false,
)

data class RepoPreset(
    val id: String,
    val name: String,
    val machineId: String,
    val path: String,
    val defaultBranch: String?,
    val agent: WorkAgent,
    val model: String?,
    val reasoningEffort: String?,
    /** 完全访问：随每条消息以 permissionMode=bypassPermissions 下发远端 */
    val fullAccess: Boolean,
    /** 硬性限制，Claude 侧映射为 disallowedTools；Codex 侧协议不支持，仅提示层生效 */
    val disallowedTools: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
)
