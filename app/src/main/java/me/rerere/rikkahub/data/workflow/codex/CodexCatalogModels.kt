package me.rerere.rikkahub.data.workflow.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class CodexRuntimeState {
    UNKNOWN,
    IDLE,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_USER,
    SYSTEM_ERROR;

    companion object {
        fun fromWire(value: String): CodexRuntimeState = when (value) {
            "idle" -> IDLE
            "running" -> RUNNING
            "waiting_approval" -> WAITING_APPROVAL
            "waiting_user" -> WAITING_USER
            "system_error" -> SYSTEM_ERROR
            else -> UNKNOWN
        }
    }
}

data class CodexMachine(
    val machineId: String,
    val displayName: String,
    val platformFamily: String,
    val platformOs: String,
    val agentVersion: String,
    val codexVersion: String?,
    val runtimeWritable: Boolean,
    val compatibilityReason: String?,
    val lastSeenAt: Long,
)

data class CodexProject(
    val projectId: String,
    val machineId: String,
    val displayName: String,
    val canonicalRoot: String,
    val existsOnDisk: Boolean,
    val vcsKind: String,
    val branch: String?,
    val updatedAt: Long,
    val isPinned: Boolean,
    val isHidden: Boolean,
    val machine: CodexMachine?,
    val threads: List<CodexThread>,
) {
    val primaryThreads: List<CodexThread> get() = threads.filter { !it.isSubagent && !it.isAutomation }
    val currentThreads: List<CodexThread> get() = primaryThreads.filterNot(CodexThread::archived)
    val archivedThreads: List<CodexThread> get() = primaryThreads.filter(CodexThread::archived)
    val automations: List<CodexThread> get() = threads.filter(CodexThread::isAutomation)
    val needsAttention: Int get() = threads.count { it.runtimeState.needsAttention }
    val activeAt: Long get() = currentThreads.maxOfOrNull(CodexThread::recencyAt) ?: updatedAt
}

data class CodexThread(
    val machineId: String,
    val threadId: String,
    val projectId: String,
    val name: String,
    val preview: String,
    val createdAt: Long,
    val updatedAt: Long,
    val recencyAt: Long,
    val archived: Boolean,
    val source: String,
    val parentThreadId: String?,
    val forkedFromId: String?,
    val isSubagent: Boolean,
    val isAutomation: Boolean,
    val runtimeState: CodexRuntimeState,
    val rawStatus: String,
    val isPinned: Boolean,
)

data class CodexThreadDetail(
    val thread: CodexThread?,
    val turns: List<CodexTurn>,
    val approvals: List<CodexApproval> = emptyList(),
    val cwd: String? = null,
    val attachments: Map<String, CodexAttachment> = emptyMap(),
)

data class CodexAttachment(
    val remotePath: String,
    val localUri: String,
    val fileName: String,
    val mime: String,
)

data class CodexApproval(
    val approvalId: String,
    val kind: String,
    val summary: String,
    val createdAt: Long,
    val payload: JsonObject = JsonObject(emptyMap()),
) {
    val itemId: String?
        get() = (payload["itemId"] as? JsonPrimitive)?.contentOrNull
}

data class CodexTurn(
    val turnId: String,
    val status: String,
    val startedAt: Long?,
    val completedAt: Long?,
    val error: String?,
    val items: List<CodexItem>,
)

