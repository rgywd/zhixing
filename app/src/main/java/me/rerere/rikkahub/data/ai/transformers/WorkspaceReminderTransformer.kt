package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定 workspace 时注入基本上下文；Rootfs 就绪时再追加 Shell 能力。
 * 已识别 OrbitOS vault 时只注入目录和维护边界，不把检索提升为普通对话的前置步骤。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        val vaultReady = runCatching {
            workspaceRepository.isKnowledgeVaultInitialized(workspaceId)
        }.getOrDefault(false)
        val prompt = buildWorkspacePrompt(
            workspace = workspace,
            cwd = ctx.workspaceCwd,
            vaultReady = vaultReady,
        )

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

internal fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    cwd: String? = null,
    vaultReady: Boolean = false,
): String = buildString {
    appendLine("<workspace>")
    appendLine("You are bound to a persistent local workspace named \"${workspace.name}\".")
    if (vaultReady) {
        appendLine("- The OrbitOS CN vault is stored at `vault/` (inside Rootfs: `/workspace/vault`).")
        appendLine("- For vault maintenance, read `vault/AGENTS.md` first and treat it as the routing contract.")
        appendLine("- Unclassified captures go to `00_收件箱`; daily notes use `YYYY-MM-DD.md`; projects use C.A.P. (Context / Actions / Progress).")
        appendLine("- Research main notes live under `30_研究/<领域>/<主题>/<主题>.md` with `type: reference`.")
        appendLine("- Atomic concepts live under `40_知识库/<分类>/<概念名>.md` using the Wiki template.")
        appendLine("- Tool entries live under `60_工具/<类别>/<工具名>.md` with `type: tool`; plans use `90_计划/Plan_YYYY-MM-DD_<主题>.md`.")
        appendLine("- Vault-local workflow skills live at `vault/.agents/skills` (inside Rootfs: `/workspace/vault/.agents/skills`). Read a skill's `SKILL.md` before using it.")
        appendLine("- Do not read credentials or modify `.git`; only run Git mutations when the user explicitly asks.")
        appendLine("- Do not search the vault as a default preflight; use its knowledge tools only when the request depends on vault content.")
    }
    if (workspace.shellStatus == WorkspaceShellStatus.READY.name) {
        appendLine("- A sandboxed Linux Rootfs is ready. The persistent files area is mounted at `/workspace`.")
        appendLine("- Workspace tool paths must be absolute inside the Rootfs, for example `/workspace/notes.md`.")
        appendLine("- Use `workspace_read_file`, `workspace_write_file`, `workspace_edit_file`, and `workspace_shell` for project execution.")
        appendLine("- Use the dedicated `gh` tool for GitHub Issues; do not handle GitHub credentials in the shell.")
        appendLine("- App-global skills are mounted at `/skills`; vault-local workflows remain under `/workspace/vault/.agents/skills`.")
        appendLine("- `/upload` is read-only. Copy a file to `/workspace` before changing it.")
        if (!cwd.isNullOrBlank()) {
            appendLine("- Current working directory: `$cwd`.")
        }
    }
    append("</workspace>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