data class CodexItem(
    val itemId: String,
    val type: String,
    val rawType: String,
    val role: String,
    val text: String?,
    val status: String?,
    val raw: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RuntimeCatalogPayload(
    val machineId: String,
    val cwd: String,
    val models: List<CodexModelOption> = emptyList(),
    val permissionProfiles: List<CodexPermissionProfile> = emptyList(),
    val skills: List<CodexSkillOption> = emptyList(),
    val plugins: List<CodexPluginOption> = emptyList(),
    val apps: List<CodexAppOption> = emptyList(),
    val generatedAt: Long,
)

@Serializable
data class CodexModelOption(
    val id: String,
    val model: String,
    val displayName: String,
    val description: String = "",
    val isDefault: Boolean = false,
    val hidden: Boolean = false,
    val defaultReasoningEffort: String,
    val supportedReasoningEfforts: List<CodexReasoningOption> = emptyList(),
    val inputModalities: List<String> = listOf("text", "image"),
    val serviceTiers: List<CodexServiceTier> = emptyList(),
    val defaultServiceTier: String? = null,
)

@Serializable
data class CodexReasoningOption(
    val reasoningEffort: String,
    val description: String = "",
)

@Serializable
data class CodexServiceTier(
    val id: String,
    val name: String,
    val description: String = "",
)

@Serializable
data class CodexPermissionProfile(
    val id: String,
    val allowed: Boolean,
    val description: String? = null,
)

@Serializable
data class CodexSkillOption(
    val name: String,
    val path: String,
    val description: String = "",
    val shortDescription: String? = null,
    val enabled: Boolean = true,
    val scope: String = "user",
    @SerialName("interface") val interfaceInfo: CodexSkillInterface? = null,
)

@Serializable
data class CodexSkillInterface(
    val displayName: String? = null,
    val shortDescription: String? = null,
    val defaultPrompt: String? = null,
)

@Serializable
data class CodexPluginOption(
    val id: String,
    val name: String,
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val availability: String = "AVAILABLE",
    @SerialName("interface") val interfaceInfo: CodexPluginInterface? = null,
)

@Serializable
data class CodexPluginInterface(
    val displayName: String? = null,
    val shortDescription: String? = null,
)

@Serializable
data class CodexAppOption(
    val id: String,
    val name: String,
    val description: String? = null,
    val isAccessible: Boolean = false,
    val isEnabled: Boolean = true,
)

data class CodexRuntimeSettingsState(
    val model: String? = null,
    val effort: String? = null,
    val serviceTier: String? = null,
    val permissions: String? = null,
    val usedTokens: Long? = null,
    val contextWindow: Long? = null,
    val updatedAt: Long = 0,
) {
    val contextPercent: Int?
        get() = if (usedTokens != null && contextWindow != null && contextWindow > 0) {
            ((usedTokens * 100) / contextWindow).toInt().coerceIn(0, 100)
        } else null
}

@Serializable
data class CodexInputPayload(
    val type: String,
    val text: String? = null,
    val url: String? = null,
    val path: String? = null,
    val name: String? = null,
    val detail: String? = null,
)

val CodexRuntimeState.needsAttention: Boolean
    get() = this == CodexRuntimeState.WAITING_APPROVAL || this == CodexRuntimeState.WAITING_USER

@Serializable
data class CatalogSnapshotPayload(
    val revision: Long,
    val generatedAt: Long,
    val machine: CatalogMachinePayload,
    val projects: List<CatalogProjectPayload>,
    val threads: List<CatalogThreadPayload>,
)

@Serializable
data class CatalogSnapshotChunkPayload(
    val snapshotId: String,
    val revision: Long,
    val generatedAt: Long,
    val machine: CatalogMachinePayload,
    val chunkIndex: Int,
    val chunkCount: Int,
    val contentHash: String,
    val chunkHash: String,
    val contentBase64: String,
)

@Serializable
data class CatalogSnapshotChunkContent(
    val projects: List<CatalogProjectPayload> = emptyList(),
    val threads: List<CatalogThreadPayload> = emptyList(),
)

@Serializable
data class CatalogMachinePayload(
    val machineId: String,
    val displayName: String,
    val platformFamily: String,
    val platformOs: String,
    val agentVersion: String,
    val codexVersion: String? = null,
    val schemaHash: String? = null,
    val runtimeWritable: Boolean,
    val compatibilityReason: String? = null,
    val operations: List<String> = emptyList(),
    val lastSeenAt: Long,
)

@Serializable
data class CatalogProjectPayload(
    val projectId: String,
    val machineId: String,
    val displayName: String,
    val canonicalRoot: String,
    val existsOnDisk: Boolean = true,
    val vcs: CatalogVcsPayload,
    val updatedAt: Long,
)

@Serializable
data class CatalogVcsPayload(
    val kind: String,
    val originUrl: String? = null,
    val branch: String? = null,
)

@Serializable
data class CatalogThreadPayload(
    val machineId: String,
    val threadId: String,
    val projectId: String,
    val name: String,
    val preview: String,
    val createdAt: Long,
    val updatedAt: Long,
    val recencyAt: Long,
    val archived: Boolean,
    val source: String,
    val threadSource: String? = null,
    val parentThreadId: String? = null,
    val forkedFromId: String? = null,
    val isSubagent: Boolean,
    val isAutomation: Boolean,
    val runtimeState: String,
    val rawStatus: String,
)

@Serializable
data class ThreadDetailPayload(
    val machineId: String,
    val threadId: String,
    val turns: List<CatalogTurnPayload>,
)

@Serializable
data class CatalogTurnPayload(
    val turnId: String,
    val status: String,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val durationMs: Long? = null,
    val error: String? = null,
    val items: List<CatalogItemPayload>,
)

@Serializable
data class CatalogItemPayload(
    val itemId: String,
    val type: String,
    val rawType: String,
    val role: String,
    val text: String? = null,
    val status: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RuntimeCommandPayload(
    val command: String,
    val machineId: String,
    val threadId: String? = null,
    val cwd: String? = null,
    val text: String? = null,
    val input: List<CodexInputPayload> = emptyList(),
    val confirmedUnknown: Boolean? = null,
    val approvalId: String? = null,
    val decision: String? = null,
    val answer: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val approvalPolicy: String? = null,
    val sandbox: String? = null,
    val serviceTier: String? = null,
    val permissions: String? = null,
    val attachmentId: String? = null,
    val fileName: String? = null,
    val mime: String? = null,
    val chunkIndex: Int? = null,
    val chunkCount: Int? = null,
    val contentBase64: String? = null,
    val sha256: String? = null,
    val path: String? = null,
)

@Serializable
data class ThreadDetailChunkPayload(
    val detailId: String,
    val machineId: String,
    val threadId: String,
    val chunkIndex: Int,
    val chunkCount: Int,
    val contentHash: String,
    val chunkHash: String,
    val contentBase64: String,
)

@Serializable
data class RuntimeEventPayload(
    val machineId: String,
    val threadId: String,
    val eventId: String,
    val type: String,
    val at: Long,
    val bindingId: String? = null,
    val state: String? = null,
    val turnId: String? = null,
    val itemId: String? = null,
    val itemType: String? = null,
    val role: String? = null,
    val text: String? = null,
    val status: String? = null,
    val approvalId: String? = null,
    val kind: String? = null,
    val summary: String? = null,
    val decision: String? = null,
    val message: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
    val model: String? = null,
    val effort: String? = null,
    val serviceTier: String? = null,
    val permissions: String? = null,
    val usedTokens: Long? = null,
    val contextWindow: Long? = null,
)

@Serializable
data class CommandResultPayload(
    val requestId: String? = null,
    val machineId: String,
    val threadId: String? = null,
    val command: String,
    val ok: Boolean,
    val error: String? = null,
    val result: JsonObject? = null,
)
